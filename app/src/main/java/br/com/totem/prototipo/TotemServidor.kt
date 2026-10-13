package br.com.totem.prototipo

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

/**
 * Onde este aparelho guarda com qual servidor fala e o token recebido no pareamento.
 *
 * O token é a identidade do totem na API. Ele fica cifrado (AES-GCM) com uma chave
 * que mora no Android Keystore e não sai do aparelho; em SharedPreferences vai só o
 * texto cifrado. A página (HTML/JS) nunca recebe o token: quem chama o servidor é o
 * ApiBridge.
 *
 * Também guarda um hash (PBKDF2, com sal) do último PIN de diagnóstico aceito pelo
 * servidor, para o operador conseguir entrar no diagnóstico sem internet. O PIN
 * em si não é guardado.
 */
object TotemServidor {
    private const val TAG = "TotemServidor"
    private const val PREFS = "totem_servidor"
    private const val K_ENDERECO = "endereco"
    private const val K_TOKEN = "token"
    private const val K_IV = "token_iv"
    private const val ALIAS_CHAVE = "totem_token"
    private const val CIFRA = "AES/GCM/NoPadding"
    private const val K_PIN_HASH = "pin_hash"
    private const val K_PIN_SAL = "pin_sal"
    private const val PIN_ITERACOES = 60_000

    @Volatile
    private var tokenEmMemoria: String? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Endereço do servidor, sem barra no fim. Antes do pareamento vale o de gradle.properties (totem.api). */
    fun endereco(context: Context): String =
        (prefs(context).getString(K_ENDERECO, null)?.takeIf { it.isNotBlank() } ?: BuildConfig.API_URL)
            .trim().trimEnd('/')

    fun token(context: Context): String? {
        tokenEmMemoria?.let { return it }
        val p = prefs(context)
        val cifrado = p.getString(K_TOKEN, null) ?: return null
        val iv = p.getString(K_IV, null) ?: return null
        return try {
            val cipher = Cipher.getInstance(CIFRA)
            cipher.init(Cipher.DECRYPT_MODE, chave(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(cifrado, Base64.NO_WRAP)), Charsets.UTF_8)
                .also { tokenEmMemoria = it }
        } catch (e: Exception) {
            // Chave perdida (ex.: dados do app restaurados em outro aparelho): precisa parear de novo.
            Log.w(TAG, "Não foi possível ler o token guardado", e)
            null
        }
    }

    /** Guarda o resultado do pareamento. */
    fun salvar(context: Context, endereco: String, token: String) {
        val cipher = Cipher.getInstance(CIFRA)
        cipher.init(Cipher.ENCRYPT_MODE, chave())
        val cifrado = cipher.doFinal(token.toByteArray(Charsets.UTF_8))
        prefs(context).edit()
            .putString(K_ENDERECO, endereco.trim().trimEnd('/'))
            .putString(K_TOKEN, Base64.encodeToString(cifrado, Base64.NO_WRAP))
            .putString(K_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
        tokenEmMemoria = token
    }

    /** Esquece o token (e o PIN guardado, que era da loja deste pareamento). O endereço do servidor fica. */
    fun apagarToken(context: Context) {
        tokenEmMemoria = null
        prefs(context).edit().remove(K_TOKEN).remove(K_IV).remove(K_PIN_HASH).remove(K_PIN_SAL).apply()
    }

    /** Guarda o hash do PIN que o servidor acabou de aceitar. */
    fun salvarPin(context: Context, pin: String) {
        val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
        prefs(context).edit()
            .putString(K_PIN_HASH, Base64.encodeToString(hashPin(pin, sal), Base64.NO_WRAP))
            .putString(K_PIN_SAL, Base64.encodeToString(sal, Base64.NO_WRAP))
            .apply()
    }

    /** Confere o PIN sem internet. null = nenhum PIN guardado neste aparelho. */
    fun conferirPin(context: Context, pin: String): Boolean? {
        val p = prefs(context)
        val hash = p.getString(K_PIN_HASH, null) ?: return null
        val sal = p.getString(K_PIN_SAL, null) ?: return null
        return MessageDigest.isEqual(
            Base64.decode(hash, Base64.NO_WRAP),
            hashPin(pin, Base64.decode(sal, Base64.NO_WRAP)),
        )
    }

    /** O servidor disse que o PIN mudou ou deixou de existir: o guardado não vale mais. */
    fun apagarPin(context: Context) {
        prefs(context).edit().remove(K_PIN_HASH).remove(K_PIN_SAL).apply()
    }

    private fun hashPin(pin: String, sal: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), sal, PIN_ITERACOES, 256)
        return try {
            // HmacSHA256 existe a partir do Android 8 (API 26); o app aceita desde a 24.
            val fabrica = try {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            } catch (e: java.security.NoSuchAlgorithmException) {
                SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1")
            }
            fabrica.generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    private fun chave(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(ALIAS_CHAVE, null) as? SecretKey)?.let { return it }

        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(
            KeyGenParameterSpec.Builder(
                ALIAS_CHAVE,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return gerador.generateKey()
    }
}
