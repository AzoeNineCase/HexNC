package com.hexnc.music.data.netease

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * NetEase Cloud Music request encryption.
 *
 * Two schemes are implemented because the API is inconsistent about which one a route accepts:
 *
 *  - **weapi** (`https://music.163.com/weapi/...`) — payload is AES-128-CBC encrypted twice, first
 *    with a well-known constant key then with a per-request random key that is itself RSA-encrypted
 *    into `encSecKey`. The ciphertext is Base64.
 *  - **eapi** (`https://interface.music.163.com/eapi/...`) — payload is a digest-bound string
 *    AES-128-ECB encrypted once with a constant key and returned as uppercase hex. This is what the
 *    desktop client uses and what several routes (player URL v1, lyric, cloud upload) expect.
 *
 * Everything here is a pure function of its inputs so it can be unit-tested without a device.
 */
object NeteaseCrypto {

    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val EAPI_KEY = "e82ckenh8dichen8"
    private val IV = "0102030405060708".toByteArray(Charsets.UTF_8)
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** NetEase's fixed 1024-bit RSA modulus, used verbatim from `core.js`. */
    private const val RSA_MODULUS_HEX =
        "e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e41" +
            "7629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee25593" +
            "2575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"

    private const val RSA_EXPONENT_HEX = "010001"

    private val random = SecureRandom()

    /** The per-request secret key NetEase's own web client generates. */
    fun randomSecretKey(): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(BASE62[random.nextInt(BASE62.length)]) }
        return sb.toString()
    }

    /**
     * Build the `params` + `encSecKey` form body for a weapi route.
     *
     * @param json already-serialised request object.
     */
    fun weapi(json: String, secretKey: String = randomSecretKey()): Map<String, String> {
        val once = aesCbcBase64(json.toByteArray(Charsets.UTF_8), PRESET_KEY)
        // Second pass encrypts the *bytes* of the first Base64 string, not the string itself.
        val twice = aesCbcBase64(once.toByteArray(Charsets.UTF_8), secretKey)
        return mapOf(
            "params" to twice,
            "encSecKey" to rsaEncrypt(secretKey)
        )
    }

    /**
     * Build the `params` hex string for an eapi route.
     *
     * @param url the *path portion* of the route, e.g. `/api/song/enhance/player/url/v1`. It is part
     *   of the signed digest, so it must match the request path exactly.
     */
    fun eapi(url: String, json: String): String {
        val message = "nobody$url" + "use" + json + "md5forencrypt"
        val digest = md5Hex(message)
        val data = "$url-36cd479b6b5-$json-36cd479b6b5-$digest"
        return aesEcbHexUpper(data.toByteArray(Charsets.UTF_8), EAPI_KEY)
    }

    /**
     * Encrypt the eapi payload for the "linux" forward endpoint (`/api/linux/forward`), which wraps
     * the real route inside an envelope.
     */
    fun eapiEnvelope(url: String, json: String): String = eapi(url, json)

    /** `MUSIC_A`-style anonymous token helper: the fixed key NetEase uses for anonymous requests. */
    fun anonymousUsername(deviceId: String): String {
        val raw = deviceId + " " + md5Hex(deviceId + " " + "6cd47dd5df5c88b30ac2e0b4f26f0bdd")
        return Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
    }

    // ---------------------------------------------------------------- primitives

    fun md5Hex(text: String): String =
        md5Hex(text.toByteArray(Charsets.UTF_8))

    fun md5Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(bytes)
        return digest.toHexLower()
    }

    fun aesCbcBase64(plain: ByteArray, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV)
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(plain))
    }

    fun aesEcbHexUpper(plain: ByteArray, key: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"))
        return cipher.doFinal(plain).toHexUpper()
    }

    /**
     * Textbook `m^e mod n` RSA. NetEase's key is the *reversed* secret key interpreted as a big-endian
     * integer, and the result is left-zero-padded to the 256 hex characters of a 1024-bit modulus.
     */
    fun rsaEncrypt(secretKey: String): String {
        val reversed = secretKey.reversed()
        val text = BigInteger(1, reversed.toByteArray(Charsets.UTF_8))
        val exponent = BigInteger(RSA_EXPONENT_HEX, 16)
        val modulus = BigInteger(RSA_MODULUS_HEX, 16)
        val encrypted = text.modPow(exponent, modulus)
        return encrypted.toString(16).padStart(256, '0')
    }

    // NOTE: the loop must iterate `this` explicitly. Inside `buildString` the implicit receiver is
    // the StringBuilder, so a bare `forEach` would resolve to `CharSequence.forEach` and walk the
    // (empty) builder instead of the bytes — silently producing an empty hex string.
    private fun ByteArray.toHexLower(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            out.append(HEX[(b.toInt() shr 4) and 0xF])
            out.append(HEX[b.toInt() and 0xF])
        }
        return out.toString()
    }

    private fun ByteArray.toHexUpper(): String {
        val out = StringBuilder(size * 2)
        for (b in this) {
            out.append(HEX_UPPER[(b.toInt() shr 4) and 0xF])
            out.append(HEX_UPPER[b.toInt() and 0xF])
        }
        return out.toString()
    }

    private const val HEX = "0123456789abcdef"
    private const val HEX_UPPER = "0123456789ABCDEF"
}
