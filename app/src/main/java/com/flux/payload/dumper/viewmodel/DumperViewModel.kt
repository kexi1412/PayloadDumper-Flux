package com.flux.payload.dumper.viewmodel

import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chromeos_update_engine.UpdateMetadata.PartitionUpdate
import com.flux.payload.dumper.DumperApplication
import com.flux.payload.dumper.R
import com.flux.payload.dumper.core.Diagnostics
import com.flux.payload.dumper.core.FileSource
import com.flux.payload.dumper.core.HttpSource
import com.flux.payload.dumper.core.NestedZipStream
import com.flux.payload.dumper.core.Net
import com.flux.payload.dumper.core.ParseResult
import com.flux.payload.dumper.core.PayloadExtractor
import com.flux.payload.dumper.core.PayloadParser
import com.flux.payload.dumper.core.PayloadSource
import com.flux.payload.dumper.core.ResumeState
import com.flux.payload.dumper.core.UniversalParser
import com.flux.payload.dumper.core.UriSource
import com.flux.payload.dumper.core.toHex
import com.flux.payload.dumper.data.Preferences
import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.model.ExtractState
import com.flux.payload.dumper.model.Payload
import com.flux.payload.dumper.model.PartitionInfo
import com.flux.payload.dumper.model.VerifyState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

sealed interface ParseState {
    data object Idle : ParseState

    /**
     * Parsing in progress. [detail] carries live progress for long streaming operations such
     * as walking a 9 GB nested Honor ZIP, where a silent spinner would look like a hang.
     */
    data class Parsing(val detail: String? = null) : ParseState

    data class Ready(val archive: ArchiveInfo) : ParseState
    data class Failed(val message: String) : ParseState
}

class DumperViewModel : ViewModel() {

    private val client = Net.buildClient()
    private var source: PayloadSource? = null
    private var payload: Payload? = null
    private val extractor = PayloadExtractor()
    private val jobs = mutableMapOf<String, Job>()

    // Resume support: per-partition completed-op state, plus the output dir pinned at first parse
    // (so relinking to a differently-named mirror still writes to — and resumes from — one folder).
    private val resumeStates = mutableMapOf<String, ResumeState>()
    private var sessionOutputDir: File? = null

    // A partition whose network extraction stalled (e.g. expired link) and needs a fresh URL.
    private val _relink = MutableStateFlow<String?>(null)
    val relink: StateFlow<String?> = _relink.asStateFlow()

    private val _input = MutableStateFlow(Preferences.getString(Preferences.KEY_PATH_OR_URL) ?: "")
    val input: StateFlow<String> = _input.asStateFlow()

    private val _parseState = MutableStateFlow<ParseState>(ParseState.Idle)
    val parseState: StateFlow<ParseState> = _parseState.asStateFlow()

    private val _partitions = MutableStateFlow<List<PartitionInfo>>(emptyList())
    private val _search = MutableStateFlow("")
    val search: StateFlow<String> = _search.asStateFlow()

