package com.flux.payload.desktop

import com.flux.payload.dumper.core.*
import com.flux.payload.dumper.model.ArchiveInfo
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.system.exitProcess

private data class Row(
    val partitionName: String,
    val displayTitle: String,
    val subtitle: String = "",
    val size: Long,
    val compressedSize: Long,
    val sha256: String = "",
    val extractState: String = "IDLE",
    val verifyState: String = "NONE",
    val progress: Float = 0f,
    val message: String = "",
)
private data class EngineState(
    val phase: String = "idle",
    val detail: String = "",
    val archive: ArchiveInfo? = null,
    val partitions: List<Row> = emptyList(),
    val outputDir: String? = null,
    val sourceLabel: String? = null,
)
private data class Options(val workers: Int, val verify: Boolean, val userAgent: String)
private data class SourceSpec(val kind: String, val value: String)

private class Protocol {
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()
    private val output = PrintWriter(OutputStreamWriter(System.out, Charsets.UTF_8), true)
    @Synchronized fun write(value: Any) { output.println(gson.toJson(value)) }
    fun state(value: EngineState) = write(mapOf("event" to "state", "data" to value))
    fun log(message: String, level: String = "info") = write(mapOf(
        "event" to "log", "data" to mapOf("time" to System.currentTimeMillis(), "level" to level, "message" to message),
    ))
}

