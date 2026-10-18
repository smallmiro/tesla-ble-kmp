package dev.mirolab.teslable.model

/**
 * 공개키 하나에 대한 차량 도메인의 세션정보 요약(FR-024, Go `signatures.SessionInfo`). `Vehicle.sessionInfo`가 `status == OK`일 때만 돌려준다
 * (`KEY_NOT_ON_WHITELIST`는 `Failure(KeyNotPaired)`). 페어링 뒤 Infotainment 동기화를 기다리는 데 쓴다.
 */
public data class SessionInfoSummary(
    /** 차량이 마지막으로 본 counter. */
    val counter: UInt,
    /** 차량 시계(epoch 시작부터의 초). */
    val clockTime: UInt,
    /** 차량 도메인 공개키. 형식이 틀리면 null. */
    val vehiclePublicKey: PublicKeyBytes?,
)