    val partitions: StateFlow<List<PartitionInfo>> =
        combine(_partitions, _search) { list, q ->
            if (q.isBlank()) {
                list
            } else {
                // Match every name a row can show. `partitionName` is only the key — for nested
                // entries it is archive-qualified, so a user typing the short name they see in
                // the list ("preload.img") would otherwise get nothing back.
                list.filter {
                    it.partitionName.contains(q, ignoreCase = true) ||
                        it.displayTitle.contains(q, ignoreCase = true) ||
                        it.subtitle.contains(q, ignoreCase = true)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _snackbar = MutableStateFlow<String?>(null)
    val snackbar: StateFlow<String?> = _snackbar.asStateFlow()

    fun updateInput(value: String) {
        _input.value = value
        Preferences.setString(Preferences.KEY_PATH_OR_URL, value)
    }

    fun updateSearch(value: String) { _search.value = value }

    fun consumeSnackbar() { _snackbar.value = null }

    /** Resolve a localized string against the app context, so status/error messages follow the UI language. */
    private fun str(@StringRes resId: Int, vararg args: Any): String =
        DumperApplication.appContext.getString(resId, *args)

    private var parseResult: ParseResult? = null
    private val rawEntries = mutableMapOf<String, ParseResult.RawEntry>()  // for non-CrAU formats

    /** Names already produced by a batched nested-ZIP pass, so extractAll() skips them. */
    private val _extractedInBatch = mutableSetOf<String>()

    fun parse() {
        val raw = _input.value.trim()
        if (raw.isEmpty()) { _snackbar.value = str(R.string.msg_input_required); return }
        _parseState.value = ParseState.Parsing("Opening archive…")
        // Held outside the runCatching so the failure path can still probe the archive that was
        // actually opened. Assigning only on success (as before) meant a parse failure had
        // nothing to report on — exactly the case the report exists for.
        var opened: PayloadSource? = null
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    closeSource()
                    val src: PayloadSource = when {
                        raw.startsWith("http://") || raw.startsWith("https://") ->
                            HttpSource.open(raw, client)
                        raw.startsWith("content://") ->
                            runCatching { UriSource.open(DumperApplication.appContext, Uri.parse(raw)) }
                                .getOrElse { throw java.io.IOException(str(R.string.err_file_unreadable, raw)) }
                        else -> {
                            val f = File(raw)
                            if (!f.exists() || !f.canRead()) throw java.io.IOException(str(R.string.err_file_unreadable, raw))
                            FileSource(f)
                        }
                    }
                    opened = src
                    _parseState.value = ParseState.Parsing(
                        "Measuring ${src.fileName()} — ${src.size / (1 shl 20)} MB…"
                    )
                    val result = UniversalParser.parse(src) { msg ->
                        _parseState.value = ParseState.Parsing(msg)
                    }
                    source = src
                    parseResult = result
                    result
                }
            }.onSuccess { result ->
                sessionOutputDir = resolveOutputDir(srcFileName(result))
                resumeStates.clear()
                rawEntries.clear()
                _extractedInBatch.clear()

                when (result) {
                    is ParseResult.CrAU -> {
                        payload = result.payload
                        _partitions.value = result.payload.manifest.partitionsList.map { it.toInfo() }
                        _parseState.value = ParseState.Ready(
                            ArchiveInfo(
                                fileName = result.payload.fileName,
                                fileSize = result.payload.archiveSize,
                                securityPatchLevel = result.payload.manifest.securityPatchLevel.ifEmpty { "—" },
                                partitionCount = result.payload.manifest.partitionsCount,
                                blockSize = result.payload.blockSize,
                            )
                        )
                    }
                    is ParseResult.RawEntries -> {
                        payload = null
                        // Keyed by display name so entries that share a basename across
                        // different nested archives stay distinct. The Honor package really
                        // does collide: `preload.img` is 2,135,482,852 bytes in
                        // update_sd_preload_… and 28,784 bytes in update_sd_base.zip, and
                        // `version.img` is 258,160 vs 28,784. Keying on the bare basename
                        // made one silently overwrite the other, so the row the user tapped
                        // could extract a different file than the one it listed.
                        result.entries.forEach { e -> rawEntries[displayKey(e)] = e }
                        _partitions.value = result.entries.map { e ->
                            PartitionInfo(
                                partitionName = displayKey(e),
                                size = e.size,
                                compressedSize = e.compressedSize,
                                sha256 = "",
                                title = e.name,
                                subtitle = e.displayName,
                            )
                        }
                        _parseState.value = ParseState.Ready(result.archiveInfo)
                    }
                }
            }.onFailure { e ->
                // `runCatching` is `catch (Throwable)`, so this also lands here for
                // OutOfMemoryError and StackOverflowError — both of which carry a null
                // message. Reporting `e.message` alone therefore collapsed every distinct
                // device-side failure into the same generic string. Name the class and walk
                // the cause chain instead, and drop a full report on disk.
                val report = Diagnostics.dump(DumperApplication.appContext, opened, "parse", e)
                val detail = buildString {
                    append(Diagnostics.describe(e))
                    if (report != null) append("\n\n报告已写入：${report.absolutePath}")
                }
                _parseState.value = ParseState.Failed(detail)
                _snackbar.value = if (Diagnostics.isOutOfMemory(e)) {
                    str(R.string.err_out_of_memory)
                } else {
                    Diagnostics.describe(e).lineSequence().first()
                }
            }
        }
    }