/** One active operation; immutable state snapshots are serialized in mutation order. */
private class Controller(private val protocol: Protocol) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val clients = ConcurrentHashMap.newKeySet<OkHttpClient>()
    @Volatile private var current = EngineState()
    @Volatile private var generation = 0L
    @Volatile private var work: Job? = null
    @Volatile private var metadataJob: Job? = null
    @Volatile private var source: PayloadSource? = null
    @Volatile private var metadataSource: HttpSource? = null
    @Volatile private var activeClient: OkHttpClient? = null
    @Volatile private var parsed: ParseResult? = null
    private var specification: SourceSpec? = null
    private var sourceIdentity = ""
    private var archiveIdentity = ""
    private var lastPublish = 0L
    private var outputRoot: File? = null
    private var parsedOptions = Options(4, true, "")

    fun state(): EngineState = current
    private fun update(force: Boolean = true, transform: (EngineState) -> EngineState) = synchronized(stateLock) {
        current = transform(current)
        val now = System.nanoTime()
        if (force || now - lastPublish >= 100_000_000L) {
            lastPublish = now
            protocol.state(current)
        }
    }
    private fun row(name: String, force: Boolean = true, transform: (Row) -> Row) = update(force) { value ->
        value.copy(partitions = value.partitions.map { if (it.partitionName == name) transform(it) else it })
    }
    private fun idleOnly() {
        check(current.phase !in setOf("parsing", "extracting", "cancelling")) { "请先等待当前任务结束或暂停任务" }
    }
    private fun options(params: JsonObject): Options {
        val value = params.getAsJsonObject("options") ?: throw IOException("缺少提取设置")
        val workers = value.get("workers")?.asInt ?: 4
        require(workers in 1..16) { "并行线程必须在 1–16 之间" }
        val verify = value.get("verify")?.asBoolean ?: true
        val agent = value.get("userAgent")?.asString.orEmpty().trim()
        require(agent.length <= 512 && agent.none { it == '\r' || it == '\n' || it == '\u0000' }) { "User-Agent 无效" }
        return Options(workers, verify, agent)
    }
    private fun root(params: JsonObject): File {
        val value = params.get("outputDir")?.asString ?: throw IOException("缺少输出目录")
        require(value.isNotBlank() && !value.contains('\u0000')) { "输出目录无效" }
        val file = File(value)
        require(file.isAbsolute) { "输出目录必须是绝对路径" }
        require(!file.exists() || file.isDirectory) { "输出目录不是文件夹" }
        return file.canonicalFile
    }
    private fun sourceSpec(params: JsonObject): SourceSpec {
        val value = params.getAsJsonObject("source") ?: throw IOException("请选择固件来源")
        val kind = value.get("kind")?.asString.orEmpty()
        val location = value.get("value")?.asString.orEmpty().trim()
        require(location.isNotBlank() && location.length <= 32768 && !location.contains('\u0000')) { "固件来源无效" }
        require(kind == "file" || kind == "url") { "未知来源类型" }
        if (kind == "file") require(File(location).isAbsolute && File(location).isFile) { "固件文件不存在" }
        else {
            val uri = java.net.URI(location)
            require(uri.scheme?.lowercase() in setOf("http", "https") && !uri.host.isNullOrBlank()) { "只支持 HTTP(S) 下载直链" }
        }
        return SourceSpec(kind, location)
    }
    private fun open(spec: SourceSpec, settings: Options): PayloadSource {
        if (spec.kind == "file") return FileSource(File(spec.value))
        val agent = settings.userAgent.ifBlank { "PayloadDumperFlux/3.1.0-pc.2 (Desktop)" }
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS).retryOnConnectionFailure(true)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().header("User-Agent", agent).build()) }
            .build()
        activeClient = client
        clients.add(client)
        return HttpSource.open(spec.value, client)
    }
    private fun closeSource() {
        val old = source
        source = null
        runCatching { old?.close() }
    }
    private fun cancelMetadata() {
        metadataJob?.cancel()
        metadataJob = null
        runCatching { metadataSource?.close() }
        metadataSource = null
    }
    private fun closeClients() {
        clients.forEach { client ->
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
        clients.clear()
        activeClient = null
    }
    private fun rows(result: ParseResult): List<Row> = when (result) {
        is ParseResult.CrAU -> result.payload.manifest.partitionsList.map { part ->
            Row(part.partitionName, part.partitionName, size = if (part.hasNewPartitionInfo()) part.newPartitionInfo.size else 0,
                compressedSize = part.operationsList.sumOf { it.dataLength },
                sha256 = if (part.hasNewPartitionInfo()) part.newPartitionInfo.hash.toByteArray().hex() else "")
        }
        is ParseResult.RawEntries -> rawRows(result.entries)
    }
    private fun rawRows(entries: List<ParseResult.RawEntry>) = entries.map { entry ->
        Row(key(entry), entry.name, entry.displayName.takeUnless { it == entry.name }.orEmpty(), entry.size, entry.compressedSize)
    }
    private fun key(entry: ParseResult.RawEntry) = entry.displayName.ifBlank { entry.name }
    private fun identity(result: ParseResult, input: PayloadSource): String {
        val bytes = when (result) {
            is ParseResult.CrAU -> result.payload.manifest.toByteArray()
            is ParseResult.RawEntries -> (input.identity() + result.entries.joinToString("\u0000") { "${key(it)}:${it.size}:${it.compressedSize}:${it.origin}" }).toByteArray()
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    }
    private fun folder(root: File, result: ParseResult): File {
        val stem = OutputPaths.archiveFolder(result.archiveInfo.fileName).take(80)
        val safe = if (reserved(stem)) "firmware_$stem" else stem
        return OutputPaths.child(root, "${safe}_${archiveIdentity.take(8)}")
    }
    private fun reserved(name: String): Boolean = name.substringBefore('.').uppercase(Locale.ROOT).matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))
    private fun safeTarget(directory: File, name: String, exact: Boolean = false): File {
        val leaf = OutputPaths.leaf(name)
        if (exact && leaf != name) throw IOException("分区名称不能安全映射到 Windows 文件：$name")
        require(!reserved(leaf) && leaf == leaf.trimEnd(' ', '.')) { "Windows 保留文件名：$leaf" }
        return OutputPaths.child(directory, leaf)
    }

    fun parse(params: JsonObject) {
        idleOnly()
        val spec = sourceSpec(params)
        val settings = options(params)
        val selectedRoot = root(params)
        generation++
        val epoch = generation
        cancelMetadata(); closeSource(); closeClients()
        parsed = null
        specification = spec
        parsedOptions = settings
        outputRoot = selectedRoot
        update { EngineState(phase = "parsing", detail = "正在读取固件目录…", sourceLabel = if (spec.kind == "file") File(spec.value).name else "网络固件") }
        work = scope.launch(start = CoroutineStart.LAZY) {
            var success = false
            var metadataInput: HttpSource? = null
            try {
                val input = runInterruptible { open(spec, settings) }
                source = input
                ensureActive()
                val result = runInterruptible {
                    UniversalParser.parse(input, includeKernel = input !is HttpSource,
                        onProgress = { detail ->
                            if (generation == epoch) update(false) { it.copy(detail = detail) }
                            if (Thread.currentThread().isInterrupted) throw CancellationException("解析已取消")
                        },
                        onPartial = { entries -> if (generation == epoch) update { it.copy(partitions = rawRows(entries)) } },
                    )
                }
                ensureActive()
                if (generation != epoch) return@launch
                val newRows = rows(result)
                require(newRows.map { it.partitionName.lowercase(Locale.ROOT) }.distinct().size == newRows.size) { "固件中存在重复或大小写冲突的分区标识" }
                parsed = result
                sourceIdentity = input.identity()
                archiveIdentity = identity(result, input)
                update { it.copy(archive = result.archiveInfo, partitions = newRows, outputDir = folder(selectedRoot, result).path, sourceLabel = result.archiveInfo.fileName) }
                if (input is HttpSource) metadataInput = input
                success = true
            } catch (_: CancellationException) {
                closeSource()
            } catch (error: Exception) {
                closeSource()
                if (generation == epoch) {
                    val message = message(error)
                    update { it.copy(phase = "error", detail = message) }
                    protocol.log(message, "error")
                }
            } finally {
                if (generation == epoch) {
                    work = null
                    if (success && current.phase != "cancelling") {
                        update { it.copy(phase = "ready", detail = "解析完成，共 ${it.partitions.size} 个分区") }
                        protocol.log("解析完成：${current.archive?.fileName}（${current.partitions.size} 个分区）")
                    } else if (current.phase != "error") {
                        parsed = null
                        update { EngineState(detail = "解析已取消") }
                    }
                }
            }
            if (success && generation == epoch && parsed != null) metadataInput?.let { startMetadata(it, epoch) }
        }
        work!!.start()
    }

    private fun startMetadata(input: HttpSource, epoch: Long) {
        if (generation != epoch || source !== input) return
        val result = parsed ?: return
        val child = input.forMetadata()
        metadataSource = child
        metadataJob = scope.launch {
            try {
                val info = runInterruptible { FirmwareMetadataReader.readKernel(child, result.archiveInfo, (result as? ParseResult.CrAU)?.payload) }
                ensureActive()
                if (generation == epoch && source === input) update { it.copy(archive = info) }
            } catch (_: CancellationException) {
                // Optional metadata never turns a successful parse/extraction into a failure.
            } catch (_: Exception) {
                if (generation == epoch) protocol.log("内核信息未能在读取预算内获得，保留未知", "warn")
            } finally {
                child.close()
                if (metadataSource === child) metadataSource = null
            }
        }
    }

    fun extract(params: JsonObject) {
        idleOnly()
        val result = parsed ?: throw IOException("请先解析固件")
        val settings = options(params)
        val selectedRoot = root(params)
        val selected = params.getAsJsonArray("partitions")?.map { it.asString }?.distinct()
            ?: throw IOException("请选择分区")
        require(selected.isNotEmpty() && selected.size <= 10000) { "请选择分区" }
        val known = current.partitions.map { it.partitionName }.toSet()
        require(selected.all { it in known }) { "所选分区不属于当前固件" }
        val epoch = generation
        val destination = folder(selectedRoot, result)
        if (outputRoot != selectedRoot) update { it.copy(partitions = it.partitions.map { p -> p.copy(extractState = "IDLE", verifyState = "NONE", progress = 0f, message = "") }) }
        outputRoot = selectedRoot
        update { it.copy(phase = "extracting", detail = "准备提取 ${selected.size} 个分区…", outputDir = destination.path) }
        work = scope.launch(start = CoroutineStart.LAZY) {
            var cancelled = false
            try {
                if (!destination.isDirectory && !destination.mkdirs()) throw IOException("无法创建输出目录")
                // A changed User-Agent requires a fresh HTTP client and the same identity check.
                if (source is HttpSource && settings.userAgent != parsedOptions.userAgent) {
                    cancelMetadata()
                    closeSource()
                }
                parsedOptions = settings
                // The source may have been closed to promptly cancel a previous blocking read.
                val input = source ?: runInterruptible {
                    val reopened = open(specification ?: throw IOException("来源已失效"), settings)
                    try {
                        val reparsed = UniversalParser.parse(reopened, includeKernel = false)
                        require(identity(reparsed, reopened) == archiveIdentity) { "固件内容或操作布局已改变，请重新解析；不会复用旧进度" }
                        reopened
                    } catch (error: Throwable) { reopened.close(); throw error }
                }.also { source = it }
                ensureActive()
                if (input is FileSource && input.identity() != sourceIdentity) throw IOException("本地固件已被修改，请重新解析")
                when (result) {
                    is ParseResult.CrAU -> {
                        val extractor = PayloadExtractor(settings.workers)
                        val parts = result.payload.manifest.partitionsList.associateBy { it.partitionName }
                        for (name in selected) {
                            ensureActive()
                            val part = parts.getValue(name)
                            safeTarget(destination, "$name.img", exact = true)
                            val resume = ResumeState(safeTarget(destination, "$name.img.progress", exact = true), payloadResumeIdentity(result.payload, name), part.operationsCount)
                            row(name) { it.copy(extractState = "RUNNING", verifyState = "NONE", message = "") }
                            update { it.copy(detail = "正在提取 $name") }
                            try {
                                val extracted = extractor.extractPartition(result.payload, input, part, destination, settings.verify, resume) { progress ->
                                    row(name, false) { it.copy(progress = progress, verifyState = if (settings.verify && progress >= .99f && it.sha256.isNotBlank()) "VERIFYING" else "NONE") }
                                }
                                ensureActive()
                                if (extracted.verified == false) {
                                    row(name) { it.copy(extractState = "ERROR", verifyState = "FAILED", message = "分区 SHA-256 校验失败，请重试") }
                                    protocol.log("$name：SHA-256 校验失败", "error")
                                } else {
                                    row(name) { it.copy(extractState = "DONE", verifyState = if (extracted.verified == true) "PASSED" else "SKIPPED", progress = 1f) }
                                    protocol.log("已提取 $name → ${extracted.outputFile.name}")
                                }
                            } catch (error: CancellationException) { throw error }
                            catch (error: Exception) { currentCoroutineContext().ensureActive(); failure(name, error) }
                        }
                    }
                    is ParseResult.RawEntries -> extractRaw(result, input, selected, destination)
                }
            } catch (_: CancellationException) {
                cancelled = true
                closeSource()
            } catch (error: Exception) {
                val detail = message(error)
                update { it.copy(phase = "error", detail = detail, partitions = it.partitions.map { p -> if (p.partitionName in selected && p.extractState != "DONE") p.copy(extractState = "ERROR", message = detail) else p }) }
                protocol.log(detail, "error")
            } finally {
                if (generation == epoch) {
                    work = null
                    if (cancelled || current.phase == "cancelling") {
                        update { it.copy(phase = "ready", detail = "任务已暂停；payload 操作进度已保存，普通 ZIP 条目重新开始", partitions = it.partitions.map { p ->
                            if (p.partitionName in selected && p.extractState !in setOf("DONE", "ERROR")) p.copy(extractState = "PAUSED", message = "已暂停") else p
                        }) }
                        protocol.log("提取已暂停，payload 进度保留", "warn")
                    } else if (current.phase != "error") {
                        val failures = current.partitions.count { it.partitionName in selected && it.extractState == "ERROR" }
                        update { it.copy(phase = "ready", detail = if (failures == 0) "提取完成，镜像已保存到输出目录" else "提取结束：$failures 个分区失败，可单独重试") }
                    }
                }
            }
        }
        work!!.start()
    }

    private suspend fun extractRaw(result: ParseResult.RawEntries, input: PayloadSource, selected: List<String>, destination: File) {
        val entries = result.entries.associateBy(::key)
        val wanted = selected.map { entries.getValue(it) }
        // Validate ALL resulting paths, not only selected rows, to prevent sequential batches
        // from overwriting colliding basenames on Windows' case-insensitive filesystem.
        val targets = HashMap<String, String>()
        for (entry in result.entries) {
            val origin = entry.origin
            val directory = if (origin is ParseResult.Origin.NestedZip) OutputPaths.child(destination, OutputPaths.archiveFolder(origin.ref.outerName)) else destination
            val leaf = if (origin is ParseResult.Origin.NestedZip) origin.inner.name.substringAfterLast('/') else entry.name
            val target = safeTarget(directory, leaf, exact = origin is ParseResult.Origin.NestedZip)
            val canonical = target.canonicalPath.lowercase(Locale.ROOT)
            val previous = targets.putIfAbsent(canonical, key(entry))
            require(previous == null) { "固件包含输出名称冲突：$previous / ${key(entry)}" }
        }
        val groups = wanted.filter { it.origin is ParseResult.Origin.NestedZip }
            .groupBy { (it.origin as ParseResult.Origin.NestedZip).ref }
        for ((ref, group) in groups) {
            currentCoroutineContext().ensureActive()
            val directory = OutputPaths.child(destination, OutputPaths.archiveFolder(ref.outerName))
            val names = group.map(::key).toSet()
            update { it.copy(partitions = it.partitions.map { p -> if (p.partitionName in names) p.copy(extractState = "RUNNING", message = "") else p }, detail = "正在流式提取 ${ref.outerName}") }
            try {
                val produced = runInterruptible {
                    NestedZipStream.extract(input, ref, group.map { (it.origin as ParseResult.Origin.NestedZip).inner }, directory,
                        onProgress = { done, total ->
                            if (Thread.currentThread().isInterrupted) throw CancellationException("提取已取消")
                            val fraction = if (total > 0) (done.toDouble() / total).toFloat().coerceIn(0f, .99f) else 0f
                            update(false) { it.copy(partitions = it.partitions.map { p -> if (p.partitionName in names) p.copy(progress = fraction) else p }) }
                        },
                    )
                }
                for (entry in group) {
                    currentCoroutineContext().ensureActive()
                    val inner = (entry.origin as ParseResult.Origin.NestedZip).inner
                    val file = produced[inner.name] ?: throw IOException("嵌套条目缺失或未完整写入：${inner.name}")
                    require(file.length() == inner.uncompressedSize) { "嵌套条目输出长度不匹配：${inner.name}" }
                    if (inner.crc32 != null) runInterruptible { verifyCrc(file, inner.crc32) }
                    row(key(entry)) { it.copy(extractState = "DONE", progress = 1f, verifyState = "SKIPPED") }
                    protocol.log("已提取 ${key(entry)}")
                }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); for (entry in group) if (current.partitions.first { it.partitionName == key(entry) }.extractState != "DONE") failure(key(entry), error) }
        }
        for (entry in wanted.filter { it.origin is ParseResult.Origin.OuterRange }) {
            currentCoroutineContext().ensureActive()
            val name = key(entry)
            row(name) { it.copy(extractState = "RUNNING", progress = 0f, verifyState = "NONE", message = "") }
            update { it.copy(detail = "正在提取 ${entry.name}") }
            try {
                val target = safeTarget(destination, entry.name)
                val origin = entry.origin as ParseResult.Origin.OuterRange
                runInterruptible { copyRange(input, origin, target) { fraction -> row(name, false) { it.copy(progress = fraction) } } }
                currentCoroutineContext().ensureActive()
                row(name) { it.copy(extractState = "DONE", progress = 1f, verifyState = "SKIPPED") }
                protocol.log("已提取 ${entry.name}")
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) { currentCoroutineContext().ensureActive(); failure(name, error) }
        }
    }
    private fun failure(name: String, error: Exception) {
        val detail = message(error)
        row(name) { it.copy(extractState = "ERROR", message = detail) }
        protocol.log("$name：$detail", "error")
    }
    private fun copyRange(input: PayloadSource, origin: ParseResult.Origin.OuterRange, file: File, progress: (Float) -> Unit) {
        require(origin.offset >= 0 && origin.compressedSize >= 0 && origin.uncompressedSize >= 0 && origin.offset <= input.size - origin.compressedSize) { "固件条目超出来源范围" }
        require(origin.method == 0 || origin.method == 8) { "不支持的 ZIP 压缩方法：${origin.method}" }
        if (origin.method == 0) require(origin.compressedSize == origin.uncompressedSize) { "STORED 条目长度不一致" }
        file.parentFile?.mkdirs()
        val inflater = if (origin.method == 8) Inflater(true) else null
        try {
            val incoming = ByteArray(256 * 1024)
            val outgoing = ByteArray(512 * 1024)
            var consumed = 0L
            var produced = 0L
            FileOutputStream(file).use { output ->
                if (inflater == null) {
                    while (consumed < origin.compressedSize) {
                        interrupted()
                        val count = minOf(incoming.size.toLong(), origin.compressedSize - consumed).toInt()
                        input.readFully(origin.offset + consumed, incoming, 0, count)
                        output.write(incoming, 0, count)
                        consumed += count; produced += count
                        progress(if (origin.uncompressedSize == 0L) 1f else (produced.toDouble() / origin.uncompressedSize).toFloat())
                    }
                } else {
                    while (!inflater.finished()) {
                        interrupted()
                        if (inflater.needsInput()) {
                            if (consumed == origin.compressedSize) throw IOException("ZIP DEFLATE 数据截断")
                            val count = minOf(incoming.size.toLong(), origin.compressedSize - consumed).toInt()
                            input.readFully(origin.offset + consumed, incoming, 0, count)
                            inflater.setInput(incoming, 0, count); consumed += count
                        }
                        val count = inflater.inflate(outgoing)
                        if (count > 0) {
                            if (count > origin.uncompressedSize - produced) throw IOException("ZIP 解压超出声明长度")
                            output.write(outgoing, 0, count); produced += count
                            progress(if (origin.uncompressedSize == 0L) 1f else (produced.toDouble() / origin.uncompressedSize).toFloat())
                        } else if (!inflater.finished() && !inflater.needsInput()) throw IOException("无效的 ZIP DEFLATE 数据")
                    }
                    if (consumed - inflater.remaining != origin.compressedSize) throw IOException("ZIP 压缩数据长度不匹配")
                }
                require(produced == origin.uncompressedSize) { "输出文件长度不匹配" }
                output.fd.sync()
            }
        } finally { inflater?.end() }
    }
    private fun verifyCrc(file: File, expected: Long) {
        val crc = CRC32()
        file.inputStream().buffered().use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) { interrupted(); val count = stream.read(buffer); if (count < 0) break; crc.update(buffer, 0, count) }
        }
        require(crc.value == expected) { "ZIP CRC32 校验失败：${file.name}" }
    }
    fun cancel() {
        val job = work ?: return
        update { it.copy(phase = "cancelling", detail = "正在暂停并保存进度…") }
        cancelMetadata()
        job.cancel()
        activeClient?.dispatcher?.cancelAll()
        closeSource()
    }
    fun reset() {
        idleOnly()
        generation++
        cancelMetadata(); closeSource(); closeClients()
        parsed = null; specification = null; sourceIdentity = ""; archiveIdentity = ""
        update { EngineState() }
    }
    fun shutdown() {
        cancel()
        runBlocking { withTimeoutOrNull(5000) { work?.join() } }
        cancelMetadata(); closeSource(); closeClients()
        scope.cancel()
    }
}

