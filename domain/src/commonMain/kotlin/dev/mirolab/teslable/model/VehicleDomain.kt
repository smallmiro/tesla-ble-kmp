package dev.mirolab.teslable.model

import com.tesla.generated.universalmessage.Domain

/** 공개 API의 차량 도메인(SDD §2.1). 내부는 Wire [Domain]을 쓴다(M2 설계 구체화 3). */
public enum class VehicleDomain(
    /** 대응하는 Wire 도메인. */
    public val wire: Domain,
) {
    /** 차량 보안(VCSEC): 잠금, 키, 차체 상태. */
    VCSEC(Domain.DOMAIN_VEHICLE_SECURITY),

    /** 인포테인먼트: 공조, 충전, 상태 조회. */
    INFOTAINMENT(Domain.DOMAIN_INFOTAINMENT),
    ;

    /** 조회. */
    public companion object {
        /** 두 도메인 전부. */
        public val ALL: Set<VehicleDomain> = entries.toSet()

        /** Wire 도메인에 대응하는 값. 공개 도메인이 아니면 null. */
        public fun of(domain: Domain): VehicleDomain? = entries.firstOrNull { it.wire == domain }
    }
}
