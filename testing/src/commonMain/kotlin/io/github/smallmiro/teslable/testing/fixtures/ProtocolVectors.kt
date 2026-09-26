package io.github.smallmiro.teslable.testing.fixtures

/**
 * `vehicle-command/pkg/protocol/protocol.md` 의 테스트 키와 벡터 (03-protocol.md §6~§8, 10-porting-guide.md §0.3).
 * 개인키가 공개된 키이므로 실차에 등록하지 않는다. 모든 값은 hex 문자열.
 */
public object ProtocolVectors {
    public const val VIN: String = "5YJ30123456789ABC"
    public const val LOCAL_NAME_VIN: String = "5YJS0000000000000"
    public const val LOCAL_NAME: String = "S1a87a5a75f3df858C"

    public const val CLIENT_PRIVATE_SCALAR: String = "2538cdc29a97c19c1e99a637d6cf4f8c970c118b56ede1e6323e6d162c4b30db"
    public const val CLIENT_PUBLIC_KEY: String =
        "04b2b6bc68c2da0665ce656815594996c62394edd8bea905fe781a754fe6a845a7" +
            "14330902f225e9269d466e05b349981fda9d85cc23c6fb444aa73b629105dc6e"
    public const val VEHICLE_PRIVATE_SCALAR: String = "344ee5b466a7cf1eeb12b6f50331db2e5ec5834ef5f4befcfd8cbe55c2528d70"
    public const val VEHICLE_PUBLIC_KEY: String =
        "04c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
            "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97"

    public const val SHARED_KEY_K: String = "1b2fce19967b79db696f909cff89ea9a"
    public const val SESSION_INFO_KEY: String = "fceb679ee7bca756fcd441bf238bf2f338629b41d9eb9c67be1b32c9672ce300"

    /** 핸드셰이크 응답의 session_info (Signatures.SessionInfo: counter 6, vehicle pub, epoch, clock_time 2650) */
    public const val SESSION_INFO: String =
        "0806124104c7a1f47138486aa4729971494878d33b1a24e39571f748a6e16c5955b3d877d3" +
            "a6aaa0e955166474af5d32c410f439a2234137ad1bb085fd4e8813c958f11d97" +
            "1a104c463f9cc0d3d26906e982ed224adde6255a0a0000"
    public const val EPOCH: String = "4c463f9cc0d3d26906e982ed224adde6"
    public const val CLOCK_TIME: Int = 2650
    public const val COUNTER: Int = 6
    public const val CHALLENGE: String = "1588d5a30eabc6f8fc9a951b11f6fd11"
    public const val HANDSHAKE_METADATA: String =
        "000106021135594a333031323334353637383941424306101588d5a30eabc6f8fc9a951b11f6fd11ff"
    public const val SESSION_INFO_TAG: String = "996c1fe38331be138f8039c194b14db2198846ed7d8251e6749284d7b32ea002"

    /** "Turn HVAC on" 예시 (flags = 2). protocol_doc_test.go 와 동일한 정본. */
    public const val HVAC_ON_PLAINTEXT: String = "120452020801"
    public const val HVAC_EXPIRES_AT: Int = 2655
    public const val HVAC_COUNTER: Int = 7
    public const val HVAC_METADATA_FLAGS2: String =
        "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6" +
            "040400000a5f050400000007070400000002ff"
    public const val HVAC_NONCE: String = "dbf79447fa156674dae1caed"
    public const val HVAC_CIPHERTEXT: String = "38038e8c0f2e"
    public const val HVAC_TAG_FLAGS2: String = "c228e0ff64991481db3a7bbc133696c5"

    /** PoC 보조 케이스: flags = 0 이면 TAG_FLAGS 생략 (reference/poc/PocTest.kt) */
    public const val HVAC_METADATA_FLAGS0: String =
        "000105010103021135594a333031323334353637383941424303104c463f9cc0d3d26906e982ed224adde6" +
            "040400000a5f050400000007ff"
    public const val HVAC_TAG_FLAGS0: String = "8e128da165f162f4d7d2c8da866cf82a"

    /** tesla-control -ble -debug list-keys 의 TX (03-protocol.md §15). uuid(51)가 payload(10)보다 앞에 있음에 주의. */
    public const val LIST_KEYS_TX: String =
        "320208023a1212100a7962c10d38b61dd2a7722780a4f0969a031005514f57616bcc81a8ce0f9d7b48322952040a020805"
    public const val LIST_KEYS_ROUTING_ADDRESS: String = "0a7962c10d38b61dd2a7722780a4f096"
    public const val LIST_KEYS_UUID: String = "05514f57616bcc81a8ce0f9d7b483229"
    public const val LIST_KEYS_PAYLOAD: String = "0a020805"

    /** Go metadata_test.go TestCheckSum */
    public const val GO_CHECKSUM_EPOCH: String = "aada928a4f215f55f9e6e45e66b6521e"
    public const val GO_CHECKSUM_SHA256: String = "abab04d804499813382efd74a06791ce2de777439603246dfbaa8392ca05868e"
}