private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it.toInt() and 255) }
private fun interrupted() { if (Thread.currentThread().isInterrupted) throw CancellationException("操作已取消") }
private fun message(error: Throwable): String = (error.message ?: error.javaClass.simpleName)
    .replace(Regex("https?://[^\\s]+"), "[下载链接]").take(2048)

fun main() {
    val protocol = Protocol()
    val controller = Controller(protocol)
    val input = System.`in`.bufferedReader(Charsets.UTF_8)
    try {
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            var id = ""
            var shutdown = false
            try {
                require(line.length <= 1024 * 1024) { "请求超出大小限制" }
                val request = JsonParser.parseString(line).asJsonObject
                id = request.get("id")?.asString ?: throw IOException("缺少请求 ID")
                require(id.length <= 128) { "请求 ID 无效" }
                val params = request.getAsJsonObject("params") ?: JsonObject()
                val result: Any? = when (request.get("method")?.asString) {
                    "status" -> controller.state()
                    "parse" -> { controller.parse(params); null }
                    "extract" -> { controller.extract(params); null }
                    "cancel" -> { controller.cancel(); null }
                    "reset" -> { controller.reset(); null }
                    "shutdown" -> { shutdown = true; controller.shutdown(); null }
                    else -> throw IOException("未知引擎方法")
                }
                protocol.write(mapOf("id" to id, "ok" to true, "result" to result))
            } catch (error: Exception) {
                protocol.write(mapOf("id" to id, "ok" to false, "error" to message(error)))
            }
            if (shutdown) break
        }
    } finally { controller.shutdown() }
    exitProcess(0)
}
