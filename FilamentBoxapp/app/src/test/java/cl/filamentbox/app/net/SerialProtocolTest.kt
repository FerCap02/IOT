package cl.filamentbox.app.net

import cl.filamentbox.app.data.Climate
import cl.filamentbox.app.data.DeviceStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SerialProtocolTest {
    private val key = "0123456789abcdef0123456789abcdef"
    private val nonce = "00112233445566778899aabbccddeeff"
    private fun session() = SerialProtocol(key).also {
        it.receive("""{"v":1,"hello":"$nonce"}""")
    }
    // Independent HMAC fixture computed with Python hashlib, not the Kotlin encoder.
    private fun reply() = JSONObject().put("v", 1).put("boot", nonce).put("seq", 1)
        .put("payload", """{"type":"ACK","ack":1,"ok":true}""")
        .put("mac", "4bd8fd32ff49a4b05adeef34aa518a16c94480962e16a5e32cb77b6c4291e3ae")

    @Test fun acceptsAuthenticatedDeviceReply() {
        assertTrue(session().receive(reply().toString())!!.getBoolean("ok"))
    }
    @Test fun rejectsModifiedPayload() {
        assertThrows(IllegalArgumentException::class.java) {
            session().receive(reply().put("payload", """{"type":"ACK","ok":false}""").toString())
        }
    }
    @Test fun rejectsReplay() {
        val p = session(); val frame = reply().toString(); p.receive(frame)
        assertThrows(IllegalArgumentException::class.java) { p.receive(frame) }
    }
    @Test fun rejectsPreviousBoot() {
        assertThrows(IllegalArgumentException::class.java) {
            session().receive(reply().put("boot", "ffffffffffffffffffffffffffffffff").toString())
        }
    }
    @Test fun rejectsReflectedAppCommand() {
        val p = session()
        val command = p.encode(JSONObject().put("cmd", "STOP"))
        assertThrows(IllegalArgumentException::class.java) { p.receive(command.trim()) }
    }
    @Test fun rejectsWrongKey() {
        val p = SerialProtocol("f".repeat(32)); p.receive("""{"v":1,"hello":"$nonce"}""")
        assertThrows(IllegalArgumentException::class.java) { p.receive(reply().toString()) }
    }
    @Test fun rejectsOversizedFrame() {
        assertThrows(IllegalArgumentException::class.java) { session().receive("x".repeat(4097)) }
    }
    @Test fun rejectsSecondChallengeDuringActiveSession() {
        assertThrows(IllegalArgumentException::class.java) { session().receive("""{"v":1,"hello":"$nonce"}""") }
    }
    @Test fun missingDhtRemainsUnavailable() {
        val c = Climate.from(JSONObject().put("valid", false))
        assertFalse(c.valid); assertTrue(c.t.isNaN()); assertTrue(c.rh.isNaN())
        assertTrue(c.toJson().isNull("t"))
    }
    @Test fun rejectsOutOfRangeTelemetry() {
        assertThrows(IllegalArgumentException::class.java) { DeviceStatus(chamber = Climate(23.0, 150.0)).validated() }
        assertThrows(IllegalArgumentException::class.java) { DeviceStatus(uv = 12.0, uvUnit = "V").validated() }
    }
}
