package io.github.smallmiro.teslable.protocol

import com.squareup.wire.ProtoAdapter
import io.github.smallmiro.teslable.InternalTeslableApi
import okio.IOException

/**
 * Wire의 `decode`를 감싸, 디코딩할 수 없는 입력을 예외가 아닌 `null`로 돌려준다(ADR-0006: 와이어 입력의 오류는 값).
 * `okio.IOException`뿐 아니라, Wire가 손상된 입력에 던지는 `IllegalStateException`도 잡는다(예: 메시지 타입 필드가
 * 잘못된 wire type을 실었을 때 `beginMessage()`가 던지는 오류 — `WireDecodingTest` 참고). `IllegalArgumentException`
 * 캐치는 방어적이다: Wire가 생성한 모든 oneof의 `decode()`는 태그를 읽을 때마다 마지막에 이긴 멤버만 남기고 나머지를
 * 명시적으로 null로 지운 뒤 생성자를 호출하므로, `decode()` 경로에서 "at most one of ..." 오류가 실제로 나는 입력은
 * 찾지 못했다(`WireDecodingTest` 참고) — 그래도 신뢰할 수 없는 입력에서 나오는 어떤 `IllegalArgumentException`도
 * 프로그래밍 오류로 오분류하지 않기 위해 잡아 둔다. 나중 태스크(6, 10)의 `:application`도 이 함수로 응답 바이트를
 * 디코딩한다.
 */
@InternalTeslableApi
public fun <M : Any> ProtoAdapter<M>.decodeOrNull(bytes: ByteArray): M? =
    try {
        decode(bytes)
    } catch (ignored: IOException) {
        null
    } catch (ignored: IllegalStateException) {
        null
    } catch (ignored: IllegalArgumentException) {
        null
    }
