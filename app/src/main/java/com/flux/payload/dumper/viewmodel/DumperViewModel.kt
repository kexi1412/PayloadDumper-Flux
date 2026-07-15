package com.flux.payload.dumper.viewmodel

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
import com.flux.payload.dumper.core.PayloadExtractor
import com.flux.payload.dumper.core.PayloadParser
import com.flux.payload.dumper.core.PayloadSource
import com.flux.payload.dumper.core.ResumeState
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

    fun parse() {
        val raw = _input.value.trim()
        if (raw.isEmpty()) { _snackbar.value = str(R.string.msg_input_required); return }
        _parseState.value = ParseState.Parsing
        viewModelScope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    closeSource()
                    val src: PayloadSource = if (raw.startsWith("http://") || raw.startsWith("https://")) {
                        HttpSource.open(raw, client)
                    } else {
                        val f = File(raw)
                        if (!f.exists() || !f.canRead()) throw java.io.IOException(str(R.string.err_file_unreadable, raw))
                        FileSource(f)
                    }
                    val parsed = PayloadParser.parse(src)
                    source = src
                    payload = parsed
                    parsed
                }
            }.onSuccess { parsed ->
                sessionOutputDir = resolveOutputDir(parsed.fileName)
                resumeStates.clear()
                _partitions.value = parsed.manifest.partitionsList.map { it.toInfo() }
                _parseState.value = ParseState.Ready(
                    ArchiveInfo(
                        fileName = parsed.fileName,
                        fileSize = parsed.archiveSize,
                        securityPatchLevel = parsed.manifest.securityPatchLevel.ifEmpty { "—" },
                        partitionCount = parsed.manifest.partitionsCount,
                        blockSize = parsed.blockSize,
                    )
                )
            }.onFailure { e ->
                _parseState.value = ParseState.Failed(e.message ?: str(R.string.parse_failed))
                _snackbar.value = e.message ?: str(R.string.parse_failed)
            }
        }
    }

    fun extract(partitionName: String) {
        val pl = payload ?: return
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

    private fun closeSource() {
        runCatching { source?.close() }
        source = null
    }

    override fun onCleared() {
        super.onCleared()
        closeSource()
    }
}
