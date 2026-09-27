package io.github.smallmiro.teslable.testing

import io.github.smallmiro.teslable.port.LogLevel
import io.github.smallmiro.teslable.port.TeslaLogger

/** 로그를 모아 두는 테스트용 로거. 드롭·폐기 사유를 단언하는 데 쓴다. */
public class RecordingLogger : TeslaLogger {
    /** 기록 한 줄. */
    public class Entry(
        /** 레벨. */
        public val level: LogLevel,
        /** 태그. */
        public val tag: String,
        /** 평가된 메시지. */
        public val message: String,
    )

    private val entryList = mutableListOf<Entry>()

    /** 지금까지의 기록(사본). */
    public val entries: List<Entry> get() = entryList.toList()

    override fun log(
        level: LogLevel,
        tag: String,
        message: () -> String,
    ) {
        entryList += Entry(level, tag, message())
    }

    /** [fragment]를 포함하는 메시지가 하나라도 있으면 true. */
    public fun contains(fragment: String): Boolean = entryList.any { it.message.contains(fragment) }

    /** [fragment]를 포함하는 메시지 수. */
    public fun count(fragment: String): Int = entryList.count { it.message.contains(fragment) }
}
