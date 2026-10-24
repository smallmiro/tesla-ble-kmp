package dev.mirolab.teslable.application.observe

import com.tesla.generated.universalmessage.Domain
import com.tesla.generated.universalmessage.RoutableMessage

/**
 * 원본에 없음 — VCSEC가 보낸 원래 평문 브로드캐스트(서명·세션 정보·요청 UUID 없음)인지. 관찰 로그 상세 디코드(v2)의 입구이자
 * (18-lib §Observation log) `Dispatcher.plainBroadcasts`로 내보낼 프레임의 조건이다(FR-035). 두 곳이 같은 조건을 쓴다.
 */
internal fun isPlainBroadcast(message: RoutableMessage): Boolean =
    message.from_destination?.domain == Domain.DOMAIN_VEHICLE_SECURITY &&
        message.to_destination?.domain == Domain.DOMAIN_BROADCAST &&
        message.request_uuid.size == 0 &&
        message.signature_data == null &&
        message.session_info == null
