package dev.gpssync.for_tesla_probe

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class TeslaAuthSettings(context: Context, name: String = "tesla_auth") : TeslaAuthStore {
    companion object {
        private val lock = Any()
        fun jwtExpiry(token: String): Long = runCatching {
            val payload = token.split('.')[1]
            JSONObject(String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)).optLong("exp").let {
                if (it in 1..9_000_000_000L) it * 1000 else 0L
            }
        }.getOrDefault(0L)
    }
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
    private val alias = "${name}_aes_v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun load(): StoredTeslaAuth = synchronized(lock) {
        val revision = prefs.getString("revision", "empty").orEmpty()
        val encoded = prefs.getString("encrypted", null) ?: return@synchronized StoredTeslaAuth(TeslaAuthData(), revision)
        try {
            val parts = encoded.split(':')
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)))
            StoredTeslaAuth(TeslaAuthData.fromJson(JSONObject(String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), Charsets.UTF_8))), revision)
        } catch (_: Exception) { error("저장된 TeslaAuth 정보를 읽지 못했습니다. 기기 키 상태를 확인하거나 Clear 후 다시 로그인하세요.") }
    }
    fun save(data: TeslaAuthData) = synchronized(lock) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(cipher.doFinal(data.json().toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        check(prefs.edit().putString("encrypted", encrypted).putString("revision", UUID.randomUUID().toString()).commit()) { "TeslaAuth 저장 실패" }
    }
    override fun replaceIfCurrent(revision: String, data: TeslaAuthData): Boolean = synchronized(lock) {
        if (prefs.getString("revision", "empty") != revision) return@synchronized false
        save(data); true
    }
    fun clear() = synchronized(lock) {
        check(prefs.edit().remove("encrypted").putString("revision", UUID.randomUUID().toString()).commit()) { "TeslaAuth 삭제 실패" }
    }
}
