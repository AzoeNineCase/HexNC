package com.hexnc.music.data.netease

import com.hexnc.music.data.repo.QrPoll
import com.hexnc.music.data.repo.QrStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression cover for the risk-control gate that broke QR sign-in.
 *
 * NetEase answered `-462` (a captcha challenge) to the QR poll, but the app only understood
 * 800/801/802/803, so an unrecognised code fell through to "waiting" and the screen polled forever
 * before ending on a misleading "QR expired". These tests pin the two halves of the fix: the code
 * mapping and the payload that carries the challenge URL.
 */
class NeteaseLoginGateTest {

    private val json = NeteaseJson

    @Test
    fun `maps every documented qr poll code`() {
        assertEquals(QrStage.WAITING, QrStage.fromCode(801))
        assertEquals(QrStage.SCANNED, QrStage.fromCode(802))
        assertEquals(QrStage.AUTHORISED, QrStage.fromCode(803))
        assertEquals(QrStage.EXPIRED, QrStage.fromCode(800))
        assertEquals(QrStage.VERIFY_REQUIRED, QrStage.fromCode(QrStage.RISK_CONTROL))
        assertEquals(-462, QrStage.RISK_CONTROL)
    }

    @Test
    fun `an unknown code stops the loop instead of polling forever`() {
        // The old behaviour mapped everything unrecognised to WAITING, which is what produced the
        // endless spinner. Any unknown code must be terminal.
        assertEquals(QrStage.EXPIRED, QrStage.fromCode(0))
        assertEquals(QrStage.EXPIRED, QrStage.fromCode(-1))
        assertEquals(QrStage.EXPIRED, QrStage.fromCode(200))
    }

    @Test
    fun `decodes the real risk-control qr poll payload`() {
        val payload = """
            {"code":-462,"message":"请完成验证操作","data":{
            "verifyType":40,"verifyId":1007602,
            "verifyUrl":"https://st.music.163.com/encrypt-pages",
            "blockText":"验证成功后，可进行下一步操作哦~",
            "verifyToken":"00.40.1a2b3c4d.1700000000"}}
        """.trimIndent()
        val response = json.decodeFromString<NcQrPollResponse>(payload)
        assertEquals(-462, response.code)
        assertEquals(QrStage.VERIFY_REQUIRED, QrStage.fromCode(response.code))
        assertEquals("请完成验证操作", response.message)
        val challenge = response.data
        assertEquals(40, challenge?.verifyType)
        assertEquals(1007602L, challenge?.verifyId)
        assertEquals("https://st.music.163.com/encrypt-pages", challenge?.challengeUrl)
        assertEquals("验证成功后，可进行下一步操作哦~", challenge?.blockText)
    }

    @Test
    fun `a blank or absent verify url yields no challenge link`() {
        assertNull(
            json.decodeFromString<NcQrPollResponse>("""{"code":-462}""").data?.challengeUrl,
        )
        assertNull(
            json.decodeFromString<NcQrPollResponse>(
                """{"code":-462,"data":{"verifyType":40,"verifyUrl":"   "}}""",
            ).data?.challengeUrl,
        )
    }

    @Test
    fun `a healthy poll carries no challenge`() {
        val response = json.decodeFromString<NcQrPollResponse>("""{"code":801}""")
        assertEquals(QrStage.WAITING, QrStage.fromCode(response.code))
        assertNull(response.data)
    }

    @Test
    fun `decodes the risk-control password login payload`() {
        val payload = """
            {"code":-462,"message":"请完成验证操作","msg":null,"account":null,
            "data":{"verifyType":40,"verifyId":1007602,
            "verifyUrl":"https://st.music.163.com/encrypt-pages"}}
        """.trimIndent()
        val response = json.decodeFromString<NcLoginResponse>(payload)
        assertEquals(-462, response.code)
        assertNull(response.account)
        assertEquals("https://st.music.163.com/encrypt-pages", response.data?.challengeUrl)
    }

    @Test
    fun `the default poll shape is waiting with nothing attached`() {
        assertEquals(QrStage.WAITING, QrPoll.WAITING.stage)
        assertNull(QrPoll.WAITING.message)
        assertNull(QrPoll.WAITING.verifyUrl)
    }

    @Test
    fun `accepts a full pasted cookie header`() {
        val pairs = parseCookieInput("MUSIC_U=abc123; __csrf=def456; NMTID=xyz")
        assertEquals(listOf("MUSIC_U=abc123", "__csrf=def456", "NMTID=xyz"), pairs)
    }

    @Test
    fun `strips a leading cookie label and stray separators`() {
        assertEquals(
            listOf("MUSIC_U=abc123", "__csrf=def456"),
            parseCookieInput("Cookie: MUSIC_U=abc123; __csrf=def456; "),
        )
        assertEquals(
            listOf("MUSIC_U=abc123"),
            parseCookieInput("cookie:MUSIC_U=abc123"),
        )
    }

    @Test
    fun `accepts a multi-line devtools dump`() {
        val dump = "MUSIC_U=abc123\n__csrf=def456\r\nNMTID=xyz\n"
        assertEquals(
            listOf("MUSIC_U=abc123", "__csrf=def456", "NMTID=xyz"),
            parseCookieInput(dump),
        )
    }

    @Test
    fun `treats a bare token as MUSIC_U`() {
        assertEquals(listOf("MUSIC_U=00A1B2C3"), parseCookieInput("00A1B2C3"))
        assertEquals(listOf("MUSIC_U=00A1B2C3"), parseCookieInput("  00A1B2C3  "))
    }

    @Test
    fun `empty input produces nothing rather than a nameless cookie`() {
        assertTrue(parseCookieInput("").isEmpty())
        assertTrue(parseCookieInput("   \n  ").isEmpty())
        assertTrue(parseCookieInput(";").isEmpty())
    }
}
