package io.github.smallmiro.teslable.crypto

/**
 * AndroidKeyStore 별칭 네임스페이스. 접두어의 `:`는 사용자 별칭(`[A-Za-z0-9._-]`)에 올 수 없으므로
 * 사용자 별칭끼리, 호스트 앱의 다른 별칭과도 충돌하지 않는다.
 */
internal object KeystoreAliases {
    fun key(alias: String): String = "teslable.key:$alias"

    fun wrap(alias: String): String = "teslable.wrap:$alias"
}
