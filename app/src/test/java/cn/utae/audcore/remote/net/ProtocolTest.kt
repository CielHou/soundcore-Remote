package cn.utae.audcore.remote.net

import cn.utae.audcore.remote.data.Channel
import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 协议帧与数据模型的离线单测（无需真实声核实例）。
 */
class ProtocolTest {

    private val gson = Gson()

    @Test
    fun `request frame carries id method path body`() {
        val frame = cn.utae.audcore.remote.data.RequestMessage(
            id = "1",
            method = "POST",
            path = "/connections",
            body = JsonParser.parseString("""{"app_name":"声核遥控"}"""),
        ).toJson()

        assertEquals("1", frame.get("id").asString)
        assertEquals("POST", frame.get("method").asString)
        assertEquals("/connections", frame.get("path").asString)
        assertEquals("声核遥控", frame.getAsJsonObject("body").get("app_name").asString)
    }

    @Test
    fun `request frame omits body when absent`() {
        val frame = cn.utae.audcore.remote.data.RequestMessage(
            id = "2",
            method = "GET",
            path = "/channels",
        ).toJson()
        assertTrue(!frame.has("body"))
    }

    @Test
    fun `channel parses documented fields`() {
        val json = """
            {"id":"ch-1","index":0,"name":"麦克风","gain_db":0,"mute":false,"color":null,
             "visual_index":3,"align_right":false}
        """.trimIndent()
        val channel = gson.fromJson(json, Channel::class.java)

        assertEquals("ch-1", channel.id)
        assertEquals(0, channel.index)
        assertEquals("麦克风", channel.name)
        assertEquals(0f, channel.gain_db!!)
        assertFalse(channel.mute!!)
        assertEquals(3, channel.displayOrder)
    }

    @Test
    fun `displayOrder falls back to index when visual_index missing`() {
        val channel = gson.fromJson("""{"id":"a","index":7}""", Channel::class.java)
        assertEquals(7, channel.displayOrder)
    }

    @Test
    fun `status codes are json fields not http codes`() {
        val response = JsonParser.parseString(
            """{"id":"4","status":403,"data":{"error":{"code":"authorization_pending","message":"请在声核客户端允许连接"}}}""",
        ).asJsonObject
        assertEquals(403, response.get("status").asInt)
        val error = response.getAsJsonObject("data").getAsJsonObject("error")
        assertEquals("authorization_pending", error.get("code").asString)
    }
}
