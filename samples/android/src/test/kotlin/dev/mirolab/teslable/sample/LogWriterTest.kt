package dev.mirolab.teslable.sample

import dev.mirolab.teslable.model.ClosureState
import dev.mirolab.teslable.model.Closures
import dev.mirolab.teslable.model.Gear
import dev.mirolab.teslable.model.LockState
import dev.mirolab.teslable.model.SleepStatus
import dev.mirolab.teslable.model.UserPresence
import dev.mirolab.teslable.model.VehicleEvent
import dev.mirolab.teslable.model.VehicleStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 시계를 손으로 움직이는 테스트용 시계(파일 이름의 초가 겹치지 않게 한다). */
private class TestClock(
    var now: Instant = Instant.parse("2026-10-24T01:00:00.123Z"),
) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.ofHours(9)

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = now
}

class LogWriterTest {
    // 실제 VIN이 아닌 가짜 값(형식만 같다)
    private val vin = "5YJAAAA1111222333"
    private val session = SessionInfo(platform = "android", app = "0.2.0(2)", os = "16", phone = "Pixel")
    private val iso = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}[+-]\d{2}:\d{2}""")
    private val fileName = Regex("""monitor-\d{8}-\d{6}\.jsonl""")

    private fun tempDir(): File = Files.createTempDirectory("monitor-logs").toFile().also { it.deleteOnExit() }

    private fun files(dir: File): List<File> = dir.listFiles()!!.filter { it.name.endsWith(".jsonl") }.sortedBy { it.name }

    private fun lines(file: File): List<JSONObject> = file.readLines().map { JSONObject(it) }

    private fun status(
        unknown: List<String> = emptyList(),
        raw: ByteArray = byteArrayOf(1, 2, 3),
    ) = VehicleEvent.Status(
        VehicleStatus(
            LockState.LOCKED,
            Closures(
                ClosureState.CLOSED, ClosureState.CLOSED, ClosureState.CLOSED, ClosureState.CLOSED,
                ClosureState.CLOSED, ClosureState.CLOSED, ClosureState.CLOSED, ClosureState.CLOSED,
            ),
            SleepStatus.AWAKE,
            UserPresence.NOT_PRESENT,
            null,
            Gear.PARK,
        ),
        raw,
        unknown,
    )

    private fun writer(
        scope: CoroutineScope,
        dir: File,
        clock: Clock = TestClock(),
        maxFile: Long = LogWriter.DEFAULT_MAX_FILE_BYTES,
        maxTotal: Long = LogWriter.DEFAULT_MAX_TOTAL_BYTES,
        queue: Int = 1024,
    ) = LogWriter(dir, session, scope, clock, maxFile, maxTotal, queue)

    @Test
    fun defaultsAre20MbPerFileAnd200MbInTotal() {
        assertEquals(20_000_000L, LogWriter.DEFAULT_MAX_FILE_BYTES)
        assertEquals(200_000_000L, LogWriter.DEFAULT_MAX_TOTAL_BYTES)
    }

    @Test
    fun firstLineOfTheFileIsTheSessionLine() =
        runTest {
            val dir = tempDir()
            val w = writer(backgroundScope, dir)
            w.write(LogLine.mark(w.stamp(), "hello")!!)
            w.flush()

            val file = files(dir).single()
            assertTrue(fileName.matches(file.name), file.name)
            val first = lines(file).first()
            assertEquals("session", first.getString("kind"))
            assertEquals("android", first.getString("platform"))
            assertEquals("0.2.0(2)", first.getString("app"))
            assertEquals("16", first.getString("os"))
            assertEquals("Pixel", first.getString("phone"))
            assertTrue(iso.matches(first.getString("ts")), first.getString("ts"))
            assertEquals("2026-10-24T10:00:00.123+09:00", first.getString("ts"))
        }

    @Test
    fun eventLineMatchesSchema() =
        runTest {
            val dir = tempDir()
            val w = writer(backgroundScope, dir)
            val raw = byteArrayOf(0x0a, 0x11) + vin.toByteArray(Charsets.US_ASCII) + byteArrayOf(0x7f)
            w.write(LogLine.event(w.stamp(), status(listOf("1.8"), raw)))
            w.flush()

            val o = lines(files(dir).single())[1]
            assertEquals(setOf("ts", "kind", "type", "summary", "unknownFields", "unrecognized", "raw"), o.keys().asSequence().toSet())
            // 필드 순서: ts, kind가 앞(JSON 파서는 순서를 보존하지 않으므로 원문으로 확인한다)
            val text = file(dir).readLines()[1]
            assertTrue(text.startsWith("{\"ts\":\""), text)
            assertTrue(text.contains("\",\"kind\":\"event\",\"type\":\"Status\",\"summary\":\""), text)
            assertTrue(iso.matches(o.getString("ts")))
            assertEquals("event", o.getString("kind"))
            assertEquals("Status", o.getString("type"))
            assertTrue(o.getString("summary").startsWith("lock=LOCKED gear=PARK presence=NOT_PRESENT"), o.getString("summary"))
            assertEquals(listOf("1.8"), o.getJSONArray("unknownFields").let { a -> (0 until a.length()).map { a.getString(it) } })
            assertTrue(o.getBoolean("unrecognized"))
            // raw: 소문자 16진수, VIN은 같은 길이로 가려진다
            val expected = (byteArrayOf(0x0a, 0x11) + "5YJ**********2333".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x7f))
                .joinToString("") { "%02x".format(it) }
            assertEquals(expected, o.getString("raw"))
            assertFalse(file(dir).readText().contains(vin))
        }

    private fun file(dir: File): File = files(dir).single()

    @Test
    fun otherKindsMatchSchema() =
        runTest {
            val dir = tempDir()
            val w = writer(backgroundScope, dir)
            val ts = w.stamp()
            w.write(LogLine.observe(ts, "[drop:no-handler] 83B from=domain(X) vin=$vin"))
            w.write(LogLine.query(ts, "vehicleStatus", "Success", "lock=LOCKED"))
            w.write(LogLine.link(ts, "backoff", reason = "link lost", attempt = 3, delayMs = 8000))
            w.write(LogLine.link(ts, "stopped"))
            w.flush()

            val all = lines(file(dir))
            val observe = all[1]
            assertEquals(setOf("ts", "kind", "line"), observe.keys().asSequence().toSet())
            assertEquals("observe", observe.getString("kind"))
            assertEquals("[drop:no-handler] 83B from=domain(X) vin=5YJ**********2333", observe.getString("line"))
            val query = all[2]
            assertEquals(setOf("ts", "kind", "call", "result", "summary"), query.keys().asSequence().toSet())
            val backoff = all[3]
            assertEquals(setOf("ts", "kind", "state", "reason", "attempt", "delayMs"), backoff.keys().asSequence().toSet())
            assertEquals(8000, backoff.getInt("delayMs"))
            assertEquals(setOf("ts", "kind", "state"), all[4].keys().asSequence().toSet())
            assertEquals("stopped", all[4].getString("state"))
        }

    @Test
    fun unrecognizedFlag() =
        runTest {
            val dir = tempDir()
            val w = writer(backgroundScope, dir)
            val ts = w.stamp()
            w.write(LogLine.event(ts, status()))
            w.write(LogLine.event(ts, status(unknown = listOf("1.8"))))
            w.write(LogLine.event(ts, VehicleEvent.Unrecognized(byteArrayOf(9), emptyList())))
            w.write(LogLine.event(ts, VehicleEvent.Unrecognized(byteArrayOf(9), listOf("53"))))
            w.flush()

            val events = lines(file(dir)).filter { it.getString("kind") == "event" }
            assertEquals(listOf(false, true, true, true), events.map { it.getBoolean("unrecognized") })
            assertEquals(listOf("Status", "Status", "Unrecognized", "Unrecognized"), events.map { it.getString("type") })
            assertEquals(3, w.stats.value.unrecognized)
            assertEquals(4, w.stats.value.byKind["event"])
        }

    @Test
    fun markMemoTruncatedTo80AndMasked() {
        val long = "m".repeat(70) + vin
        val line = LogLine.mark("2026-10-24T10:00:00.000+09:00", long)!!
        val memo = JSONObject(line.toJson()).getString("memo")

        assertEquals(80, memo.length)
        assertEquals("m".repeat(70) + "5YJ*******", memo)
        assertFalse(line.toJson().contains(vin))
    }

    @Test
    fun markWithEmptyMemoIsIgnoredAndShortMemoIsKept() {
        assertEquals(null, LogLine.mark("t", ""))
        assertEquals(null, LogLine.mark("t", "   "))
        assertEquals("before: open driver door", JSONObject(LogLine.mark("t", " before: open driver door ")!!.toJson()).getString("memo"))
    }

    @Test
    fun jsonEscapesQuotesAndControlCharacters() {
        val line = LogLine.mark("t", "a\"b\\c\nd\u0001")!!
        assertEquals("a\"b\\c\nd\u0001", JSONObject(line.toJson()).getString("memo"))
        assertFalse(line.toJson().contains('\n'))
    }

    @Test
    fun rotatesAt20Mb() =
        runTest {
            val dir = tempDir()
            val clock = TestClock()
            // 20 MB 대신 400바이트로 줄여서 같은 경로를 시험한다
            val w = writer(backgroundScope, dir, clock, maxFile = 400)
            repeat(30) { i ->
                clock.now = clock.now.plusSeconds(1)
                w.write(LogLine.mark(w.stamp(), "line $i")!!)
            }
            w.flush()

            val all = files(dir)
            assertTrue(all.size > 2, "files=${all.size}")
            all.forEach { assertTrue(it.length() <= 400, "${it.name} ${it.length()}") }
            all.forEach { assertEquals("session", lines(it).first().getString("kind")) }
            // 줄은 하나도 잃지 않고, 파일 이름 순서 = 시간 순서
            val marks = all.flatMap { lines(it) }.filter { it.getString("kind") == "mark" }.map { it.getString("memo") }
            assertEquals((0 until 30).map { "line $it" }, marks)
            assertEquals(all.map { it.name }.sorted(), all.map { it.name })
            assertEquals(all.last().absolutePath, w.stats.value.currentFile)
        }

    @Test
    fun prunesAt200Mb() =
        runTest {
            val dir = tempDir()
            val clock = TestClock()
            // 200 MB 대신 1500바이트로 줄여서 같은 경로를 시험한다
            val w = writer(backgroundScope, dir, clock, maxFile = 400, maxTotal = 1_500)
            repeat(80) { i ->
                clock.now = clock.now.plusSeconds(1)
                w.write(LogLine.mark(w.stamp(), "line $i")!!)
                w.flush()
                assertTrue(files(dir).sumOf { it.length() } <= 1_500, "after line $i")
            }

            val names = files(dir).map { it.name }
            assertTrue(names.size >= 2)
            val lastMemos = files(dir).flatMap { lines(it) }.filter { it.getString("kind") == "mark" }.map { it.getString("memo") }
            assertEquals("line 79", lastMemos.last())
            assertFalse(lastMemos.contains("line 0"), "the oldest file should be gone")
            assertEquals(lastMemos.sortedBy { it.removePrefix("line ").toInt() }, lastMemos)
        }

    @Test
    fun newFileStartsAFreshFileWithASessionLine() =
        runTest {
            val dir = tempDir()
            val w = writer(backgroundScope, dir)
            w.write(LogLine.mark(w.stamp(), "one")!!)
            w.newFile()
            w.write(LogLine.mark(w.stamp(), "two")!!)
            w.flush()

            val all = files(dir)
            assertEquals(2, all.size)
            assertEquals(listOf("session", "mark"), lines(all[0]).map { it.getString("kind") })
            assertEquals(listOf("session", "mark"), lines(all[1]).map { it.getString("kind") })
            assertEquals("two", lines(all[1])[1].getString("memo"))
        }

    @Test
    fun newFileNameStaysAfterTheNewestFileWhenTheClockGoesBack() =
        runTest {
            val dir = tempDir()
            val clock = TestClock()
            val w = writer(backgroundScope, dir, clock)
            w.write(LogLine.mark(w.stamp(), "one")!!)
            w.flush()
            clock.now = clock.now.minusSeconds(3600) // 서머타임 끝이나 시계 조정으로 시계가 뒤로 감
            w.newFile()
            w.write(LogLine.mark(w.stamp(), "two")!!)
            w.flush()

            val names = files(dir).map { it.name }
            assertEquals(2, names.size)
            assertEquals(names.sorted(), names)
            assertEquals("two", lines(files(dir).last())[1].getString("memo"))
        }

    @Test
    fun newFileIsNotLostWhenTheQueueIsFull() =
        runTest {
            val dir = tempDir()
            val w = writer(backgroundScope, dir, queue = 1)
            w.write(LogLine.mark(w.stamp(), "one")!!) // 큐가 찬다
            w.newFile() // 들어갈 자리가 없어도 잃지 않는다(자리가 나면 들어간다)
            w.flush()
            w.flush() // 두 번째 flush는 그 뒤에 들어간 NewFile 까지 처리된 것을 보장한다

            assertEquals(2, files(dir).size)
        }

    @Test
    fun fullQueueDropsLinesInsteadOfBlocking() =
        runTest {
            val dir = tempDir()
            val w = writer(backgroundScope, dir, queue = 2)
            // 쓰기 코루틴이 아직 돌지 않으므로 큐는 2개까지만 받는다. write()는 멈추지 않는다.
            repeat(10) { w.write(LogLine.mark(w.stamp(), "m$it")!!) }
            w.flush()

            assertEquals(8, w.stats.value.dropped)
            assertEquals(2, lines(file(dir)).count { it.getString("kind") == "mark" })
        }

    @Test
    fun statsKeepTheLastLines() =
        runTest {
            val dir = tempDir()
            val w = writer(backgroundScope, dir)
            repeat(LogWriter.LAST_LINES + 5) { w.write(LogLine.mark(w.stamp(), "m$it")!!) }
            w.flush()

            val last = w.stats.value.lastLines
            assertEquals(LogWriter.LAST_LINES, last.size)
            assertTrue(last.last().contains("m${LogWriter.LAST_LINES + 4}"))
        }
}
