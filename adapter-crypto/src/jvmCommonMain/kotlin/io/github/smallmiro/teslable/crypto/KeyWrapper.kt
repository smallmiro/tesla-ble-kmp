package io.github.smallmiro.teslable.crypto

/** 소프트웨어 개인키를 감싸는 대칭 키(Android: Keystore AES-256-GCM `teslable.wrap:<alias>`). 결과는 `iv(12)‖ciphertext+tag`. */
internal interface KeyWrapper {
    fun wrap(plain: ByteArray): ByteArray

    /** 인증 실패·형식 오류면 null. */
    fun unwrap(wrapped: ByteArray): ByteArray?

    /** 감싸는 키를 지운다. 멱등. */
    fun destroy()
}
