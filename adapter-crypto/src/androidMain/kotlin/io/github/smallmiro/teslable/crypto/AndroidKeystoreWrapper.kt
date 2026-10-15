package io.github.smallmiro.teslable.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private const val ANDROID_KEY_STORE = "AndroidKeyStore"
private const val IV_SIZE = 12
private const val TAG_BITS = 128
private const val AES_BITS = 256

/** Keystore AES-256-GCM 키(`teslable.wrap:<alias>`)로 감싼다. 키는 Keystore 밖으로 나가지 않는다. */
internal class AndroidKeystoreWrapper(
    alias: String,
) : KeyWrapper {
    private val wrapAlias = KeystoreAliases.wrap(alias)
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }

    private fun key(): SecretKey =
        (keyStore.getKey(wrapAlias, null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE).run {
                init(
                    KeyGenParameterSpec
                        .Builder(wrapAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(AES_BITS)
                        .build(),
                )
                generateKey()
            }

    override fun wrap(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) } // Keystore가 IV를 만든다
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray? {
        if (wrapped.size <= IV_SIZE) return null
        val existing = keyStore.getKey(wrapAlias, null) as? SecretKey ?: return null
        return try {
            Cipher
                .getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, existing, GCMParameterSpec(TAG_BITS, wrapped.copyOfRange(0, IV_SIZE))) }
                .doFinal(wrapped.copyOfRange(IV_SIZE, wrapped.size))
        } catch (
            @Suppress("SwallowedException") e: java.security.GeneralSecurityException,
        ) {
            null // 인증 실패 = 손상(설계 메모)
        }
    }

    override fun destroy() {
        if (keyStore.containsAlias(wrapAlias)) keyStore.deleteEntry(wrapAlias)
    }
}
