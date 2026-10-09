package com.hexnc.music.data.netease

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Host-side verification of the request crypto.
 *
 * Every expected value here was computed **independently** of this codebase, with .NET's
 * `System.Security.Cryptography` and `System.Numerics.BigInteger` on the host. That matters: the
 * original `toHexLower`/`toHexUpper` were written as `buildString { forEach { ... } }`, which inside
 * `buildString` resolved `forEach` to `CharSequence.forEach` over the *empty* builder, so both
 * returned `""`. A test that merely asserted "output is not blank" would have been weaker than
 * these fixed vectors, and the compiler only hinted at it with a `Char.toInt()` deprecation warning.
 */
class NeteaseCryptoTest {

    @Test
    fun `md5 matches known vectors`() {
        assertEquals("d41d8cd98f00b204e9800998ecf8427e", NeteaseCrypto.md5Hex(""))
        assertEquals("900150983cd24fb0d6963f7d28e17f72", NeteaseCrypto.md5Hex("abc"))
        // Non-ASCII must be hashed as UTF-8 bytes, not platform default.
        assertEquals("1a17aec70bd7d64faafb3f894b1491ee", NeteaseCrypto.md5Hex("网易云"))
    }

    @Test
    fun `md5 of string and of its utf8 bytes agree`() {
        val text = "HexNC / 极光"
        assertEquals(NeteaseCrypto.md5Hex(text.toByteArray(Charsets.UTF_8)), NeteaseCrypto.md5Hex(text))
    }

    @Test
    fun `aes cbc base64 matches host vector`() {
        // AES-128-CBC/PKCS7, key "0CoJUm6Qyw8W8jud", IV "0102030405060708".
        assertEquals(
            "eHhjXckqrtZkqcwCalCMx0QuU6Lj9L7Wxouw1iMCnB4=",
            NeteaseCrypto.aesCbcBase64("{\"csrf_token\":\"\"}".toByteArray(Charsets.UTF_8), "0CoJUm6Qyw8W8jud")
        )
    }

    @Test
    fun `aes ecb hex upper matches host vector`() {
        assertEquals(
            "3329CABA8FB561FF0086A5823D14F529",
            NeteaseCrypto.aesEcbHexUpper("hello".toByteArray(Charsets.UTF_8), "e82ckenh8dichen8")
        )
    }

    @Test
    fun `hex helpers never return empty for non-empty input`() {
        // Direct regression guard for the silent-empty-hex bug.
        assertTrue(NeteaseCrypto.md5Hex(byteArrayOf(1)).length == 32)
        assertTrue(NeteaseCrypto.aesEcbHexUpper(byteArrayOf(1), "e82ckenh8dichen8").isNotEmpty())
        assertTrue(NeteaseCrypto.aesEcbHexUpper(byteArrayOf(1), "e82ckenh8dichen8").length == 32)
    }

    @Test
    fun `weapi produces the double-cbc params and the rsa-wrapped key`() {
        val out = NeteaseCrypto.weapi("{\"csrf_token\":\"\"}", secretKey = "0123456789abcdef")
        // Second pass encrypts the *bytes* of the first Base64 string, not the string object.
        assertEquals("echuH06dDs7OxMA2egejtvWuTbLs/0W4Iar5ZYYlrHZMs++29jrOmUM2kZbJQK22", out.getValue("params"))
        assertEquals(EXPECTED_ENC_SEC_KEY, out.getValue("encSecKey"))
    }

