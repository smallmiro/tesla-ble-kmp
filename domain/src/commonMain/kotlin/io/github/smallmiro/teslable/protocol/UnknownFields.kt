package io.github.smallmiro.teslable.protocol

import com.squareup.wire.ProtoReader
import okio.Buffer
import okio.ByteString
import okio.IOException

/**
 * Wire `unknownFields`에서 [tag]가 가리키는 varint 필드의 원시 값(마지막 값). 없으면 null. Wire가 범위를 벗어난 enum
 * 값을 만나면 원본 바이트를 여기로 옮기므로, 값이 있으면 그 필드는 미인식 값이었다는 뜻이다. 손상된 버퍼는 "없음"(null)으로
 * 취급한다.
 */
internal fun ByteString.unknownVarint(tag: Int): Int? {
    if (size == 0) return null
    return try {
        var value: Int? = null
        val reader = ProtoReader(Buffer().write(this))
        reader.forEachTag { foundTag ->
            if (foundTag == tag) {
                value = reader.readVarint32()
            } else {
                reader.skip()
            }
        }
        value
    } catch (ignored: IOException) {
        null
    }
}