    fun extract(partitionName: String) {
        // CrAU path (existing)
        val pl = payload
        if (pl != null) {
            extractCrAU(partitionName, pl)
            return
        }

        // Raw entry path (Honor/Huawei/ZIP)
        val entry = rawEntries[partitionName] ?: return
        if (jobs[partitionName]?.isActive == true) return
        val src = source ?: return
        val outDir = sessionOutputDir ?: resolveOutputDir(src.fileName()).also { sessionOutputDir = it }

        val job = viewModelScope.launch {
            update(partitionName) { it.copy(extractState = ExtractState.RUNNING, verifyState = VerifyState.NONE, message = "") }
            runCatching {
                withContext(Dispatchers.IO) {
                    val outFile = File(outDir, File(entry.name).name)
                    outFile.parentFile?.mkdirs()
                    when (val origin = entry.origin) {
                        is ParseResult.Origin.OuterRange -> {
                            if (origin.method == 0) {
                                copyRawRange(src, origin.offset, origin.uncompressedSize, outFile) { p ->
                                    update(partitionName) { it.copy(progress = p) }
                                }
                            } else {
                                inflateRawRange(src, origin.offset, origin.compressedSize, outFile) { p ->
                                    update(partitionName) { it.copy(progress = p) }
                                }
                            }
                        }

                        is ParseResult.Origin.NestedZip -> {
                            // Stream the nested ZIP once and pull this one entry out of it.
                            // No temp file: the inner deflate stream is inflated inline.
                            // Written into the archive's own subdirectory so a basename that
                            // also exists in another nested archive cannot clobber it.
                            val nestedDir = nestedOutputDir(src, origin.ref)
                            val produced = NestedZipStream.extract(
                                outer = src,
                                ref = origin.ref,
                                targets = listOf(origin.inner),
                                outDir = nestedDir,
                                onProgress = { done, total ->
                                    update(partitionName) {
                                        it.copy(progress = if (total > 0) done.toFloat() / total else 0f)
                                    }
                                },
                            )
                            val written = produced[origin.inner.name]
                                ?: produced.values.firstOrNull()
                                ?: throw IOException("Entry '${origin.inner.name}' not found in nested ZIP")
                            if (written.absolutePath != outFile.absolutePath) {
                                outFile.parentFile?.mkdirs()
                                written.copyTo(outFile, overwrite = true)
                                written.delete()
                            }
                        }
                    }
                    outFile
                }
            }.onSuccess { outFile ->
                update(partitionName) { it.copy(extractState = ExtractState.DONE, progress = 1f, verifyState = VerifyState.SKIPPED) }
                _snackbar.value = "${entry.name} → ${outFile.absolutePath}"
            }.onFailure { e ->
                update(partitionName) { it.copy(extractState = ExtractState.ERROR, message = e.message ?: str(R.string.state_extract_failed)) }
                _snackbar.value = "$partitionName: ${e.message}"
            }
        }
        jobs[partitionName] = job
    }

    private fun extractCrAU(partitionName: String, pl: com.flux.payload.dumper.model.Payload) {
        val src = source ?: return
        if (jobs[partitionName]?.isActive == true) return
        val part = pl.manifest.partitionsList.firstOrNull { it.partitionName == partitionName } ?: return
        val outDir = sessionOutputDir ?: resolveOutputDir(pl.fileName).also { sessionOutputDir = it }
        val resume = resumeStates.getOrPut(partitionName) {
            ResumeState(File(outDir, "$partitionName.img.progress")).also { it.load() }
        }

        val job = viewModelScope.launch {
            update(partitionName) { it.copy(extractState = ExtractState.RUNNING, verifyState = VerifyState.NONE, message = "") }
            runCatching {
                val verify = Preferences.getBoolean(Preferences.KEY_VERIFY_ENABLED, true)
                extractor.extractPartition(pl, src, part, outDir, verify, resume) { p ->
                    update(partitionName) { it.copy(progress = p) }
                }
            }.onSuccess { result ->
                val vState = when (result.verified) {
                    true -> VerifyState.PASSED
                    false -> VerifyState.FAILED
                    null -> VerifyState.SKIPPED
                }
                update(partitionName) { it.copy(extractState = ExtractState.DONE, progress = 1f, verifyState = vState) }
                resumeStates.remove(partitionName)
                if (result.verified == false) _snackbar.value = str(R.string.msg_extract_verify_failed, partitionName)
            }.onFailure { e ->
                // A network read that died on a remote source is almost always an expired/broken OTA
                // link. Keep the partial output + resume state and ask the user for a fresh link.
                if (source is HttpSource && e is IOException) {
                    update(partitionName) {
                        it.copy(extractState = ExtractState.PAUSED, message = str(R.string.msg_link_stalled))
                    }
                    _relink.value = partitionName
                } else {
                    update(partitionName) { it.copy(extractState = ExtractState.ERROR, message = e.message ?: str(R.string.state_extract_failed)) }
                    _snackbar.value = "$partitionName: ${e.message}"
                }
            }
        }
        jobs[partitionName] = job
    }

