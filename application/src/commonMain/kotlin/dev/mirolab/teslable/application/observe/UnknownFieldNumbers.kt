package dev.mirolab.teslable.application.observe

import com.squareup.wire.ProtoReader
import okio.Buffer
import okio.ByteString
import okio.IOException

/** Wire `unknownFields`의 필드 번호를 나온 순서대로, 반복도 그대로 하나씩. 손상된 버퍼는 읽은 데까지. */
internal fun unknownFieldOccurrences(bytes: ByteString): List<Int> {
    if (bytes.size == 0) return emptyList()
    val tags = mutableListOf<Int>()
    try {
        val reader = ProtoReader(Buffer().write(bytes))
        reader.forEachTag { tag ->
            tags += tag
            reader.skip()
        }
    } catch (ignored: IOException) {
        // 읽은 데까지만 남긴다
    }
    return tags
}

/** Wire `unknownFields`의 필드 번호(중복 제거, 나온 순서). 손상된 버퍼는 읽은 데까지. */
internal fun unknownFieldNumbers(bytes: ByteString): List<Int> = unknownFieldOccurrences(bytes).distinct()
