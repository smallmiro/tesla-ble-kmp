package dev.mirolab.teslable.sample

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.time.Clock
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

/** 로그를 받는 곳(04-monitor-app.md §Log format). 지금은 파일 구현([LogWriter])뿐이다. 클라우드 싱크는 ADR 뒤에 추가한다. */
interface LogSink {
    /** 줄에 찍을 현재 시각(ISO-8601, 오프셋 포함, 기기 시계). */
    fun stamp(): String

    /** 줄을 넘긴다. 절대 멈추지 않는다(수집 코루틴이 막히면 안 된다). */
    fun write(line: LogLine)

    /** 지금까지 넘긴 줄이 파일에 쓰일 때까지 기다린다. */
    suspend fun flush()

    /** 새 파일로 넘어간다(첫 줄은 `session`). */
    fun newFile()

    fun close()
}

/** Log 탭이 보여 주는 숫자와 마지막 줄들. */
data class LogStats(
    val totalLines: Int = 0,
    val byKind: Map<String, Int> = emptyMap(),
    val unrecognized: Int = 0,
    /** 큐가 가득 차서 버린 줄 수. */
    val dropped: Int = 0,
    val lastLines: List<String> = emptyList(),
    val currentFile: String? = null,
)

/**
 * 파일 로그 쓰기(JSON Lines). 전용 코루틴이 큐를 비우며 쓰므로 [write]는 멈추지 않는다. 큐가 가득 차면 줄을 버리고 센다.
 *
 * - 파일 이름 `monitor-<yyyyMMdd-HHmmss>.jsonl`, 모든 파일의 첫 줄은 `session`.
 * - [maxFileBytes]를 넘기게 되면 새 파일(기본 20 MB). 폴더 합계가 [maxTotalBytes]를 넘으면 가장 오래된 파일부터 지운다(기본 200 MB).
 * - 같은 초에 새 파일이 겹치면 이름의 초를 하나 올려서 이름 순서가 곧 시간 순서가 되게 한다.
 */
class LogWriter(
    private val dir: File,
    private val session: SessionInfo,
    scope: CoroutineScope,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
    private val maxTotalBytes: Long = DEFAULT_MAX_TOTAL_BYTES,
    queueCapacity: Int = DEFAULT_QUEUE,
) : LogSink {
    private sealed interface Command {
        class Line(
            val line: LogLine,
        ) : Command

        data object NewFile : Command

        class Flush(
            val done: CompletableDeferred<Unit>,
        ) : Command
    }

    private val queue = Channel<Command>(queueCapacity)
    private val dropped = AtomicInteger(0)
    private val mutableStats = MutableStateFlow(LogStats())
    val stats: StateFlow<LogStats> = mutableStats

    private var writer: BufferedWriter? = null
    private var current: File? = null
    private var currentBytes = 0L
    private var othersBytes = 0L

    init {
        dir.mkdirs()
        scope.launch {
            try {
                for (command in queue) handle(command)
            } finally {
                closeFile()
            }
        }
    }

    override fun stamp(): String = OffsetDateTime.now(clock).format(TS_FORMAT)

    override fun write(line: LogLine) {
        if (queue.trySend(Command.Line(line)).isFailure) {
            val n = dropped.incrementAndGet()
            mutableStats.update { it.copy(dropped = n) }
        }
    }

    override suspend fun flush() {
        val done = CompletableDeferred<Unit>()
        queue.send(Command.Flush(done))
        done.await()
    }

    override fun newFile() {
        queue.trySend(Command.NewFile)
    }

    override fun close() {
        queue.close()
    }

    private fun handle(command: Command) {
        when (command) {
            is Command.Line -> append(command.line)
            Command.NewFile -> openNewFile()
            is Command.Flush -> {
                writer?.flush()
                command.done.complete(Unit)
            }
        }
    }

    private fun append(line: LogLine) {
        val text = line.toJson()
        val bytes = text.toByteArray(Charsets.UTF_8).size + 1L
        if (writer == null || (currentBytes > 0 && currentBytes + bytes > maxFileBytes)) openNewFile()
        writeRaw(text, bytes)
        pruneIfNeeded()
        mutableStats.update { s ->
            s.copy(
                totalLines = s.totalLines + 1,
                byKind = s.byKind + (line.kind to (s.byKind[line.kind] ?: 0) + 1),
                unrecognized = s.unrecognized + if (line.unrecognized) 1 else 0,
                lastLines = (s.lastLines + text).takeLast(LAST_LINES),
            )
        }
    }

    private fun writeRaw(
        text: String,
        bytes: Long,
    ) {
        val w = writer ?: return
        w.write(text)
        w.newLine()
        w.flush() // 줄이 드문 로그라 줄마다 내보낸다: 프로세스가 죽어도 마지막 줄까지 남는다
        currentBytes += bytes
    }

    private fun openNewFile() {
        closeFile()
        val file = freshFile()
        othersBytes = listFiles().sumOf { it.length() }
        writer = BufferedWriter(OutputStreamWriter(FileOutputStream(file, true), Charsets.UTF_8))
        current = file
        currentBytes = 0
        mutableStats.update { it.copy(currentFile = file.absolutePath) }
        val head = LogLine.session(stamp(), session).toJson()
        writeRaw(head, head.toByteArray(Charsets.UTF_8).size + 1L)
        pruneIfNeeded()
    }

    /** 폴더 합계(다른 파일 + 지금 파일)가 한도를 넘으면 지금 파일을 뺀 가장 오래된 것부터 지운다. */
    private fun pruneIfNeeded() {
        if (othersBytes + currentBytes <= maxTotalBytes) return
        val keep = current
        for (file in listFiles()) {
            if (othersBytes + currentBytes <= maxTotalBytes) break
            if (file == keep) continue
            val size = file.length()
            if (file.delete()) othersBytes -= size
        }
    }

    private fun listFiles(): List<File> = dir.listFiles { f -> f.name.startsWith("monitor-") && f.name.endsWith(".jsonl") }.orEmpty().sortedBy { it.name }

    private fun freshFile(): File {
        var at = OffsetDateTime.now(clock)
        while (true) {
            val file = File(dir, "monitor-${at.format(NAME_FORMAT)}.jsonl")
            if (!file.exists()) return file
            at = at.plusSeconds(1)
        }
    }

    private fun closeFile() {
        runCatching { writer?.close() }
        writer = null
    }

    companion object {
        const val DEFAULT_MAX_FILE_BYTES = 20_000_000L
        const val DEFAULT_MAX_TOTAL_BYTES = 200_000_000L
        const val LAST_LINES = 30
        private const val DEFAULT_QUEUE = 4096
        private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSxxx")
        private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    }
}
