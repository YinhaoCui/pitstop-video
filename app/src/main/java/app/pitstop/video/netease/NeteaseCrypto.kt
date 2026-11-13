package app.pitstop.video.netease

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NetEase Cloud Music "weapi" request encryption (what music.163.com's own web player does):
 * the JSON body is AES-128-CBC encrypted twice (fixed key, then a random 16-char key), and the
 * random key is RSA-encrypted (textbook, reversed) for the server. Pure JVM, unit-tested.
 */
object NeteaseCrypto {
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private val MODULUS = BigInteger(
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7",
        16,
    )
    private val EXPONENT = BigInteger("010001", 16)
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    private val random = SecureRandom()

    data class Weapi(val params: String, val encSecKey: String)

    fun weapi(json: String, secretKey: String = randomKey()): Weapi {
        val once = aes(json, PRESET_KEY)
        return Weapi(aes(once, secretKey), rsa(secretKey))
    }

    fun randomKey(): String = String(CharArray(16) { ALPHABET[random.nextInt(ALPHABET.length)] })

    /** AES-128-CBC, PKCS#7 (Java calls it PKCS5), Base64 output. */
    fun aes(text: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(IV.toByteArray()))
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    fun aesDecrypt(base64: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(IV.toByteArray()))
        return String(cipher.doFinal(Base64.getDecoder().decode(base64)), Charsets.UTF_8)
    }

    /** No padding: m = reversed key as a big-endian number; c = m^e mod n as 256 hex digits. */
    fun rsa(secretKey: String): String {
        val m = BigInteger(1, secretKey.reversed().toByteArray())
        return m.modPow(EXPONENT, MODULUS).toString(16).padStart(256, '0')
    }
}
