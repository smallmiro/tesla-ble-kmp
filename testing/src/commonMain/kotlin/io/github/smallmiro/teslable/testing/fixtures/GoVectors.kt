package io.github.smallmiro.teslable.testing.fixtures

/**
 * `vehicle-command@a4b43c1 internal/authentication/verifier_test.go TestGCMKnown`의 상수.
 * Go `Signer`가 만든 실제 메시지를 우리 검증자가 복호화하는 상호운용 KAT. 실차 키가 아니다.
 */
public object GoVectors {
    /** `TestGCMKnown` `encodedMessage` (RoutableMessage, 172바이트). */
    public const val GCM_KNOWN_MESSAGE: String =
        "320208023a0208005259296d587d130d34d74d6d5c628c73c1f8ef99cc4ae1c95b9767" +
            "1474980ccc7946a90a2f7971a5c837b96c9cc1e8075d1aab92bc8557b7fdf2dcf9ddbb" +
            "90e236246db699788e585e8b0ea84752e0090cc80c4384d27ca6fcdd216a470a06120" +
            "4bb0ca3712a3d0a10eaabe301b4b4a1243118a40825220115120c45d29af664e2ff8f" +
            "d49218b718ffffffff0f2550c300002a106d846705ba5c143f94257275a2ca701f"

    /** `TestGCMKnown` `encodedPublicKey` — 메시지를 서명한(클라이언트) 공개키. */
    public const val GCM_KNOWN_SIGNER_PUBLIC_KEY: String =
        "0450444fabe062f6ffc59de654373e1aa84aa4f053f365f3746fbaaa8ad5d5875e827" +
            "9ba379b4788e9148f5000bd1fe085d18925e8d047239cfa0d9cd23417b714"

    /** `TestGCMKnown` `privateScalar` — 검증자(차량) 개인 스칼라. */
    public const val GCM_KNOWN_VERIFIER_SCALAR: String = "9d9c5e86d5a83057965ca94b5faeff116c275d08ca91e839bd2dea59c2229b81"

    /** 위 스칼라의 P-256 공개점 (uncompressed). 오프라인에서 곱셈으로 유도했고, 테스트가 sharedX로 간접 검증한다. */
    public const val GCM_KNOWN_VERIFIER_PUBLIC_KEY: String =
        "041431dc57c892b8dcc87ba59159031e12046b6febcdfb88f7b9c9e2a0fc2b7a1e323" +
            "a4bd5a5696f103845bd1c4b9dd104d0fe87a3721314a03a7550406445705b"

    /** `TestGCMKnown` `testEpoch`. */
    public const val GCM_KNOWN_EPOCH: String = "eaabe301b4b4a1243118a40825220115"

    /** `TestGCMKnown` `testVin` — 17자 VIN이 아니라 임의 personalization 바이트 `"testvin"`. */
    public const val GCM_KNOWN_PERSONALIZATION: String = "testvin"

    /** `TestGCMKnown` `expectedPlaintext` (89바이트 = 178 hex 문자). */
    public const val GCM_KNOWN_PLAINTEXT: String =
        "82014a2a480a430a41044b96809e66821b4553ff2b427a52d50843a2f718fd56f2ad9" +
            "3d46a8b1db74dd6190f20b6b82e85a8eac40e867b9a0cf74f364f03e9946e25ceee9" +
            "934b19cb603120102ca01090a077465737476696e"
}
