package rida.pour.les.pros.data.repo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/** Stockage chiffré des secrets (clé API) : clé AES-GCM dans l'Android Keystore, jamais exportable. */
interface SecretProvider {
    fun apiKey(): String?
}

@Singleton
class SecretStore @Inject constructor(@ApplicationContext context: Context) : SecretProvider {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private val _hasApiKey = MutableStateFlow(prefs.contains(API_KEY))
    val hasApiKey: StateFlow<Boolean> = _hasApiKey

    override fun apiKey(): String? = read(API_KEY)

    fun setApiKey(value: String) {
        val v = value.trim()
        require(v.isNotEmpty()) { "Clé vide." }
        write(API_KEY, v)
        _hasApiKey.value = true
    }

    fun clearApiKey() {
        prefs.edit().remove(API_KEY).apply()
        _hasApiKey.value = false
    }

    /** Clé masquée pour l'affichage (ex. sk-ant-…a1b2). */
    fun maskedApiKey(): String? = apiKey()?.let { if (it.length > 12) it.take(7) + "…" + it.takeLast(4) else "…" }

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private fun write(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val stored = b64(cipher.iv) + ":" + b64(ct)
        prefs.edit().putString(name, stored).apply()
    }

    private fun read(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val (iv, ct) = stored.split(':', limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(iv)))
            String(cipher.doFinal(unb64(ct)), Charsets.UTF_8)
        } catch (_: Exception) {
            null // clé Keystore perdue (restauration, réinitialisation) : il faudra ressaisir la clé API
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "rida_secrets_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val API_KEY = "anthropic_api_key"
    }
}
