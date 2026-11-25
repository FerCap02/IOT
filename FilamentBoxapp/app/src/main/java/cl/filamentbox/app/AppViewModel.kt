package cl.filamentbox.app

import android.app.Application
import android.util.Patterns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cl.filamentbox.app.data.ConnConfig
import cl.filamentbox.app.data.ConnState
import cl.filamentbox.app.data.DeviceStatus
import cl.filamentbox.app.data.Reading
import cl.filamentbox.app.net.FirebaseDeviceClient
import cl.filamentbox.app.net.UsbSerialClient
import cl.filamentbox.app.net.validated
import cl.filamentbox.app.security.SecureStore
import cl.filamentbox.app.sim.SimLive
import cl.filamentbox.app.sim.SimServer
import cl.filamentbox.app.sim.SimVar
import cl.filamentbox.app.sim.SimulatedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.firebase.auth.EmailAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import org.json.JSONObject

enum class AuthScreen { LOGIN, REGISTER, RECOVER }

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val auth = FirebaseAuth.getInstance()
    private val root = FirebaseDatabase.getInstance().reference.child("filamentBox")
    private val store = SecureStore(app)

    // ---- Estado observado por la UI ----
    var user by mutableStateOf<String?>(null); private set
    var authError by mutableStateOf<String?>(null); private set
    var authBusy by mutableStateOf(false); private set
    var authScreen by mutableStateOf(AuthScreen.LOGIN); private set
    var userEmail by mutableStateOf<String?>(null); private set
    /** 0 = pedir enlace, 2 = enlace enviado. */
    var recoverStage by mutableStateOf(0); private set
    var recoverInfo by mutableStateOf<String?>(null); private set

    var cfg by mutableStateOf(store.load()); private set
    var status by mutableStateOf<DeviceStatus?>(null); private set
    var connState by mutableStateOf(ConnState.DISCONNECTED); private set
    var connMsg by mutableStateOf(""); private set
    var history by mutableStateOf<List<Reading>>(emptyList()); private set
    var message by mutableStateOf<String?>(null)

    var simRunning by mutableStateOf(false); private set
    var simIp by mutableStateOf<String?>(null); private set
    /** Variables que el usuario fijó a mano en el panel de simulación. */
    var simForced by mutableStateOf<Map<SimVar, Double>>(emptyMap()); private set

    private val firebaseClient = FirebaseDeviceClient()
    private val usbClient = UsbSerialClient(app, viewModelScope)
    var usbSelected by mutableStateOf(false); private set
    var usbConnected by mutableStateOf(false); private set
    var usbMessage by mutableStateOf(""); private set
    private var firebaseOnline = false
    private var lastSampleId = ""
    private var freshnessJob: Job? = null
    private var retryJob: Job? = null
    private var wantConnected = false
    private var lastSave = 0L
    private var sim: SimServer? = null
    private var simDevice: SimulatedDevice? = null

    // ---------------- Autenticación Firebase (correo/contraseña) ----------------

    init {
        freshnessJob = viewModelScope.launch {
            while (true) {
                delay(5000)
                if (wantConnected && !usbSelected) {
                    val stamp = status?.let { if (it.sampleTs > 0) it.sampleTs else it.receivedAt } ?: 0
                    if (!firebaseOnline || System.currentTimeMillis() - stamp > 30000) {
                        connState = ConnState.CONNECTING
                        connMsg = "Sin telemetría reciente; reintentando automáticamente"
                    }
                }
            }
        }
        // Firebase Auth restaura su sesión tras reiniciar la aplicación.
        auth.currentUser?.let { account ->
            user = account.uid
            userEmail = account.email
            refreshHistory()
            if (FirebaseConfig.isConfigured) connect()
        }
    }

    fun login(email: String, password: String) {
        val e = email.trim().lowercase()
        if (!isValidEmail(e) || password.isEmpty()) { authError = "Ingresa correo y contraseña válidos"; return }
        authBusy = true; authError = null
        auth.signInWithEmailAndPassword(e, password).addOnCompleteListener { task ->
            authBusy = false
            if (task.isSuccessful) {
                user = auth.currentUser?.uid
                userEmail = auth.currentUser?.email
                refreshHistory(); connect()
            } else authError = task.exception?.localizedMessage ?: "No se pudo iniciar sesión"
        }
    }

    fun goTo(screen: AuthScreen) {
        authError = null; recoverStage = 0; recoverInfo = null; authScreen = screen
    }

    private fun isValidEmail(e: String) = Patterns.EMAIL_ADDRESS.matcher(e).matches()

    private fun passwordError(p: String, confirm: String): String? = when {
        p.length < 8 || p.none { it.isDigit() } || p.none { it.isLetter() } ->
            "La contraseña debe tener 8+ caracteres con letras y números"
        p != confirm -> "Las contraseñas no coinciden"
        else -> null
    }

    fun register(username: String, email: String, password: String, confirm: String) {
        val name = username.trim()
        val e = email.trim().lowercase()
        if (name.length < 3) { authError = "El nombre debe tener al menos 3 caracteres"; return }
        if (!isValidEmail(e)) { authError = "Ingresa un correo válido"; return }
        passwordError(password, confirm)?.let { authError = it; return }
        authBusy = true; authError = null
        auth.createUserWithEmailAndPassword(e, password).addOnCompleteListener { task ->
            authBusy = false
            if (task.isSuccessful) {
                val account = auth.currentUser ?: return@addOnCompleteListener
                root.child("usuarios").child(account.uid).setValue(
                    mapOf("nombre" to name, "createdAt" to System.currentTimeMillis())
                ).addOnFailureListener { message = "Cuenta creada, pero no se guardó el perfil: ${it.localizedMessage}" }
                user = account.uid
                userEmail = account.email
                refreshHistory(); connect()
            } else authError = task.exception?.localizedMessage ?: "No se pudo registrar"
        }
    }

    fun requestReset(email: String) {
        val e = email.trim().lowercase()
        if (!isValidEmail(e)) { authError = "Ingresa un correo válido"; return }
        authBusy = true; authError = null
        auth.sendPasswordResetEmail(e).addOnCompleteListener { task ->
            authBusy = false
            if (task.isSuccessful) {
                recoverStage = 2
                recoverInfo = "Revisa tu correo para abrir el enlace de recuperación."
            } else authError = task.exception?.localizedMessage ?: "No se pudo enviar el enlace"
        }
    }

    // Cambiar correo requiere reautenticación reciente; la contraseña no se guarda localmente.
    fun linkEmail(email: String, password: String) {
        val e = email.trim().lowercase()
        if (!isValidEmail(e)) { message = "Ingresa un correo válido"; return }
        val account = auth.currentUser ?: run { message = "Inicia sesión"; return }
        val currentEmail = account.email ?: run { message = "Cuenta sin correo"; return }
        val credential = EmailAuthProvider.getCredential(currentEmail, password)
        account.reauthenticate(credential).addOnSuccessListener {
            account.verifyBeforeUpdateEmail(e).addOnSuccessListener {
                message = "Revisa el correo nuevo y confirma el cambio antes de volver a iniciar sesión."
            }.addOnFailureListener { err -> message = err.localizedMessage ?: "No se pudo solicitar el cambio" }
        }.addOnFailureListener { err -> message = err.localizedMessage ?: "Contraseña incorrecta" }
    }

    fun logout() {
        disconnectUsb()
        disconnect()
        firebaseClient.logout()
        user = null; userEmail = null; status = null; history = emptyList()
        authScreen = AuthScreen.LOGIN
    }

    // ---------------- Conexión Wi-Fi con el dispositivo ----------------

    fun saveConfig(c: ConnConfig) {
        cfg = c
        store.save(c)
        connect()
    }

    fun connect() {
        disconnectUsb()
        if (!FirebaseConfig.isConfigured) {
            connState = ConnState.ERROR
            connMsg = "Falta configurar FirebaseConfig.kt con los datos de tu proyecto Firebase."
            return
        }
        wantConnected = true
        connState = ConnState.CONNECTING
        connMsg = "Conectando con Firebase..."
        firebaseClient.connect(
            onReady = {
                firebaseOnline = true
                connMsg = "Firebase conectado; esperando telemetría reciente"
            },
            onStatus = { incoming ->
                if (!usbSelected) viewModelScope.launch { onTelemetry(incoming) }
            },
            onError = { error ->
                connState = ConnState.ERROR
                connMsg = error
                message = error
            },
            onOffline = {
                firebaseOnline = false
                if (!usbSelected) { connState = ConnState.CONNECTING; connMsg = "Sin conexión; datos en caché, reconexión automática" }
            },
        )
    }

    fun connectUsb(key: String) {
        if (key.length < 32) { message = "Ingresa la clave USB de al menos 32 caracteres"; return }
        disconnect()
        usbSelected = true
        store.saveUsbKey(key)
        usbClient.start(key,
            onState = { connected, text -> viewModelScope.launch {
                if (usbSelected) {
                    usbConnected = connected; usbMessage = text; connMsg = text
                    connState = if (connected) ConnState.ONLINE else ConnState.CONNECTING
                }
            } },
            onStatus = { incoming -> viewModelScope.launch { if (usbSelected) onTelemetry(incoming) } })
    }

    fun savedUsbKey(): String = store.usbKey()
    fun disconnectUsb() {
        usbClient.close(); usbSelected = false; usbConnected = false
    }

    private fun sendUsb(command: JSONObject) {
        if (!usbConnected || !usbClient.send(command)) message = "USB no disponible; la instrucción no fue enviada"
    }

    fun usbStop() = sendUsb(JSONObject().put("cmd", "STOP"))
    fun usbMotorTest(motor: Int, direction: Int) = sendUsb(JSONObject().put("cmd", "MOTOR_TEST")
        .put("motor", motor).put("direction", direction).put("benchConfirmed", true))

    private fun usbControl(update: (JSONObject) -> Unit) {
        val s = status ?: return
        val body = s.toJson()
        update(body)
        sendUsb(JSONObject().put("cmd", "SET_CONTROL").put("control", body))
    }

    private fun onTelemetry(s: DeviceStatus) {
        status = s
        val stamp = if (s.sampleTs > 0) s.sampleTs else s.receivedAt
        if (usbSelected || (firebaseOnline && stamp > 0 && System.currentTimeMillis() - stamp in -5000..30000)) {
            connState = ConnState.ONLINE
            if (!usbSelected) connMsg = "Firebase y dispositivo conectados"
        }
        val u = user ?: return
        val now = System.currentTimeMillis()
        if (s.sampleId.isNotEmpty() && s.sampleId == lastSampleId) return
        if (now - lastSave >= 10_000) {
            lastSave = now
            lastSampleId = s.sampleId
            viewModelScope.launch {
                val reading = mapOf(
                    "ts" to (s.sampleTs.takeIf { it > 0 } ?: now), "chT" to s.chamber.t, "chRh" to s.chamber.rh,
                    "outT" to s.outdoor.t, "outRh" to s.outdoor.rh,
                    "postT" to s.postSilica.t, "postRh" to s.postSilica.rh,
                    "uv" to s.uv, "mode" to s.mode
                )
                val safeReading = reading.mapValues { (_, v) -> if (v is Double && !v.isFinite()) null else v }
                val ref = root.child("historial").child(u)
                val key = s.sampleId.ifEmpty { ref.push().key ?: now.toString() }
                // Firebase persistence retains this write across process death and retries it.
                ref.child(key).setValue(safeReading)
                    .addOnSuccessListener { refreshHistory() }
                    .addOnFailureListener { message = "No se guardó historial: ${it.localizedMessage}" }
            }
        }
    }

    fun disconnect() {
        wantConnected = false
        retryJob?.cancel()
        firebaseClient.close()
        firebaseOnline = false
        connState = ConnState.DISCONNECTED
        connMsg = ""
    }

    // ---------------- Comandos hacia Firebase Realtime Database ----------------

    fun setPower(on: Boolean) {
        if (usbSelected) usbControl { it.put("power", on); if (on && it.optString("mode") == "OFF") it.put("mode", "AUTO").put("auto", true) }
        else firebaseClient.setPower(on) { message = it }
    }
    fun setMode(mode: String) {
        if (usbSelected) usbControl { it.put("mode", mode).put("auto", mode == "AUTO") }
        else firebaseClient.setMode(mode) { message = it }
    }
    fun setFan(on: Boolean) {
        if (usbSelected) usbControl { it.put("fan", on) }
        else firebaseClient.setFan(on) { message = it }
    }
    fun setVents(inletOpen: Boolean, outletExterior: Boolean) {
        message = "Compuertas sin calibrar; utiliza la prueba de motores en Ajustes"
    }

    fun applySettings(target: Double, hysteresis: Double, uvAlert: Double) {
        if (usbSelected) usbControl { it.put("settings", JSONObject().put("targetRh", target).put("hysteresis", hysteresis).put("uvAlert", uvAlert)) }
        else firebaseClient.setSettings(target, hysteresis, uvAlert) { message = it }
    }

    // ---------------- Historial en Firebase Realtime Database ----------------

    fun refreshHistory() {
        val uid = user ?: return
        root.child("historial").child(uid).orderByChild("ts").limitToLast(60).get()
            .addOnSuccessListener { snap ->
                history = snap.children.mapNotNull { node ->
                    val ts = node.child("ts").getValue(Long::class.java) ?: return@mapNotNull null
                    fun value(key: String): Double {
                        val n = node.child(key)
                        return n.getValue(Double::class.java)
                            ?: n.getValue(Long::class.java)?.toDouble() ?: Double.NaN
                    }
                    Reading(ts, value("chT"), value("chRh"), value("outT"), value("outRh"),
                        value("postT"), value("postRh"), value("uv"),
                        node.child("mode").getValue(String::class.java) ?: "OFF")
                }.sortedByDescending { it.ts }
            }.addOnFailureListener { message = "No se pudo leer el historial: ${it.localizedMessage}" }
    }

    fun clearHistory() {
        val uid = user ?: return
        root.child("historial").child(uid).removeValue()
            .addOnSuccessListener { history = emptyList() }
            .addOnFailureListener { message = "No se pudo borrar el historial: ${it.localizedMessage}" }
    }

    // ---------------- Simulador de ESP32 (para el 2.º teléfono) ----------------

    fun startSim(token: String) {
        if (simRunning) return
        simRunning = true
        val dev = SimulatedDevice()
        simDevice = dev
        viewModelScope.launch {
            try {
                val (server, ip) = withContext(Dispatchers.IO) {
                    val srv = SimServer(SIM_PORT, dev, token)
                    srv.begin()
                    srv to SimServer.localIpv4()
                }
                sim = server
                simIp = ip
            } catch (e: Exception) {
                simRunning = false
                simDevice = null
                message = "No se pudo iniciar el simulador: ${e.message}"
            }
        }
    }

    fun stopSim() {
        val s = sim
        sim = null
        simDevice = null
        simForced = emptyMap()
        simRunning = false
        if (s != null) viewModelScope.launch(Dispatchers.IO) { s.end() }
    }

    // ---- Panel de simulación ----

    /** Valores actuales del dispositivo simulado (para refrescar los sliders). */
    fun simSnapshot(): SimLive? {
        val d = simDevice ?: return null
        return SimLive(d.status(), d.silicaPercent(), sim?.clientCount() ?: 0)
    }

    /** Fija una variable a un valor (null = vuelve a automático). */
    fun simForce(v: SimVar, value: Double?) {
        val d = simDevice ?: return
        d.force(v, value)
        simForced = d.forcedSnapshot()
    }

    /** DRY | RAIN | SUN | SILICA_SPENT | SILICA_NEW | LID | AUTO */
    fun simScenario(name: String) {
        val d = simDevice ?: return
        d.scenario(name)
        simForced = d.forcedSnapshot()
    }

    override fun onCleared() {
        disconnectUsb()
        freshnessJob?.cancel()
        disconnect()
        sim?.end(); sim = null; simDevice = null
    }

    companion object {
        const val SIM_PORT = 8080

    }
}
