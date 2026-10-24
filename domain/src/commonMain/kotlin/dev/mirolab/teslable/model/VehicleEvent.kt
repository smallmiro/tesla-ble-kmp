package dev.mirolab.teslable.model

/**
 * 차량이 요청 없이 보낸 평문 VCSEC 브로드캐스트 한 통을 해석한 결과(FR-035, `Vehicle.events`). 원본 Go는 브로드캐스트를 버린다 —
 * 이 이벤트는 원본에 없는 동작이다.
 *
 * - 이벤트가 되는 프레임: 출발이 VCSEC 도메인, 목적지가 브로드캐스트 도메인, 요청 UUID·서명·세션 정보가 없는 `RoutableMessage`.
 *   다른 클라이언트에게 가는 응답, 서명된 프레임, `RoutableMessage`로 해석되지 않는 프레임은 이벤트가 아니다. 라이브러리가 복호화한
 *   평문은 이벤트에 나오지 않는다(FR-112).
 * - 프레임 하나에서 `vehicleStatus`는 [Status], `authenticationRequest`는 [AuthenticationRequest]가 된다. 둘이 같이 오면 [Status],
 *   [AuthenticationRequest] 순서로 두 이벤트가 나오고 둘의 [raw]·[unknownFields]가 같다. 둘 다 없으면 [Unrecognized] 하나다.
 * - **반복은 변화가 아니다.** 차량은 바뀐 것이 없어도 `vehicleStatus`를 1~25초마다(때로는 1초 안에 여러 번) 다시 보낸다. 라이브러리는
 *   중복을 거르지 않는다. 바뀌었는지는 앱이 이전 값과 비교한다.
 */
public sealed interface VehicleEvent {
    /**
     * 받은 `RoutableMessage` 프레임 전체(BLE 길이 접두 2바이트는 빼고)의 복사본. 읽을 때마다 새 복사본이므로 한 번 읽어 두고 쓴다.
     * 차량이 평문으로 보낸 바이트 그대로이며 **VIN을 가리지 않는다** — 앱이 저장하거나 공유하기 전에 가린다.
     */
    public val raw: ByteArray

    /**
     * [raw]에서 스키마가 모르는 필드의 위치. `FromVCSECMessage`에서 시작하는 점 경로다: 최상위 필드 n은 `"n"`(예: `"53"`, `"48"`),
     * `vehicleStatus`(1) 안은 `"1.n"`(예: `"1.8"`), 그 안의 `closureStatuses`(1)·`detailedClosureStatus`(5)는 `"1.1.n"`·`"1.5.n"`,
     * `authenticationRequest`(3) 안은 `"3.n"`, 그 토큰 메시지(2) 안은 `"3.2.n"`. 스키마 밖 enum 값(모델에서는 `UNKNOWN`)도 그 필드
     * 번호로 나온다(예: 새 기어 값은 `"1.7"`). 최상위는 도착 순서이고, 하위 메시지 안에서는 그 메시지 자신의 필드 다음에 그 안 메시지의
     * 필드가 온다. 같은 번호가 여러 번 오면 그대로 여러 번 나온다. 그 밖의 메시지(`commandStatus` 등)의 안은 보지 않는다.
     * 비어 있지 않으면 해석하지 못한 부분이 있다는 뜻이고, 내용은 [raw]에 있다.
     */
    public val unknownFields: List<String>

    /**
     * `FromVCSECMessage.vehicleStatus`(1). [status]는 `vehicleStatus()` 조회와 같은 규칙으로 만든다: 차량은 열린 곳이 있을 때만
     * `closureStatuses`를 보내므로 없으면 모두 `CLOSED`, 모르는 enum 값은 `UNKNOWN`, `gear`도 같은 규칙. **트렁크 주의:** 실내 버튼으로
     * 트렁크를 열고 닫았을 때는 브로드캐스트가 오지 않았다(다른 방법은 미확인). 트렁크는 `vehicleStatus()`로 읽는다.
     *
     * @param raw 받은 프레임. 복사해서 보관한다.
     * @param unknownFields [VehicleEvent.unknownFields].
     */
    public class Status(
        /** 차량 상태. */
        public val status: VehicleStatus,
        raw: ByteArray,
        unknownFields: List<String>,
    ) : VehicleEvent {
        private val bytes = raw.copyOf()

        override val raw: ByteArray get() = bytes.copyOf()

        override val unknownFields: List<String> = unknownFields.toList()

        override fun equals(other: Any?): Boolean =
            other is Status && status == other.status && bytes.contentEquals(other.bytes) && unknownFields == other.unknownFields

        override fun hashCode(): Int = 31 * (31 * status.hashCode() + bytes.contentHashCode()) + unknownFields.hashCode()

        override fun toString(): String = "Status(status=$status, unknownFields=$unknownFields, raw=${bytes.size}B)"
    }

