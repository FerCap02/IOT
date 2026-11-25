package cl.filamentbox.app.security

import android.content.Context
import cl.filamentbox.app.data.ConnConfig

/** Guarda la configuración de conexión; el token va cifrado con Keystore. */
class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("fb_prefs", Context.MODE_PRIVATE)

    fun usbKey(): String = prefs.getString("usb_key_enc", null)?.let { Crypto.decrypt(it) } ?: ""
    fun saveUsbKey(key: String) { prefs.edit().putString("usb_key_enc", Crypto.encrypt(key)).apply() }

    fun load(): ConnConfig {
        val d = ConnConfig()
        val token = prefs.getString("token_enc", null)?.let { Crypto.decrypt(it) } ?: d.token
        return ConnConfig(
            host = prefs.getString("host", d.host) ?: d.host,
            port = prefs.getInt("port", d.port),
            tls = prefs.getBoolean("tls", d.tls),
            token = token,
        )
    }

    fun save(c: ConnConfig) {
        prefs.edit()
            .putString("host", c.host)
            .putInt("port", c.port)
            .putBoolean("tls", c.tls)
            .putString("token_enc", Crypto.encrypt(c.token))
            .apply()
    }
}

/** Política propia: HTTP/WS sin cifrar solo dentro de la red local; fuera de ella, TLS obligatorio. */
object NetPolicy {
    fun isLocalHost(host: String): Boolean {
        val h = host.trim().lowercase()
        if (h == "localhost" || h.endsWith(".local")) return true
        val p = h.split(".").mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || p[0] == 127 ||
            (p[0] == 192 && p[1] == 168) ||
            (p[0] == 172 && p[1] in 16..31) ||
            (p[0] == 169 && p[1] == 254)
    }

    fun allowed(c: ConnConfig): Boolean = c.tls || isLocalHost(c.host)
}
