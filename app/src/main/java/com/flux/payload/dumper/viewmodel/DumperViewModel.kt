package com.flux.payload.dumper.viewmodel

import android.net.Uri
import android.os.Environment
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chromeos_update_engine.UpdateMetadata.PartitionUpdate
import com.flux.payload.dumper.DumperApplication
import com.flux.payload.dumper.R
import com.flux.payload.dumper.core.FileSource
import com.flux.payload.dumper.core.HttpSource
import com.flux.payload.dumper.core.Net
import com.flux.payload.dumper.core.ParseResult
import com.flux.payload.dumper.core.PayloadExtractor
import com.flux.payload.dumper.core.PayloadParser
import com.flux.payload.dumper.core.PayloadSource
import com.flux.payload.dumper.core.ResumeState
import com.flux.payload.dumper.core.UniversalParser
import com.flux.payload.dumper.core.UriSource
import com.flux.payload.dumper.core.toHex
import com.flux.payload.dumper.core.ZipScanner
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
    data object Parsing : ParseState
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
            if (q.isBlank()) list else list.filter { it.partitionName.contains(q, ignoreCase = true) }
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

    fun parse() {
        val raw = _input.value.trim()
        if (raw.isEmpty()) { _snackbar.value = str(R.string.msg_input_required); return }
        _parseState.value = ParseState.Parsing
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
                    val result = UniversalParser.parse(src)
                    source = src
                    parseResult = result
                    result
                }
            }.onSuccess { result ->
                sessionOutputDir = resolveOutputDir(srcFileName(result))
                resumeStates.clear()
                rawEntries.clear()

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
                        result.entries.forEach { e -> rawEntries[e.name] = e }
                        _partitions.value = result.entries.map { e ->
                            PartitionInfo(
                                partitionName = e.name,
                                size = e.size,
                                compressedSize = e.compressedSize,
                                sha256 = "",
                            )
                        }
                        _parseState.value = ParseState.Ready(result.archiveInfo)
                    }
                }
            }.onFailure { e ->
                _parseState.value = ParseState.Failed(e.message ?: str(R.string.parse_failed))
                _snackbar.value = e.message ?: str(R.string.parse_failed)
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
        // Use the nested temp file as source if entry came from a nested ZIP
        val src: PayloadSource = if (entry.sourceFile != null) FileSource(entry.sourceFile!!) else (source ?: return)
        val outDir = sessionOutputDir ?: resolveOutputDir(src.fileName()).also { sessionOutputDir = it }

        val job = viewModelScope.launch {
            update(partitionName) { it.copy(extractState = ExtractState.RUNNING, verifyState = VerifyState.NONE, message = "") }
            runCatching {
                withContext(Dispatchers.IO) {
                    val outFile = File(outDir, File(entry.name).name)
                    outFile.parentFile?.mkdirs()

                    if (entry.method == 0) {
                        // STORED — direct copy from source offset
                        copyRawRange(src, entry.offset, entry.size, outFile) { p ->
                            // progress callback
                        }
                    } else {
                        // DEFLATED — need to decompress
                        inflateRawRange(src, entry.offset, entry.compressedSize, outFile) { p ->
                        }
                    }
                    outFile
                }
            }.onSuccess { outFile ->
                update(partitionName) { it.copy(extractState = ExtractState.DONE, progress = 1f, verifyState = VerifyState.SKIPPED) }
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
            for (p in current) {
                if (p.extractState == ExtractState.DONE) continue
                extract(p.partitionName)
                jobs[p.partitionName]?.join()
                // If this partition stalled and is awaiting a new link, stop the batch — every
                // remaining partition would fail on the same dead link.
                if (_relink.value != null) return@launch
            }
            _snackbar.value = str(R.string.msg_all_done)
        }
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
        val inflater = java.util.zip.Inflater()
        try {
            val inputBuf = ByteArray(256 * 1024)
            val outputBuf = ByteArray(512 * 1024)
            var srcPos = offset
            var consumed = 0L
            var totalOut = 0L

            java.io.FileOutputStream(outFile).use { fos ->
                while (consumed < compressedSize) {
                    val toRead = minOf(inputBuf.size.toLong(), compressedSize - consumed).toInt()
                    source.readFully(srcPos, inputBuf, 0, toRead)
                    inflater.setInput(inputBuf, 0, toRead)
                    srcPos += toRead
                    consumed += toRead

                    while (!inflater.needsInput() && !inflater.finished()) {
                        val n = inflater.inflate(outputBuf)
                        if (n > 0) {
                            fos.write(outputBuf, 0, n)
                            totalOut += n
                            if (compressedSize > 0) onProgress(consumed.toFloat() / compressedSize)
                        } else break
                    }
                }
                // Flush remaining
                while (!inflater.finished()) {
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