    /**
     * Supply a fresh URL for a stalled partition. The new link is re-parsed and its manifest hash
     * for this partition is compared against the original — a mismatch means it's a *different*
     * ROM, which we reject to avoid corrupting the half-written image. On a match we swap in the
     * new source and resume, skipping the operations already on disk.
     */
    fun submitNewLink(newUrl: String) {
        val name = _relink.value ?: return
        val url = newUrl.trim()
        if (!(url.startsWith("http://") || url.startsWith("https://"))) {
            _snackbar.value = str(R.string.msg_invalid_http); return
        }
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val newSrc = HttpSource.open(url, client)
                    val newPl = PayloadParser.parse(newSrc)
                    val newPart = newPl.manifest.partitionsList.firstOrNull { it.partitionName == name }
                        ?: throw IOException(str(R.string.err_partition_missing, name))
                    val expected = _partitions.value.firstOrNull { it.partitionName == name }?.sha256.orEmpty()
                    val fresh = if (newPart.hasNewPartitionInfo()) newPart.newPartitionInfo.hash.toHex() else ""
                    if (expected.isNotEmpty() && fresh.isNotEmpty() && expected != fresh) {
                        newSrc.close()
                        throw IOException(str(R.string.err_different_rom, name))
                    }
                    runCatching { source?.close() }
                    source = newSrc
                    payload = newPl
                }
            }.onSuccess {
                _relink.value = null
                _snackbar.value = str(R.string.msg_resume_same_rom, name)
                extract(name) // resumes: completed ops are skipped via the retained ResumeState
            }.onFailure { e ->
                _snackbar.value = e.message ?: str(R.string.msg_relink_failed)
            }
        }
    }

    /** Give up on the stalled partition instead of relinking. */
    fun cancelRelink() {
        val name = _relink.value ?: return
        _relink.value = null
        update(name) { it.copy(extractState = ExtractState.ERROR, message = str(R.string.msg_cancelled_progress_kept)) }
    }

    fun extractAll() {
        val current = _partitions.value
        viewModelScope.launch {
            // Batched fast path: entries sharing a nested ZIP are pulled out in ONE stream
            // pass. Honour packages nest ~45 images inside a single 9 GB ZIP, so doing this
            // per-entry would re-stream 9 GB forty-five times (~45 min); batching makes it one.
            val batched = batchedNestedNames(current)
            if (batched.isNotEmpty()) {
                for ((ref, names) in batched) {
                    if (names.all { name ->
                            current.firstOrNull { it.partitionName == name }?.extractState == ExtractState.DONE
                        }
                    ) continue
                    extractNestedBatch(ref, names)
                    // A dead link aborts the whole batch; every remaining entry would fail too.
                    if (_relink.value != null) return@launch
                }
            }

            for (p in current) {
                if (_extractedInBatch.contains(p.partitionName)) continue
                if (p.extractState == ExtractState.DONE) continue
                extract(p.partitionName)
                jobs[p.partitionName]?.join()
                if (_relink.value != null) return@launch
            }
            _snackbar.value = str(R.string.msg_all_done)
        }
    }

    /**
     * Group partitions that live inside the same nested ZIP, so they can share one stream pass.
     * Only groups with more than one member are worth batching.
     */
    private fun batchedNestedNames(
        current: List<PartitionInfo>,
    ): Map<NestedZipStream.Ref, List<String>> {
        val byRef = LinkedHashMap<NestedZipStream.Ref, MutableList<String>>()
        for (p in current) {
            val origin = rawEntries[p.partitionName]?.origin
            if (origin is ParseResult.Origin.NestedZip) {
                byRef.getOrPut(origin.ref) { mutableListOf() }.add(p.partitionName)
            }
        }
        return byRef.filterValues { it.size > 1 }
    }

    /**
     * Stream a nested ZIP once and extract every named entry in it, updating each row's
     * progress from the shared pass.
     */
    private suspend fun extractNestedBatch(ref: NestedZipStream.Ref, names: List<String>) =
        withContext(Dispatchers.IO) {
            val src = source ?: return@withContext
            // One subdirectory per nested archive. The three archives a Honor package carries
            // reuse basenames (`preload.img`, `version.img`, `super_metadata.img`, …), so
            // sharing a directory would let a later archive overwrite an earlier one's file.
            val outDir = nestedOutputDir(src, ref)

            val pending = names.mapNotNull { rawEntries[it] }.mapNotNull { e ->
                val o = e.origin
                if (o is ParseResult.Origin.NestedZip) o.inner else null
            }
            if (pending.isEmpty()) return@withContext

            names.forEach { n ->
                _extractedInBatch.add(n)
                update(n) { it.copy(extractState = ExtractState.RUNNING, verifyState = VerifyState.NONE, message = "") }
            }

            runCatching {
                NestedZipStream.extract(
                    outer = src,
                    ref = ref,
                    targets = pending,
                    outDir = outDir,
                    onProgress = { done, total ->
                        val frac = if (total > 0) done.toFloat() / total else 0f
                        names.forEach { n -> update(n) { it.copy(progress = frac) } }
                    },
                )
            }.onSuccess { produced ->
                // Match on each row's own inner name. The previous check compared the row's
                // display key against inner names, which can never match once nested entries
                // are qualified by the archive that holds them — every row would be reported
                // as failed even though the files were written.
                var missing = 0
                names.forEach { n ->
                    val inner = (rawEntries[n]?.origin as? ParseResult.Origin.NestedZip)?.inner?.name
                    val ok = inner != null &&
                        (produced.containsKey(inner) ||
                            produced.values.any { it.name == inner.substringAfterLast('/') })
                    if (ok) {
                        update(n) { it.copy(extractState = ExtractState.DONE, progress = 1f, verifyState = VerifyState.SKIPPED) }
                    } else {
                        missing++
                        update(n) {
                            it.copy(
                                extractState = ExtractState.ERROR,
                                message = str(R.string.state_extract_failed),
                            )
                        }
                    }
                }
                _snackbar.value = if (missing == 0) {
                    str(R.string.msg_nested_extracted, produced.size)
                } else {
                    str(R.string.msg_nested_partial, produced.size, missing)
                }
            }.onFailure { e ->
                val report = Diagnostics.dump(DumperApplication.appContext, src, "nested-extract", e)
                names.forEach { n ->
                    update(n) {
                        it.copy(
                            extractState = ExtractState.ERROR,
                            message = Diagnostics.describe(e).lineSequence().first() +
                                (report?.let { "\n报告：${it.absolutePath}" } ?: ""),
                        )
                    }
                }
                _snackbar.value = Diagnostics.describe(e).lineSequence().first()
            }
        }

    /** `Download/PayloadDumper/<rom>/<nested-archive>/` so colliding basenames stay apart. */
    private fun nestedOutputDir(src: PayloadSource, ref: NestedZipStream.Ref): File {
        val base = sessionOutputDir ?: resolveOutputDir(src.fileName()).also { sessionOutputDir = it }
        val leaf = ref.outerName.substringAfterLast('/').removeSuffix(".zip").ifBlank { "nested" }
        return File(base, leaf)
    }

    private fun resolveOutputDir(fileName: String): File {
        val romName = fileName.removeSuffix(".zip").removeSuffix(".bin")
        val custom = Preferences.getString(Preferences.KEY_OUTPUT_FOLDER)
        return if (!custom.isNullOrBlank()) File(custom, romName)
        else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PayloadDumper/$romName")
    }

    private inline fun update(name: String, crossinline transform: (PartitionInfo) -> PartitionInfo) {
        _partitions.value = _partitions.value.map { if (it.partitionName == name) transform(it) else it }
    }

    private fun PartitionUpdate.toInfo(): PartitionInfo {
        val ops = operationsList
        val compressed = if (ops.isEmpty()) 0L
        else (ops.last().dataOffset + ops.last().dataLength) - ops.first().dataOffset
        return PartitionInfo(
            partitionName = partitionName,
            size = if (hasNewPartitionInfo()) newPartitionInfo.size else 0L,
            compressedSize = compressed,
            sha256 = if (hasNewPartitionInfo()) newPartitionInfo.hash.toHex() else "",
        )
    }

    private fun srcFileName(result: ParseResult): String = when (result) {
        is ParseResult.CrAU -> result.payload.fileName
        is ParseResult.RawEntries -> result.archiveInfo.fileName
    }

    /**
     * Label used both in the list and as the [rawEntries] key.
     *
     * [ParseResult.RawEntry.displayName] already qualifies nested entries with the archive
     * that holds them ("update_sd_base.zip :: image/system.img"), because basenames are not
     * unique across the three nested ZIPs a Honor package carries — `preload.img` alone
     * appears as both 2,135,482,852 and 28,784 bytes. Keying on the bare basename made the
     * last one parsed overwrite the others, so tapping a row could extract a different file
     * than the row described.
     */
    private fun displayKey(e: ParseResult.RawEntry): String =
        e.displayName.ifBlank { e.name }

    private fun closeSource() {
        runCatching { source?.close() }
        source = null
        // Clean up nested ZIP temp files
        UniversalParser.cleanupTempFiles()
    }

    override fun onCleared() {
        super.onCleared()
        closeSource()
    }

    /** Copy [size] bytes from [source] starting at [offset] to [outFile], with progress. */
    private suspend fun copyRawRange(
        source: PayloadSource,
        offset: Long,
        size: Long,
        outFile: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        val raf = RandomAccessFile(outFile, "rw")
        try {
            raf.setLength(size)
            val channel = raf.channel
            val bufSize = 65536
            val buffer = ByteArray(bufSize)
            var pos = 0L
            while (pos < size) {
                val toRead = minOf(bufSize.toLong(), size - pos).toInt()
                source.readFully(offset + pos, buffer, 0, toRead)
                channel.write(java.nio.ByteBuffer.wrap(buffer, 0, toRead), pos)
                pos += toRead
                onProgress(pos.toFloat() / size)
            }
        } finally {
            raf.close()
        }
    }

    /** Inflate compressed data from [source] at [offset] to [outFile], streaming. */
    private suspend fun inflateRawRange(
        source: PayloadSource,
        offset: Long,
        compressedSize: Long,
        outFile: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        // ZIP entries hold RAW DEFLATE streams (no zlib header/checksum), so `nowrap` must be
        // set — a default Inflater() would reject the very first block as a bad header.
        val inflater = java.util.zip.Inflater(true)
        try {
            val inputBuf = ByteArray(256 * 1024)
            val outputBuf = ByteArray(512 * 1024)
            var srcPos = offset
            var consumed = 0L

            java.io.FileOutputStream(outFile).use { fos ->
                while (consumed < compressedSize) {
                    val toRead = minOf(inputBuf.size.toLong(), compressedSize - consumed).toInt()
                    source.readFully(srcPos, inputBuf, 0, toRead)
                    inflater.setInput(inputBuf, 0, toRead)
                    srcPos += toRead
                    consumed += toRead

                    while (true) {
                        val n = inflater.inflate(outputBuf)
                        if (n > 0) {
                            fos.write(outputBuf, 0, n)
                            if (compressedSize > 0) onProgress(consumed.toFloat() / compressedSize)
                            continue
                        }
                        break
                    }
                }
                // Flush anything the inflater still holds.
                while (true) {
                    val n = inflater.inflate(outputBuf)
                    if (n > 0) fos.write(outputBuf, 0, n) else break
                }
            }
            onProgress(1f)
        } finally {
            inflater.end()
        }
    }
}
