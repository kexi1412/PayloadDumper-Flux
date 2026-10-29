package com.flux.payload.dumper.viewmodel

import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import chromeos_update_engine.UpdateMetadata.PartitionUpdate
import com.flux.payload.dumper.DumperApplication
import com.flux.payload.dumper.R
import com.flux.payload.dumper.core.Diagnostics
import com.flux.payload.dumper.core.FirmwareMetadataReader
import com.flux.payload.dumper.core.HttpSource
import com.flux.payload.dumper.core.NestedZipStream
import com.flux.payload.dumper.core.Net
import com.flux.payload.dumper.core.ParseResult
import com.flux.payload.dumper.core.PayloadExtractor
import com.flux.payload.dumper.core.PayloadParser
import com.flux.payload.dumper.core.PayloadSource
import com.flux.payload.dumper.core.OutputPaths
import com.flux.payload.dumper.core.payloadResumeIdentity
import com.flux.payload.dumper.core.ResumeState
import com.flux.payload.dumper.core.UniversalParser
import com.flux.payload.dumper.core.UriSource
import com.flux.payload.dumper.core.toHex
import com.flux.payload.dumper.data.Preferences
import com.flux.payload.dumper.model.ArchiveInfo
import com.flux.payload.dumper.model.LocalDocument
import com.flux.payload.dumper.model.SourceMode
import com.flux.payload.dumper.model.SourceSelection
import com.flux.payload.dumper.model.ExtractState
import com.flux.payload.dumper.model.Payload
import com.flux.payload.dumper.model.PartitionInfo
import com.flux.payload.dumper.model.VerifyState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap

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
    private val _relinkBusy = MutableStateFlow(false)
    val relinkBusy: StateFlow<Boolean> = _relinkBusy.asStateFlow()
    private var relinkJob: Job? = null
    private var relinkEpoch = 0L
    private var archiveGeneration = 0L
    private val relinkLock = combine(_relink, _relinkBusy) { name, busy -> name != null || busy }

    private val _selection = MutableStateFlow(loadSelection())
    val selection: StateFlow<SourceSelection> = _selection.asStateFlow()
    private val _resolvingDocument = MutableStateFlow(false)
    private val _batchRunning = MutableStateFlow(false)
    val batchRunning: StateFlow<Boolean> = _batchRunning.asStateFlow()
    private var parseJob: Job? = null
    private var batchJob: Job? = null
    // Optional network enrichment must not lock parsing or extraction. This child source owns
    // only its own HTTP calls, so cancelling it cannot interrupt the extraction source.
    private var metadataJob: Job? = null
    private var metadataSource: HttpSource? = null

    val inputLocked: StateFlow<Boolean> by lazy {
        combine(_parseState, _partitions, _resolvingDocument, _batchRunning, relinkLock) { state, rows, resolving, batch, relink ->
            state is ParseState.Parsing || resolving || batch || relink ||
                rows.any { it.extractState == ExtractState.RUNNING }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    }

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

    private fun loadSelection(): SourceSelection {
        val legacy = SourceSelection.fromLegacy(Preferences.getString(Preferences.KEY_PATH_OR_URL).orEmpty())
        val uri = Preferences.getString(Preferences.KEY_LOCAL_URI) ?: legacy.local?.uri
        return SourceSelection(
            mode = runCatching {
                SourceMode.valueOf(Preferences.getString(Preferences.KEY_SOURCE_MODE) ?: legacy.mode.name)
            }.getOrDefault(SourceMode.LOCAL),
            local = uri?.takeIf { it.startsWith("content://") }?.let { LocalDocument(it) },
            networkUrl = Preferences.getString(Preferences.KEY_NETWORK_URL) ?: legacy.networkUrl,
        )
    }

    private fun isWorking(): Boolean = _parseState.value is ParseState.Parsing ||
        _resolvingDocument.value || _batchRunning.value || _relink.value != null || _relinkBusy.value ||
        jobs.values.any { it.isActive }

    private fun resetArchive() {
        archiveGeneration++
        cancelKernelMetadata()
        closeSource()
        payload = null
        parseResult = null
        rawEntries.clear()
        resumeStates.clear()
        _extractedInBatch.clear()
        sessionOutputDir = null
        _partitions.value = emptyList()
        _search.value = ""
        _parseState.value = ParseState.Idle
    }

    fun changeSourceMode(mode: SourceMode) {
        if (isWorking() || mode == _selection.value.mode) return
        resetArchive()
        _selection.update { it.copy(mode = mode) }
        Preferences.setString(Preferences.KEY_SOURCE_MODE, mode.name)
        if (mode == SourceMode.LOCAL) refreshDocumentDetails()
    }

    fun updateNetworkUrl(value: String) {
        if (isWorking() || value == _selection.value.networkUrl) return
        resetArchive()
        _selection.update { it.copy(networkUrl = value) }
        Preferences.setString(Preferences.KEY_NETWORK_URL, value)
    }

    /** Read the provider's DISPLAY_NAME/SIZE off the UI thread. A cancelled picker never calls this. */
    fun selectDocument(uri: Uri) {
        if (isWorking() || uri.scheme != "content") return
        _resolvingDocument.value = true
        viewModelScope.launch {
            try {
                val document = withContext(Dispatchers.IO) {
                    var name = ""
                    var size: Long? = null
                    runCatching {
                        DumperApplication.appContext.contentResolver.query(
                            uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null,
                        )?.use { cursor ->
                            if (cursor.moveToFirst()) {
                                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex).orEmpty()
                                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
                            }
                        }
                    }
                    LocalDocument(uri.toString(), name, size)
                }
                resetArchive()
                _selection.update { it.copy(mode = SourceMode.LOCAL, local = document) }
                Preferences.setString(Preferences.KEY_LOCAL_URI, document.uri)
                Preferences.setString(Preferences.KEY_SOURCE_MODE, SourceMode.LOCAL.name)
            } finally {
                _resolvingDocument.value = false
            }
        }
    }

    fun refreshDocumentDetails() {
        val document = _selection.value.local ?: return
        if (document.displayName.isBlank() && _selection.value.mode == SourceMode.LOCAL) {
            selectDocument(Uri.parse(document.uri))
        }
    }

    fun updateSearch(value: String) { _search.value = value }

    fun consumeSnackbar() { _snackbar.value = null }

    /** Resolve a localized string against the app context, so status/error messages follow the UI language. */
    private fun str(@StringRes resId: Int, vararg args: Any): String =
        DumperApplication.appContext.getString(resId, *args)

    private var parseResult: ParseResult? = null

    /**
     * Entries for non-CrAU formats, keyed by [displayKey].
     *
     * Concurrent because the parser now publishes results from its own IO thread while the
     * scanner is still running, and the user can tap a row on the main thread in between.
     * A plain HashMap read during that write could observe a half-resized table.
     */
    private val rawEntries = ConcurrentHashMap<String, ParseResult.RawEntry>()

    /**
     * Publish entries discovered so far, from the parser's background thread.
     *
     * Called once per nested archive as each finishes scanning, plus once more with the final
     * list. Both [rawEntries] and [_partitions] are safe to touch from a non-main thread:
     * the former is concurrent, the latter is a StateFlow whose `value` is volatile, and
     * Compose collects it on the main thread.
     *
     * Rows already published are never removed — the parser only ever appends.
     */
    private fun publishRawEntries(entries: List<ParseResult.RawEntry>) {
        entries.forEach { e -> rawEntries[displayKey(e)] = e }
        _partitions.value = entries.map { e ->
            PartitionInfo(
                partitionName = displayKey(e),
                size = e.size,
                compressedSize = e.compressedSize,
                sha256 = "",
                title = e.name,
                subtitle = e.displayName,
            )
        }
    }

    /** Names already produced by a batched nested-ZIP pass, so extractAll() skips them. */
    private val _extractedInBatch = mutableSetOf<String>()

    fun parse() {
        if (isWorking()) return
        val selected = _selection.value
        val raw = selected.activeSource
        if (!selected.canParse) {
            _snackbar.value = str(if (selected.mode == SourceMode.LOCAL) R.string.msg_input_required else R.string.msg_invalid_http)
            return
        }
        val generation = ++archiveGeneration
        cancelKernelMetadata()
        _parseState.value = ParseState.Parsing(str(R.string.state_opening))
        // Drop the previous archive's rows up front. This used to happen in onSuccess, but rows
        // are now published incrementally as each nested archive is scanned, so clearing there
        // would discard entries the user is already extracting. Resetting here instead means the
        // list starts empty for the new file and can only ever grow.
        rawEntries.clear()
        _partitions.value = emptyList()
        payload = null
        parseResult = null
        // Held outside the runCatching so the failure path can still probe the archive that was
        // actually opened. Assigning only on success (as before) meant a parse failure had
        // nothing to report on — exactly the case the report exists for.
        var opened: PayloadSource? = null
        parseJob = viewModelScope.launch {
            runCatching {
                runInterruptible(Dispatchers.IO) {
                    closeSource()
                    val src: PayloadSource = if (selected.mode == SourceMode.NETWORK) {
                        HttpSource.open(raw, client)
                    } else {
                        UriSource.open(DumperApplication.appContext, Uri.parse(raw))
                    }
                    opened = src
                    _parseState.value = ParseState.Parsing(str(R.string.state_reading_archive, src.fileName()))
                    val result = UniversalParser.parse(
                        source = src,
                        includeKernel = src !is HttpSource,
                        onProgress = { msg -> _parseState.value = ParseState.Parsing(msg) },
                        // Publish rows the moment an archive has been scanned. The Honor package
                        // is three nested archives and scanning them is inherently sequential
                        // (each is a multi-GB DEFLATE stream), so waiting for the last one before
                        // showing anything means tens of seconds of an apparently empty screen.
                        // Emitting per archive lets the user inspect the first archive's images
                        // while the rest is being scanned; extraction waits for a committed source.
                        onPartial = { partial -> publishRawEntries(partial) },
                    )
                    result
                }
            }.onSuccess { result ->
                if (generation != archiveGeneration) {
                    runCatching { opened?.close() }
                    return@onSuccess
                }
                source = opened
                parseResult = result
                if (selected.mode == SourceMode.LOCAL) {
                    _selection.update { it.copy(local = it.local?.copy(displayName = result.archiveInfo.fileName, size = result.archiveInfo.fileSize)) }
                }
                sessionOutputDir = resolveOutputDir(srcFileName(result))
                resumeStates.clear()
                // NB: `rawEntries` is deliberately NOT cleared here. Rows were already published
                // by publishRawEntries as each nested archive finished, and the user may have
                // started extracting one of them — clearing now would drop the entry the running
                // job looks up by name. The map is repopulated in full just below for the
                // non-partial paths, and it is emptied at the start of the next parse instead.
                _extractedInBatch.clear()

                when (result) {
                    is ParseResult.CrAU -> {
                        payload = result.payload
                        _partitions.value = result.payload.manifest.partitionsList.map { it.toInfo() }
                        _parseState.value = ParseState.Ready(result.archiveInfo)
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
                        //
                        // This is the same call the incremental path makes, so the final state
                        // is identical whether the rows arrived per archive or all at once.
                        publishRawEntries(result.entries)
                        _parseState.value = ParseState.Ready(result.archiveInfo)
                    }
                }
                (source as? HttpSource)?.let { startKernelMetadata(it, result, generation) }
            }.onFailure { e ->
                if (e is CancellationException) {
                    runCatching { opened?.close() }
                    if (generation == archiveGeneration) {
                        source = null
                        rawEntries.clear()
                        _partitions.value = emptyList()
                        _parseState.value = ParseState.Idle
                    }
                    throw e
                }
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
                runCatching { opened?.close() }
                source = null
                rawEntries.clear()
                _partitions.value = emptyList()
                _parseState.value = ParseState.Failed(detail)
                _snackbar.value = if (Diagnostics.isOutOfMemory(e)) {
                    str(R.string.err_out_of_memory)
                } else {
                    Diagnostics.describe(e).lineSequence().first()
                }
            }
        }
    }

    /** Optional HTTP-only enrichment; local FileChannels must not share interruptible readers. */
    private fun startKernelMetadata(
        capturedSource: HttpSource,
        parsed: ParseResult,
        generation: Long,
    ) {
        cancelKernelMetadata()
        if (generation != archiveGeneration || source !== capturedSource || _parseState.value !is ParseState.Ready) return
        val knownKernel = parsed.archiveInfo.kernelVersion
        if (knownKernel.isNotBlank() && knownKernel != "—") return
        val child = try {
            capturedSource.forMetadata()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return // Enrichment cannot make an already parsed archive unusable.
        }
        metadataSource = child
        metadataJob = viewModelScope.launch {
            val thisJob = currentCoroutineContext()[Job]
            try {
                val enriched = runInterruptible(Dispatchers.IO) {
                    FirmwareMetadataReader.readKernel(
                        child,
                        parsed.archiveInfo,
                        (parsed as? ParseResult.CrAU)?.payload,
                    )
                }
                currentCoroutineContext().ensureActive()
                // Relinking does not change archiveGeneration. The parent and child identities
                // also fence late completions after a mirror switch or metadata cancellation.
                if (generation != archiveGeneration || source !== capturedSource || metadataSource !== child) return@launch
                val ready = _parseState.value as? ParseState.Ready ?: return@launch
                fun fillMissing(current: String, candidate: String): String =
                    if (current.isBlank() || current == "—") candidate.ifBlank { current } else current
                val current = ready.archive
                val merged = current.copy(
                    androidVersion = fillMissing(current.androidVersion, enriched.androidVersion),
                    securityPatchLevel = fillMissing(current.securityPatchLevel, enriched.securityPatchLevel),
                    kernelVersion = fillMissing(current.kernelVersion, enriched.kernelVersion),
                    fingerprint = fillMissing(current.fingerprint, enriched.fingerprint),
                )
                parseResult = when (val latest = parseResult) {
                    is ParseResult.CrAU -> latest.copy(archiveInfo = merged)
                    is ParseResult.RawEntries -> latest.copy(archiveInfo = merged)
                    null -> null
                }
                // Do not republish rows: extraction may already be running with live progress.
                _parseState.value = ready.copy(archive = merged)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Kernel metadata is best-effort. Keep Ready, rows and extraction untouched.
            } finally {
                runCatching { child.close() }
                if (metadataSource === child) metadataSource = null
                if (metadataJob === thisJob) metadataJob = null
            }
        }
    }

    /** Called on Main before a reset/parse/relink closes or replaces the primary source. */
    private fun cancelKernelMetadata() {
        val job = metadataJob
        val child = metadataSource
        // Invalidate the completion token before either cancellation path can resume the job.
        metadataJob = null
        metadataSource = null
        job?.cancel()
        runCatching { child?.close() }
    }

    fun extract(partitionName: String) {
        if (_batchRunning.value || _relinkBusy.value) return
        extractInternal(partitionName)
    }

    private fun extractInternal(partitionName: String) {
        // Partial rows are informational until the archive/source is fully committed.
        if (_parseState.value !is ParseState.Ready || _relink.value != null) return
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
                    val entryDir = (entry.origin as? ParseResult.Origin.NestedZip)?.let { nestedOutputDir(src, it.ref) } ?: outDir
                    val outFile = OutputPaths.child(entryDir, entry.name)
                    outFile.parentFile?.mkdirs()
                    when (val origin = entry.origin) {
                        is ParseResult.Origin.OuterRange -> {
                            if (origin.method == 0) {
                                copyRawRange(src, origin.offset, origin.uncompressedSize, outFile) { p ->
                                    update(partitionName) { it.copy(progress = p) }
                                }
                            } else {
                                inflateRawRange(src, origin.offset, origin.compressedSize, origin.uncompressedSize, outFile) { p ->
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
                                if (written.canonicalFile.parentFile == nestedDir.canonicalFile) written.delete()
                            }
                        }
                    }
                    outFile
                }
            }.onSuccess { outFile ->
                update(partitionName) { it.copy(extractState = ExtractState.DONE, progress = 1f, verifyState = VerifyState.SKIPPED) }
                _snackbar.value = "${entry.name} → ${outFile.absolutePath}"
            }.onFailure { e ->
                if (e is CancellationException) {
                    update(partitionName) { it.copy(extractState = ExtractState.PAUSED, message = str(R.string.msg_cancelled_progress_kept)) }
                    throw e
                }
                update(partitionName) { it.copy(extractState = ExtractState.ERROR, message = e.message ?: str(R.string.state_extract_failed)) }
                _snackbar.value = "$partitionName: ${e.message}"
            }
        }
        jobs[partitionName] = job
    }

    private fun extractCrAU(partitionName: String, pl: com.flux.payload.dumper.model.Payload) {
        val src = source ?: return
        if (!Regex("[A-Za-z0-9_-]+").matches(partitionName) || jobs[partitionName]?.isActive == true) return
        val part = pl.manifest.partitionsList.firstOrNull { it.partitionName == partitionName } ?: return
        val outDir = sessionOutputDir ?: resolveOutputDir(pl.fileName).also { sessionOutputDir = it }
        val resume = resumeStates.getOrPut(partitionName) {
            ResumeState(OutputPaths.child(outDir, "$partitionName.img.progress"), payloadResumeIdentity(pl, partitionName), part.operationsCount)
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
                if (e is CancellationException) {
                    update(partitionName) { it.copy(extractState = ExtractState.PAUSED, message = str(R.string.msg_cancelled_progress_kept)) }
                    throw e
                }
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

    /** A relink may resume operation indices only when the WHOLE manifest/layout matches. */
    fun submitNewLink(newUrl: String) {
        val name = _relink.value ?: return
        if (_relinkBusy.value) return
        val original = payload ?: return
        val url = newUrl.trim()
        if (!SourceSelection.isHttpUrl(url)) {
            _snackbar.value = str(R.string.msg_invalid_http)
            return
        }
        val generation = archiveGeneration
        val epoch = ++relinkEpoch
        cancelKernelMetadata()
        val expected = original.manifest.toByteArray()
        _relinkBusy.value = true
        relinkJob = viewModelScope.launch {
            val thisJob = currentCoroutineContext()[Job]
            var candidate: HttpSource? = null
            var committed = false
            try {
                // No reader may keep using the old descriptor while a replacement is committed.
                jobs.values.toList().forEach { if (it.isActive) it.cancelAndJoin() }
                batchJob?.join()
                val freshPayload = runInterruptible(Dispatchers.IO) {
                    val freshSource = HttpSource.open(url, client)
                    candidate = freshSource
                    val fresh = PayloadParser.parse(freshSource)
                    if (fresh.manifest.partitionsList.none { it.partitionName == name }) {
                        throw IOException(str(R.string.err_partition_missing, name))
                    }
                    if (fresh.blockSize != original.blockSize || !expected.contentEquals(fresh.manifest.toByteArray())) {
                        throw IOException(str(R.string.err_different_rom, name))
                    }
                    fresh
                }
                currentCoroutineContext().ensureActive()
                if (generation != archiveGeneration || epoch != relinkEpoch || _relink.value != name) return@launch
                val freshSource = candidate ?: throw IOException(str(R.string.msg_relink_failed))
                runCatching { source?.close() }
                source = freshSource
                payload = freshPayload
                committed = true
                _selection.update { it.copy(networkUrl = url) }
                Preferences.setString(Preferences.KEY_NETWORK_URL, url)
                val ready = _parseState.value as? ParseState.Ready
                if (ready != null) {
                    val refreshed = ParseResult.CrAU(
                        freshPayload,
                        ready.archive.copy(fileName = freshSource.fileName(), fileSize = freshSource.size),
                    )
                    parseResult = refreshed
                    _parseState.value = ready.copy(archive = refreshed.archiveInfo)
                    startKernelMetadata(freshSource, refreshed, generation)
                }
                _relink.value = null
                _relinkBusy.value = false
                _snackbar.value = str(R.string.msg_resume_same_rom, name)
                extractInternal(name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (generation == archiveGeneration && epoch == relinkEpoch) _snackbar.value = e.message ?: str(R.string.msg_relink_failed)
            } finally {
                if (!committed) runCatching { candidate?.close() }
                if (relinkJob == thisJob) {
                    _relinkBusy.value = false
                    relinkJob = null
                }
            }
        }
    }

    fun cancelRelink() {
        val name = _relink.value ?: return
        relinkEpoch++
        cancelKernelMetadata()
        relinkJob?.cancel()
        _relink.value = null
        update(name) { it.copy(extractState = ExtractState.ERROR, message = str(R.string.msg_cancelled_progress_kept)) }
    }

    fun extractAll() {
        if (_parseState.value !is ParseState.Ready || isWorking()) return
        val current = _partitions.value
        _batchRunning.value = true
        batchJob = viewModelScope.launch {
            try {
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
                extractInternal(p.partitionName)
                jobs[p.partitionName]?.join()
                if (_relink.value != null) return@launch
            }
            val failures = _partitions.value.count { it.extractState == ExtractState.ERROR || it.verifyState == VerifyState.FAILED }
            _snackbar.value = if (failures == 0) str(R.string.msg_all_done) else str(R.string.msg_extract_partial, failures)
            } finally {
                _batchRunning.value = false
            }
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
                if (e is CancellationException) throw e
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
        return OutputPaths.child(base, OutputPaths.archiveFolder(ref.outerName))
    }

    private fun resolveOutputDir(fileName: String): File {
        val custom = Preferences.getString(Preferences.KEY_OUTPUT_FOLDER)
        val root = if (!custom.isNullOrBlank()) File(custom)
            else File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PayloadDumper")
        return OutputPaths.child(root, OutputPaths.archiveFolder(fileName))
    }

    private inline fun update(name: String, crossinline transform: (PartitionInfo) -> PartitionInfo) {
        _partitions.update { rows -> rows.map { if (it.partitionName == name) transform(it) else it } }
    }

    private fun PartitionUpdate.toInfo(): PartitionInfo {
        val ops = operationsList
        val compressed = ops.sumOf { it.dataLength }
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
        archiveGeneration++
        relinkEpoch++
        cancelKernelMetadata()
        parseJob?.cancel()
        batchJob?.cancel()
        relinkJob?.cancel()
        jobs.values.forEach { it.cancel() }
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
            require(offset >= 0 && size >= 0 && offset <= source.size - size) { "Archive entry outside source" }
            raf.setLength(size)
            val channel = raf.channel
            val bufSize = 65536
            val buffer = ByteArray(bufSize)
            var pos = 0L
            while (pos < size) {
                currentCoroutineContext().ensureActive()
                val toRead = minOf(bufSize.toLong(), size - pos).toInt()
                source.readFully(offset + pos, buffer, 0, toRead)
                val bytes = java.nio.ByteBuffer.wrap(buffer, 0, toRead)
                while (bytes.hasRemaining()) {
                    val written = channel.write(bytes, pos)
                    if (written <= 0) throw IOException("Output write made no progress")
                    pos += written
                }
                onProgress(pos.toFloat() / size)
            }
            channel.force(true)
        } finally {
            raf.close()
        }
    }

    /** Stream raw DEFLATE without accepting truncated or over-expanded entries. */
    private suspend fun inflateRawRange(
        source: PayloadSource,
        offset: Long,
        compressedSize: Long,
        expectedSize: Long,
        outFile: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.IO) {
        require(offset >= 0 && compressedSize >= 0 && expectedSize >= 0 && offset <= source.size - compressedSize)
        val inflater = java.util.zip.Inflater(true)
        try {
            val input = ByteArray(256 * 1024)
            val output = ByteArray(512 * 1024)
            var consumed = 0L
            var produced = 0L
            java.io.FileOutputStream(outFile).use { stream ->
                while (!inflater.finished()) {
                    currentCoroutineContext().ensureActive()
                    if (inflater.needsInput()) {
                        if (consumed == compressedSize) throw IOException("Truncated ZIP DEFLATE stream")
                        val count = minOf(input.size.toLong(), compressedSize - consumed).toInt()
                        source.readFully(offset + consumed, input, 0, count)
                        inflater.setInput(input, 0, count)
                        consumed += count
                    }
                    val count = inflater.inflate(output)
                    if (count > 0) {
                        if (count > expectedSize - produced) throw IOException("ZIP entry exceeds advertised size")
                        stream.write(output, 0, count)
                        produced += count
                        onProgress(if (expectedSize == 0L) 1f else produced.toFloat() / expectedSize)
                    } else if (!inflater.finished() && !inflater.needsInput()) {
                        throw IOException("Invalid ZIP DEFLATE stream")
                    }
                }
                if (produced != expectedSize || consumed - inflater.remaining != compressedSize) {
                    throw IOException("ZIP DEFLATE length mismatch")
                }
                stream.fd.sync()
            }
        } finally {
            inflater.end()
        }
    }
}
