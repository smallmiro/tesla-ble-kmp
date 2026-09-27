package io.github.smallmiro.teslable.protocol

import com.squareup.wire.ProtoAdapter
import io.github.smallmiro.teslable.InternalTeslableApi
import okio.IOException

/**
 * Wire의 `decode`를 감싸, 디코딩할 수 없는 입력을 예외가 아닌 `null`로 돌려준다(ADR-0006: 와이어 입력의 오류는 값).
 * 나중 태스크(6, 10)의 `:application`도 이 함수로 응답 바이트를 디코딩한다.
 */
@InternalTeslableApi
public fun <M : Any> ProtoAdapter<M>.decodeOrNull(bytes: ByteArray): M? =
    try {
        decode(bytes)
    } catch (ignored: IOException) {
        null
    }
