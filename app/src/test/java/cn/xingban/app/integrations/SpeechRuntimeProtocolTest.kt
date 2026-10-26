package cn.xingban.app.integrations

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SpeechRuntimeProtocolTest {
    @Test
    fun miniMaxRequestUsesConfirmedHexMp3ContractAndExplicitVoice() {
        val request = JSONObject(encodeMiniMaxRequest("你好。", "voice-1"))

        assertEquals("speech-2.8-hd", request.getString("model"))
        assertEquals(false, request.getBoolean("stream"))
        assertEquals("hex", request.getString("output_format"))
        val voice = request.getJSONObject("voice_setting")
        assertEquals("voice-1", voice.getString("voice_id"))
        assertEquals(1, voice.getInt("speed"))
        assertEquals(1, voice.getInt("vol"))
        assertEquals(0, voice.getInt("pitch"))
        val audio = request.getJSONObject("audio_setting")
        assertEquals(32_000, audio.getInt("sample_rate"))
        assertEquals(128_000, audio.getInt("bitrate"))
        assertEquals("mp3", audio.getString("format"))
        assertEquals(1, audio.getInt("channel"))
    }

    @Test
    fun miniMaxResponseRequiresSuccessAndDecodesHexAudio() {
        val body = """{"base_resp":{"status_code":0},"data":{"status":2,"audio":"494433"},"extra_info":{"audio_format":"mp3"}}"""

        assertArrayEquals(byteArrayOf(0x49, 0x44, 0x33), decodeMiniMaxAudio(body))
        assertThrows(java.io.IOException::class.java) {
            decodeMiniMaxAudio("""{"base_resp":{"status_code":0},"data":{"status":1,"audio":"00"}}""")
        }
        assertThrows(java.io.IOException::class.java) {
            decodeMiniMaxAudio("""{"base_resp":{"status_code":1},"data":{"status":2,"audio":"00"}}""")
        }
        assertThrows(java.io.IOException::class.java) {
            decodeMiniMaxAudio("""{"base_resp":{"status_code":0},"data":{"status":2,"audio":"0x"}}""")
        }
    }
}
