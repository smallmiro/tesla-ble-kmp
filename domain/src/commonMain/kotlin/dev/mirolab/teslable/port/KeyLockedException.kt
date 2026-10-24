package dev.mirolab.teslable.port

/**
 * 개인키는 있지만 기기가 잠겨 지금은 쓸 수 없음(FR-037, ADR-0014, #96). [EcdhPrivateKey.sharedX]가 던진다 — 예: 잠기기 전에 얻은
 * iOS `WHEN_UNLOCKED` Secure Enclave 키 핸들로 잠긴 동안 ECDH를 하면 `SecKeyCopyKeyExchangeResult`가 `errSecInteractionNotAllowed`
 * (-25308)로 실패한다.
 *
 * 핸드셰이크(`Vehicle.startSession`)는 이것을 `Failure(VehicleError.KeyStoreError.Locked)`로 바꿔 다시 시도하지 않고 끝내며, 캐시 세션
 * 복원은 그 항목을 건너뛰되 저장된 캐시를 지우지 않는다. 키가 손상된 것이 아니므로 지우거나 새로 만들지 말고 잠금이 풀린 뒤 다시 시도한다.
 * 그 밖의 ECDH 실패(잘못된 공개키 등)는 이 예외가 아니다.
 */
public class KeyLockedException(
    message: String = "private key is not usable while the device is locked",
) : Exception(message)
