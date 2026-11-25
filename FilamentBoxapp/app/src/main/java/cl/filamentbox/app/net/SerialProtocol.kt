package cl.filamentbox.app.net

import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** USB framing: one UTF-8 JSON object per line; authenticate the exact payload bytes. */
class SerialProtocol(private val key: String) {
    var boot: String? = null; private set
    private var tx = 0L
    private var rx = 0L

    init { require(key.length >= 32) { "La clave USB debe tener al menos 32 caracteres" } }

    private fun mac(text: String): String = Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        doFinal(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    fun encode(body: JSONObject): String {
        val nonce = checkNotNull(boot)
        val seq = ++tx
        require(seq <= 0xffffffffL)
        val payload = body.toString()
        return JSONObject().put("v", 1).put("boot", nonce).put("seq", seq)
            .put("payload", payload).put("mac", mac("A|$nonce|$seq|$payload")).toString() + "\n"
    }

    fun receive(line: String): JSONObject? {
        require(line.length <= 4096)
        val frame = JSONObject(line)
        require(frame.getInt("v") == 1)
        if (frame.has("hello")) {
            require(boot == null) // accept a challenge only during this new USB session
            val nonce = frame.getString("hello")
            require(nonce.matches(Regex("[0-9a-f]{32}")))
            boot = nonce; tx = 0; rx = 0
            return null
        }
        val nonce = checkNotNull(boot)
        require(frame.getString("boot") == nonce)
        val seq = frame.getLong("seq")
        require(seq > rx && seq <= 0xffffffffL) { "Respuesta USB repetida" }
        val payload = frame.getString("payload")
        val expected = mac("D|$nonce|$seq|$payload")
        require(MessageDigest.isEqual(expected.toByteArray(), frame.getString("mac").toByteArray())) {
            "Firma USB inválida: revisa la clave del dispositivo"
        }
        val body = JSONObject(payload)
        rx = seq
        return body
    }
}
