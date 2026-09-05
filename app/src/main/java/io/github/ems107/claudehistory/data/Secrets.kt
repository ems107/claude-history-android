package io.github.ems107.claudehistory.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The one secret this app stores: the password to each server.
 *
 * Encrypted with an AES key that is generated inside the phone's hardware-backed
 * keystore and can never be read out of it -- so the file on disk is useless on
 * its own, and there is nothing to leak in a backup (there is no backup: the
 * manifest says `allowBackup="false"`).
 *
 * Written by hand rather than with `androidx.security-crypto`, which is
 * deprecated and would be a poor thing to start a new project on. It is sixty
 * lines either way.
 *
 * No user authentication is required to use the key, deliberately: opening the
 * app must not ask for anything, so the servers you already configured are
 * simply there.
 */
object Secrets {
    private const val KEY_ALIAS = "claude-history-secrets"
    private const val TRANSFORM = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORM).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + body, Base64.NO_WRAP)
    }

    /**
     * Empty rather than an exception when it cannot be read. A key can genuinely
     * disappear -- app data cleared, a restored image, the lock screen removed on
     * some devices -- and the right answer to that is asking for the password
     * again, not a crash loop at startup.
     */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val all = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES))
            String(cipher.doFinal(all, IV_BYTES, all.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }
}
