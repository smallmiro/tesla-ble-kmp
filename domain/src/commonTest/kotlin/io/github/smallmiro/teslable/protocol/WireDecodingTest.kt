package io.github.smallmiro.teslable.protocol

import com.tesla.generated.universalmessage.RoutableMessage
import io.github.smallmiro.teslable.InternalTeslableApi
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * 컨트롤러 추가 항목 P2(ADR-0006): Wire `decode`는 손상된 입력에 `okio.IOException`만 던지지 않는다. 실측해 보면(아래
 * [malformedRoutableMessageBytesDecodeToNullInsteadOfThrowing] 참고) `RoutableMessage.ADAPTER.decode`가
 * `IllegalStateException`도 던진다 — 와이어 입력의 오류는 값이어야 하므로 [decodeOrNull]은 이것도 잡아야 한다.
 *
 * "필드 하나에 payload oneof 멤버 두 개가 실린 바이트"는 예외를 던지지 않는다: Wire가 생성한 모든 oneof의 `decode()`는
 * 태그를 읽을 때마다 sentinel로 마지막에 이긴 멤버만 남기고 나머지를 명시적으로 null로 지운 뒤 생성자를 호출한다
 * (`RoutableMessage.kt`의 생성된 `decode()` 참고 — Go `proto.Unmarshal`과 같은 "마지막 값이 이긴다" 동작이다).
 * [decodingTwoPayloadOneofMembersKeepsOnlyTheLastLikeGo]는 이 Go-동등 동작을 고정한다(회귀가 아니라 안전성 확인).
 *
 * `SessionInfo`(M1의 두 호출부, `ResponseClassifier.sessionInfoError`/`Signer.decodeSessionInfo`가 디코딩하는 타입)는
 * 중첩 메시지 필드나 oneof가 전혀 없다(`counter`/`publicKey`/`epoch`/`clock_time`/`status`/`handle`뿐). `decode()`가
 * `IllegalStateException`을 던지는 유일한 경로(`beginMessage()`가 기대한 length-delimited가 아닌 wire type을 만남)는
 * 메시지 타입 필드에서만 일어나므로 `SessionInfo`에는 구조적으로 해당하지 않는다. 손상된 varint, 길이 초과,
 * START_GROUP/END_GROUP 없는 그룹 태그로 실측해도 전부 `okio.IOException` 계열만 나왔다 — 두 호출부 모두 이미
 * 통과한다. 이 두 자리는 그래서 테스트하지 않는다(태스크 리포트에 실측 근거 기록).
 */
@OptIn(InternalTeslableApi::class)
class WireDecodingTest {
    @Test
    fun malformedRoutableMessageBytesDecodeToNullInsteadOfThrowing() =
        // 실측: "I'm not a valid protobuf"의 바이트0(0x49)는 필드 9(미확인, fixed64)로 읽혀 바이트1~8을 건너뛴다.
        // 다음 태그 바이트9(0x20)는 필드 4(미확인, varint)로 읽혀 바이트10('v'=0x76, 최상위 비트 0)만 소비한다. 그
        // 다음 태그 바이트11(0x61)이 필드 12(signedMessageStatus, 메시지 타입)를 가리키지만 wire type이 fixed64(1)로
        // 잘못 해석된다. `decodeMessageOrMerge`가 `beginMessage()`를 호출하면 length-delimited가 아니므로 Wire가
        // `IllegalStateException`을 던진다(`okio.IOException`이 아니다).
        assertNull(RoutableMessage.ADAPTER.decodeOrNull("I'm not a valid protobuf".encodeToByteArray()))

    @Test
    fun decodingTwoPayloadOneofMembersKeepsOnlyTheLastLikeGo() {
        // Go 동등성 고정: 손으로 인코딩한 바이트에 protobuf_message_as_bytes(태그10, 길이0)와 session_info(태그15,
        // 길이0)를 둘 다 실어도 예외 없이 디코딩되고, 나중에 읽은 session_info만 남는다. protobuf 스펙과 Go
        // `proto.Unmarshal` 모두 "같은 oneof를 두 번 쓰면 마지막 값이 이긴다"이므로 이 동작은 버그가 아니라 명세대로다.
        val bytes = byteArrayOf(0x52, 0x00, 0x7A, 0x00) // 태그10(protobuf_message_as_bytes) 길이0, 태그15(session_info) 길이0
        val decoded = assertNotNull(RoutableMessage.ADAPTER.decodeOrNull(bytes))
        assertNull(decoded.protobuf_message_as_bytes)
        assertNotNull(decoded.session_info)
    }
}
