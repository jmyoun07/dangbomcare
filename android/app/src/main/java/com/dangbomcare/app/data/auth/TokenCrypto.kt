package com.dangbomcare.app.data.auth

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * [Codex H9 보안 검토] JWT 토큰 암호화 — Android Keystore 기반 AES-256-GCM.
 *
 * 키는 Android Keystore (TEE/StrongBox)에 저장 — 앱이 삭제돼도 키는 남고,
 * 다른 앱이나 루팅된 기기에서도 추출 불가능 (StrongBox 지원 기기는 하드웨어 격리).
 *
 * 저장 형식: Base64(IV[12바이트] + 암호문[AES-GCM] + 태그[16바이트])
 */
object TokenCrypto {

    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "dangdangcare_token_aes_v1"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12  // GCM 권장 IV 길이
    private const val TAG_LENGTH_BITS = 128

    /** Keystore에서 키 조회 — 없으면 생성. */
    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

        keyStore.getKey(KEY_ALIAS, null)?.let { return it as SecretKey }

        // 키 새로 생성
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            // 인증 없이 사용 (UI 잠금 화면 통과 없이 토큰 접근 가능해야 백그라운드 동기화 가능)
            .setUserAuthenticationRequired(false)
            .build()

        val keyGen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGen.init(spec)
        return keyGen.generateKey()
    }

    /** 평문 → IV+암호문 Base64. */
    fun encrypt(plaintext: String): String {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.ENCRYPT_MODE, key)
        }
        val iv = cipher.iv  // 시스템이 자동 생성한 안전한 IV
        val encrypted = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val combined = iv + encrypted  // [IV(12) | ciphertext+tag(16+)]
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    /** Base64 IV+암호문 → 평문. 실패 시 null. */
    fun decrypt(encoded: String): String? {
        return try {
            val combined = Base64.decode(encoded, Base64.NO_WRAP)
            if (combined.size <= IV_LENGTH) return null

            val iv = combined.copyOfRange(0, IV_LENGTH)
            val ciphertext = combined.copyOfRange(IV_LENGTH, combined.size)

            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            android.util.Log.w("TokenCrypto", "Decrypt failed — token will be cleared", e)
            null
        }
    }

    /** 키 자체를 폐기 (logout, 키 변경 등). */
    fun deleteKey() {
        try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) {
                keyStore.deleteEntry(KEY_ALIAS)
            }
        } catch (e: Exception) {
            android.util.Log.w("TokenCrypto", "Key deletion failed", e)
        }
    }
}
