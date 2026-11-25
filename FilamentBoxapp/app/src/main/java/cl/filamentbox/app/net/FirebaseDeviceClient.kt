package cl.filamentbox.app.net

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import cl.filamentbox.app.data.Climate
import cl.filamentbox.app.data.DeviceStatus

/**
 * Firebase transport for Filament Box.
 * RTDB schema: /filamentBox/{sensores,control}
 * Uses the current Firebase Authentication email/password session.
 */
class FirebaseDeviceClient {
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val root: DatabaseReference by lazy {
        FirebaseDatabase.getInstance().reference.child("filamentBox")
    }

    private var sensorsListener: ValueEventListener? = null
    private var controlListener: ValueEventListener? = null
    private var lastSensors: DataSnapshot? = null
    private var lastControl: DataSnapshot? = null
    private var connectionListener: ValueEventListener? = null
    private val connectionRef by lazy { FirebaseDatabase.getInstance().getReference(".info/connected") }

    fun connect(
        onReady: () -> Unit,
        onStatus: (DeviceStatus) -> Unit,
        onError: (String) -> Unit,
        onOffline: () -> Unit = {},
    ) {
        val attachListener = {
            attachStatusListener(onStatus, onError)
            connectionListener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    if (snapshot.getValue(Boolean::class.java) == true) onReady() else onOffline()
                }
                override fun onCancelled(error: DatabaseError) { onError(error.message) }
            }
            connectionRef.addValueEventListener(connectionListener!!)
        }

        if (auth.currentUser != null) {
            attachListener()
        } else {
            onError("Inicia sesión con tu correo de Firebase antes de conectar")
        }
    }

    private fun attachStatusListener(
        onStatus: (DeviceStatus) -> Unit,
        onError: (String) -> Unit,
    ) {
        stopListening()
        val sensorsRef = root.child("sensores")
        val controlRef = root.child("control")

        fun publish() {
            val sensors = lastSensors ?: return
            val control = lastControl ?: return
            val settings = sensors.child("settings")
            try {
                fun number(node: DataSnapshot, key: String, fallback: Double = Double.NaN): Double =
                    (node.child(key).value as? Number)?.toDouble() ?: fallback

                fun bool(key: String, fallback: Boolean = false): Boolean =
                    sensors.child(key).getValue(Boolean::class.java) ?: fallback

                fun climate(key: String): Climate {
                    val node = sensors.child(key)
                    return Climate(t = number(node, "t"), rh = number(node, "rh"))
                }

                val alerts = sensors.child("alerts").children.mapNotNull {
                    it.getValue(String::class.java)
                }
                onStatus(DeviceStatus(
                    chamber = climate("chamber"),
                    outdoor = climate("outdoor"),
                    postSilica = climate("postSilica"),
                    uv = number(sensors, "uv"),
                    power = bool("power"),
                    auto = bool("auto", true),
                    fan = bool("fan"),
                    inletOpen = bool("inletOpen"),
                    outletExterior = bool("outletExterior"),
                    mode = sensors.child("mode").getValue(String::class.java) ?: "OFF",
                    silica = sensors.child("silica").getValue(String::class.java) ?: "SIN_CALIBRAR",
                    targetRh = number(settings, "targetRh", 30.0),
                    hysteresis = number(settings, "hysteresis", 5.0),
                    uvAlert = number(settings, "uvAlert", 3.0),
                    alerts = alerts,
                    wifi = sensors.child("wifi").getValue(String::class.java) ?: "Firebase conectado",
                    sampleId = sensors.child("sampleId").getValue(String::class.java) ?: "",
                    sampleTs = (sensors.child("sampleTs").value as? Number)?.toLong() ?: 0,
                    receivedAt = (sensors.child("receivedAt").value as? Number)?.toLong() ?: 0,
                    uvUnit = sensors.child("uvUnit").getValue(String::class.java) ?: "V",
                    positionKnown = sensors.child("positionKnown").getValue(Boolean::class.java) ?: false,
                ).validated())
            } catch (e: Exception) {
                onError("Error leyendo datos de Firebase: ${e.localizedMessage ?: "formato inválido"}")
            }
        }

        sensorsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastSensors = snapshot
                publish()
            }
            override fun onCancelled(error: DatabaseError) {
                onError("Firebase sensores: ${error.message}")
            }
        }
        controlListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                lastControl = snapshot
                publish()
            }
            override fun onCancelled(error: DatabaseError) {
                onError("Firebase control: ${error.message}")
            }
        }
        sensorsRef.addValueEventListener(sensorsListener!!)
        controlRef.addValueEventListener(controlListener!!)
        sensorsRef.keepSynced(true)
        controlRef.keepSynced(true)
    }

    fun setPower(on: Boolean, onError: (String) -> Unit = {}) = write("control/power", on, onError)
    fun setMode(mode: String, onError: (String) -> Unit = {}) {
        if (mode !in setOf("OFF", "AUTO", "STORAGE", "RECIRC", "PURGE", "MAINT")) {
            onError("Modo inválido"); return
        }
        updateControl(mapOf("mode" to mode, "auto" to (mode == "AUTO")), onError)
    }
    fun setFan(on: Boolean, onError: (String) -> Unit = {}) = write("control/fan", on, onError)

    fun setVents(inletOpen: Boolean, outletExterior: Boolean, onError: (String) -> Unit = {}) {
        updateControl(mapOf("inletOpen" to inletOpen, "outletExterior" to outletExterior), onError)
    }

    fun setSettings(targetRh: Double, hysteresis: Double, uvAlert: Double, onError: (String) -> Unit = {}) {
        if (!targetRh.isFinite() || targetRh !in 10.0..60.0 || !hysteresis.isFinite() ||
            hysteresis !in 1.0..20.0 || !uvAlert.isFinite() || uvAlert !in 0.0..11.0) {
            onError("Parámetros fuera de rango"); return
        }
        updateControl(mapOf("settings" to mapOf("targetRh" to targetRh, "hysteresis" to hysteresis, "uvAlert" to uvAlert)), onError)
    }

    private fun write(path: String, value: Any, onError: (String) -> Unit) {
        val changes = mutableMapOf<String, Any>(path.removePrefix("control/") to value)
        if (path == "control/power" && value == true && lastControl?.child("mode")?.value == "OFF") {
            changes["mode"] = "AUTO"; changes["auto"] = true
        }
        updateControl(changes, onError)
    }

    private fun updateControl(changes: Map<String, Any>, onError: (String) -> Unit) {
        if (auth.currentUser == null) { onError("Inicia sesión"); return }
        val current = mutableMapOf<String, Any>(
            "power" to false, "auto" to true, "fan" to false, "inletOpen" to false,
            "outletExterior" to false, "mode" to "OFF",
            "settings" to mapOf("targetRh" to 30.0, "hysteresis" to 5.0, "uvAlert" to 3.0))
        (lastControl?.value as? Map<*, *>)?.forEach { (k, v) -> if (k is String && v != null) current[k] = v }
        current.putAll(changes)
        // Client acquisition time, not a server timestamp resolved after an outage.
        current["issuedAt"] = System.currentTimeMillis()
        root.child("control").setValue(current).addOnFailureListener {
            onError(it.localizedMessage ?: "No se pudo enviar la instrucción")
        }
    }

    fun stopListening() {
        sensorsListener?.let { root.child("sensores").removeEventListener(it) }
        controlListener?.let { root.child("control").removeEventListener(it) }
        connectionListener?.let { connectionRef.removeEventListener(it) }
        connectionListener = null
        root.child("sensores").keepSynced(false)
        root.child("control").keepSynced(false)
        sensorsListener = null
        controlListener = null
        lastSensors = null
        lastControl = null
    }

    fun close() = stopListening()

    fun logout() {
        stopListening()
        auth.signOut()
    }
}