    /**
     * `FromVCSECMessage.authenticationRequest`(3, Teslemetry 스키마의 TESLEMETRY-EXT, 하위 필드 이름 미확인). 차량이 폰 키에 인증을
     * 요청한 사건이다. 실차에서는 실내 손잡이를 당길 때(`DRIVE`, `PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL`)와 충전구를 열고 충전을 시작할 때
     * (`ENTERED_HIGHER_AUTH_ZONE`이 더해짐) 보였다. 요청 토큰(20바이트)은 필드로 내보내지 않는다 — 필요하면 [raw]에 있다.
     *
     * @param raw 받은 프레임. 복사해서 보관한다.
     * @param unknownFields [VehicleEvent.unknownFields].
     */
    public class AuthenticationRequest(
        /** 요청한 인증 수준. 차량이 보내지 않은 것과 `NONE`(0)은 구별할 수 없다(proto3). */
        public val requestedLevel: AuthenticationLevel,
        reasons: List<AuthenticationReason>,
        raw: ByteArray,
        unknownFields: List<String>,
    ) : VehicleEvent {
        private val bytes = raw.copyOf()

        /**
         * 인증을 요청한 이유. 차량이 보낸 값마다 하나씩이다. 스키마 밖 값은 [AuthenticationReason.UNKNOWN]이며 아는 값들 뒤에 온다
         * (아는 값 사이에서의 원래 위치는 남지 않는다 — 정확한 순서는 [raw]에 있다).
         */
        public val reasons: List<AuthenticationReason> = reasons.toList()

        override val raw: ByteArray get() = bytes.copyOf()

        override val unknownFields: List<String> = unknownFields.toList()

        override fun equals(other: Any?): Boolean =
            other is AuthenticationRequest &&
                requestedLevel == other.requestedLevel &&
                reasons == other.reasons &&
                bytes.contentEquals(other.bytes) &&
                unknownFields == other.unknownFields

        override fun hashCode(): Int =
            31 * (31 * (31 * requestedLevel.hashCode() + reasons.hashCode()) + bytes.contentHashCode()) + unknownFields.hashCode()

        override fun toString(): String =
            "AuthenticationRequest(requestedLevel=$requestedLevel, reasons=$reasons, unknownFields=$unknownFields, raw=${bytes.size}B)"
    }

    /**
     * `vehicleStatus`도 `authenticationRequest`도 없는 브로드캐스트: 모르는 필드만 있는 프레임(실차의 필드 48·53), 이벤트로 해석하지
     * 않는 다른 `FromVCSECMessage` 필드, 페이로드가 없거나 `FromVCSECMessage`로 디코딩되지 않는 프레임. 마지막 경우 [unknownFields]는
     * 비어 있다. 내용은 [raw]로 본다.
     *
     * @param raw 받은 프레임. 복사해서 보관한다.
     * @param unknownFields [VehicleEvent.unknownFields].
     */
    public class Unrecognized(
        raw: ByteArray,
        unknownFields: List<String>,
    ) : VehicleEvent {
        private val bytes = raw.copyOf()

        override val raw: ByteArray get() = bytes.copyOf()

        override val unknownFields: List<String> = unknownFields.toList()

        override fun equals(other: Any?): Boolean =
            other is Unrecognized && bytes.contentEquals(other.bytes) && unknownFields == other.unknownFields

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + unknownFields.hashCode()

        override fun toString(): String = "Unrecognized(unknownFields=$unknownFields, raw=${bytes.size}B)"
    }
}

/** 차량이 요청한 인증 수준(`VCSEC.AuthenticationLevel_E`, TESLEMETRY-EXT). 모르는 값은 [UNKNOWN]. */
public enum class AuthenticationLevel {
    /** 인증 필요 없음(0). 차량이 값을 보내지 않은 경우도 이 값이다(proto3). */
    NONE,

    /** 잠금 해제 수준(1). */
    UNLOCK,

    /** 주행 수준(2). */
    DRIVE,

    /** 알 수 없음(스키마 밖 값, 더 새 펌웨어). */
    UNKNOWN,
}

/** 차량이 인증을 요청한 이유(`VCSEC.AuthenticationReason_E`, TESLEMETRY-EXT, 이름 미확인). 모르는 값은 [UNKNOWN]. */
public enum class AuthenticationReason {
    /** 0. */
    NOT_DOCUMENTED,

    /** 1. */
    IDENTIFICATION,

    /** 2. */
    POWER_ON_VEHICLE_REQUEST,

    /** 3. */
    GTW_REQUEST,

    /** 4. */
    UI_UNLOCK_PASSIVE_AUTH,

    /** 5. 바깥 손잡이를 당김. */
    PASSIVE_UNLOCK_EXTERIOR_HANDLE_PULL,

    /** 6. 실내 손잡이를 당김(실차에서 확인). */
    PASSIVE_UNLOCK_INTERIOR_HANDLE_PULL,

    /** 7. */
    PASSIVE_UNLOCK_AUTOPRESENT_DOOR,

    /** 8. 더 높은 인증 구역에 들어옴(실차에서 충전구를 열고 충전을 시작할 때 확인). */
    ENTERED_HIGHER_AUTH_ZONE,

    /** 9. */
    WALK_UP_UNLOCK,

    /** 10. */
    IMMOBILIZER,

    /** 알 수 없음(스키마 밖 값, 더 새 펌웨어). */
    UNKNOWN,
}