    @Test
    fun `rsa encryption matches the reference algorithm for several keys`() {
        // The reference (`pyncm`, `NeteaseCloudMusicApi`) does:
        //   int(hex_encode(reverse(secret).utf8_bytes), 16) ** 0x10001 mod n
        // i.e. the key's *characters* are hex-encoded before the big-integer conversion. Parsing the
        // reversed key as hex would be wrong, and it is a mistake that silently produces a
        // well-formed but rejected `encSecKey`.
        assertEquals(EXPECTED_ENC_SEC_KEY, NeteaseCrypto.rsaEncrypt("0123456789abcdef"))
        assertEquals(
            "d473b9eca232f1b4090dd606b0df86de318748dd2eec307e4ed4345030fc4ee3" +
                "0331e598f41d5a6f5befaab94630ea1a1eda7cfade84fbec1a907913d2e4d2c8" +
                "744bc572b99a050075e075b4537f645ecfa994f95906c32818e076aeda6bdb90" +
                "6bfa0bb96c4cf4bc3ed6d9ab76cf08441153d9d85e1ea3d78fa8d9210d581cee",
            NeteaseCrypto.rsaEncrypt("aaaaaaaaaaaaaaaa")
        )
        // Base62 keys, which is what `randomSecretKey` actually hands it.
        assertEquals(
            "43f62ece9a6779ee8087a3043194f05f64139356034a3565050fca9ad8623e63" +
                "e4c14546d070034c0ce6d044568324d3b4078ae84fd4eba4f4dd24f225bbdfe4" +
                "77b6a781f94b3db14d517efa3134dfc50c94694eafa11021bad5b33a8ffc2845" +
                "af01138233ab885b267241750af6d984d3a560ee921778eb9188207b57d6a77e",
            NeteaseCrypto.rsaEncrypt("Zz9Qw8Er7Ty1Ui2O")
        )
        // A 1024-bit modulus must always yield exactly 256 hex digits; a shorter result means a
        // leading zero was dropped, which the NetEase server would reject as a malformed key.
        for (secret in listOf("0123456789abcdef", "aaaaaaaaaaaaaaaa", "Zz9Qw8Er7Ty1Ui2O")) {
            assertEquals(256, NeteaseCrypto.rsaEncrypt(secret).length)
        }
    }

    @Test
    fun `eapi signs and encrypts the route end to end`() {
        // Digest is md5("nobody" + url + "use" + json + "md5forencrypt"); the plaintext is
        // url + "-36cd479b6b5-" + json + "-36cd479b6b5-" + digest, AES-128-ECB with the eapi key.
        assertEquals(
            "FA90B329E9614F79E79598F37DC2EDB487F00D1BC4C9B24CD57E6C318B907356" +
                "9338432CD7D98D1A3626E997A2C53121F2652556B9CAA141000D39A0C1D1AE1" +
                "276E3600FCF48082F7762AEC1A9E148CEC7486917199AA4D98BE55190FC45699" +
                "FD89064D0C2E84D60CDA6397B1B219EE873F9952249F4C2D241CB3F7CFE99E88" +
                "C68585430AA0061813BE0B821F83D6808",
            NeteaseCrypto.eapi(
                "/api/song/enhance/player/url/v1",
                "{\"ids\":\"[1]\",\"level\":\"standard\",\"encodeType\":\"aac\"}"
            )
        )
    }

    @Test
    fun `eapi digest is bound to the route`() {
        val json = "{\"csrf_token\":\"\"}"
        assertTrue(
            NeteaseCrypto.eapi("/api/song/lyric", json) != NeteaseCrypto.eapi("/api/song/enhance/player/url/v1", json)
        )
    }

    @Test
    fun `rsa output is 256 lowercase hex chars and key-dependent`() {
        val a = NeteaseCrypto.rsaEncrypt("0123456789abcdef")
        val b = NeteaseCrypto.rsaEncrypt("fedcba9876543210")
        assertEquals(256, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue(a != b)
    }

    @Test
    fun `random secret key is 16 chars from the base62 alphabet`() {
        repeat(64) {
            val key = NeteaseCrypto.randomSecretKey()
            assertEquals(16, key.length)
            assertTrue(key.all { c -> c.isDigit() || c in 'a'..'z' || c in 'A'..'Z' })
        }
    }

    @Test
    fun `weapi defaults to a fresh random key per call`() {
        val a = NeteaseCrypto.weapi("{}")
        val b = NeteaseCrypto.weapi("{}")
        // Different random keys must yield different wrapped keys and different params.
        assertTrue(a.getValue("encSecKey") != b.getValue("encSecKey"))
        assertTrue(a.getValue("params") != b.getValue("params"))
    }

    private companion object {
        /** rsaEncrypt("0123456789abcdef") per the reference algorithm, computed on the host. */
        const val EXPECTED_ENC_SEC_KEY =
            "35701388baf89fed412e11269b9c76625d095ecaf17f03fa018abe19ea2d38b9" +
                "49debf242ee39a71ca1f6cda71b1b86a45aa909ee27f7e78e267d34e732f0de9" +
                "48206c3340a788d0003372183e2f753c1f78b66ac23d134ac1fc9b993156520e" +
                "a826b8aa89a962d4491b4b8d7e08738e1da9b07aa39bf4a7ef0b1c210728cd52"
    }
}
