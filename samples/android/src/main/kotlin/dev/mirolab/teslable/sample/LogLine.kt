package dev.mirolab.teslable.sample

import dev.mirolab.teslable.model.VehicleEvent
import dev.mirolab.teslable.model.VehicleStatus

/**
 * 로그 한 줄(JSON Lines). 줄 형식의 정본은 `samples/documents/md/04-monitor-app.md` §Log format이고 iOS 앱과 같은 이름·순서를 쓴다.
 * 만들 때 VIN 모양 텍스트와 원본 바이트의 VIN은 이미 가려진다([VinMask]).
 */
class LogLine private constructor(
    val kind: String,
    private val fields: List<Pair<String, Any>>,
    /** `unrecognized: true` 인 event 줄인지(Log 탭의 카운터용). */
    val unrecognized: Boolean = false,
) {
    /** 한 줄 JSON(줄바꿈 없음). 필드 순서는 만든 순서 그대로다. */
    fun toJson(): String =
        buildString {
            append('{')
            fields.forEachIndexed { i, (name, value) ->
                if (i > 0) append(',')
                append(jsonQuote(name))
                append(':')
                when (value) {
                    is String -> append(jsonQuote(value))
                    is List<*> -> value.joinTo(this, ",", "[", "]") { jsonQuote(it.toString()) }
                    else -> append(value.toString()) // Boolean, Int, Long
                }
            }
            append('}')
        }

    override fun toString(): String = toJson()

    @Suppress("TooManyFunctions") // 줄 종류마다 팩토리 하나(session, event, query, ...): 종류가 늘면 함수도 는다
    companion object {
        const val MEMO_MAX = 80
        private const val HEX_DIGITS = "0123456789abcdef"
        private const val BYTE_MASK = 0xff
        private const val NIBBLE_BITS = 4
        private const val NIBBLE_MASK = 0x0f

        private fun line(
            ts: String,
            kind: String,
            vararg rest: Pair<String, Any?>,
        ) = LogLine(kind, listOf<Pair<String, Any>>("ts" to ts, "kind" to kind) + rest.mapNotNull { (k, v) -> v?.let { k to it } })

        /** 파일의 첫 줄. */
        fun session(
            ts: String,
            info: SessionInfo,
        ) = line(ts, "session", "platform" to info.platform, "app" to info.app, "os" to info.os, "phone" to info.phone)

        /** 브로드캐스트 이벤트 한 줄. `raw`는 VIN을 가린 뒤의 소문자 16진수다. */
        fun event(
            ts: String,
            event: VehicleEvent,
        ): LogLine {
            val (type, summary) = describe(event)
            val unrecognized = event.unknownFields.isNotEmpty() || event is VehicleEvent.Unrecognized
            val l =
                line(
                    ts,
                    "event",
                    "type" to type,
                    "summary" to VinMask.maskText(summary),
                    "unknownFields" to event.unknownFields,
                    "unrecognized" to unrecognized,
                    "raw" to hex(VinMask.maskBytes(event.raw)),
                )
            return LogLine(l.kind, l.fields, unrecognized)
        }

        /** 라이브러리 `observe` 줄. 라이브러리가 이미 VIN을 가렸지만 한 번 더 가린다. */
        fun observe(
            ts: String,
            text: String,
        ) = line(ts, "observe", "line" to VinMask.maskText(text))

        fun query(
            ts: String,
            call: String,
            result: String,
            summary: String,
        ) = line(ts, "query", "call" to call, "result" to result, "summary" to VinMask.maskText(summary))

        /** `state`: connecting, connected, disconnected, backoff, stopped. */
        fun link(
            ts: String,
            state: String,
            reason: String? = null,
            attempt: Int? = null,
            delayMs: Long? = null,
        ) = line(ts, "link", "state" to state, "reason" to reason?.let { VinMask.maskText(it) }, "attempt" to attempt, "delayMs" to delayMs)

        /** 메모. 앞뒤 공백을 뗀 뒤 VIN을 가리고 80자로 자른다(가린 뒤에 잘라야 잘린 VIN 조각이 남지 않는다). 비어 있으면 `null`. */
        fun mark(
            ts: String,
            memo: String,
        ): LogLine? {
            val text = VinMask.maskText(memo.trim()).take(MEMO_MAX)
            return if (text.isEmpty()) null else line(ts, "mark", "memo" to text)
        }

        private fun describe(event: VehicleEvent): Pair<String, String> =
            when (event) {
                is VehicleEvent.Status -> {
                    "Status" to statusSummary(event)
                }

                is VehicleEvent.AuthenticationRequest -> {
                    "AuthenticationRequest" to "level=${event.requestedLevel} reasons=${event.reasons}"
                }

                is VehicleEvent.Unrecognized -> {
                    "Unrecognized" to "unknownFields=${event.unknownFields}"
                }
            }

        /** 이벤트의 `Status` 요약. 앞의 세 항목 순서는 04-monitor-app.md 예시와 같다. */
        fun statusSummary(event: VehicleEvent.Status): String = statusSummary(event.status)

        /** `vehicleStatus()` 조회 결과 요약(이벤트 요약과 같은 모양). */
        fun statusSummary(s: VehicleStatus): String {
            val c = s.closures
            return "lock=${s.lockState} gear=${s.gear} presence=${s.userPresence} sleep=${s.sleepStatus} " +
                "doors=${c.frontDriverDoor}/${c.frontPassengerDoor}/${c.rearDriverDoor}/${c.rearPassengerDoor} " +
                "frunk=${c.frontTrunk} trunk=${c.rearTrunk} chargePort=${c.chargePort}"
        }

        private fun hex(bytes: ByteArray): String =
            buildString(bytes.size * 2) {
                for (b in bytes) {
                    val v = b.toInt() and BYTE_MASK
                    append(HEX_DIGITS[v shr NIBBLE_BITS]).append(HEX_DIGITS[v and NIBBLE_MASK])
                }
            }
    }
}

private const val CONTROL_LIMIT = 0x20

/** JSON 문자열 리터럴. 따옴표, 역슬래시, 줄바꿈, 제어 문자를 이스케이프한다(한 줄을 유지하려면 필수). */
private fun jsonQuote(text: String): String =
    buildString(text.length + 2) {
        append('"')
        for (ch in text) {
            when {
                ch == '"' -> append("\\\"")
                ch == '\\' -> append("\\\\")
                ch == '\n' -> append("\\n")
                ch == '\r' -> append("\\r")
                ch == '\t' -> append("\\t")
                ch.code < CONTROL_LIMIT -> append("\\u%04x".format(ch.code))
                else -> append(ch)
            }
        }
        append('"')
    }

/** 파일 첫 줄 `session`에 들어가는 앱·기기 정보. */
data class SessionInfo(
    val platform: String,
    val app: String,
    val os: String,
    val phone: String,
)
