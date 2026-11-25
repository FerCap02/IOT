package cl.filamentbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import cl.filamentbox.app.AppViewModel
import cl.filamentbox.app.data.ConnState

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val c = vm.cfg
    val s = vm.status
    var token by remember(c) { mutableStateOf(c.token) }
    var usbKey by remember { mutableStateOf(vm.savedUsbKey()) }
    var benchConfirmed by remember { mutableStateOf(false) }

    var target by remember(s?.targetRh) { mutableFloatStateOf((s?.targetRh ?: 30.0).toFloat()) }
    var hyst by remember(s?.hysteresis) { mutableFloatStateOf((s?.hysteresis ?: 5.0).toFloat()) }
    var uv by remember(s?.uvAlert) { mutableFloatStateOf((s?.uvAlert ?: 3.0).toFloat()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard("Cuenta") {
            Text("Sesión iniciada como: ${vm.user}")
            Text("Correo: ${vm.userEmail ?: "sin correo asociado"}")
            if (vm.userEmail == null) {
                var mail by remember { mutableStateOf("") }
                var mailPass by remember { mutableStateOf("") }
                Text("Asocia un correo para poder recuperar tu contraseña.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = mail, onValueChange = { mail = it }, label = { Text("Correo electrónico") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                )
                PwField("Contraseña actual", mailPass) { mailPass = it }
                Button(onClick = { vm.linkEmail(mail, mailPass) }) { Text("Asociar correo") }
            }
            OutlinedButton(onClick = { vm.logout() }) { Text("Cerrar sesión") }
        }

        SectionCard("Conexión con Firebase y ESP32-S3") {
            Text("La aplicación se comunica con la ESP32 mediante Firebase Realtime Database.")
            Text("Estado: ${connLabel(vm.connState)}", style = MaterialTheme.typography.bodyMedium)
            if (vm.connMsg.isNotEmpty()) Text(vm.connMsg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.connect() }) { Text("Conectar / reintentar") }
                OutlinedButton(onClick = { vm.disconnect() }) { Text("Desconectar") }
            }
            Text(
                "Firebase usa la sesión de correo y contraseña. Las lecturas y escrituras pendientes " +
                    "se conservan en el teléfono y se sincronizan al recuperar Internet.",
                style = MaterialTheme.typography.labelSmall,
            )
        }

        SectionCard("Comunicación serial USB con ESP32-S3") {
            Text("Conecta el teléfono mediante USB OTG. Puedes controlar el equipo sin Wi-Fi.")
            OutlinedTextField(value = usbKey, onValueChange = { usbKey = it },
                label = { Text("Clave USB del dispositivo") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.connectUsb(usbKey) }) { Text("Conectar USB") }
                OutlinedButton(onClick = { vm.disconnectUsb(); vm.disconnect() }) { Text("Desconectar USB") }
            }
            Text(vm.usbMessage.ifEmpty { "USB desconectado" })
            Button(onClick = { vm.usbStop() }, enabled = vm.usbConnected) { Text("Detener actuadores") }
            Row {
                Checkbox(checked = benchConfirmed, onCheckedChange = { benchConfirmed = it })
                Text("Motores libres para la prueba; alimentación y cableado verificados")
            }
            Text("Prueba de 90° aproximados; no establece la posición de las compuertas.", style = MaterialTheme.typography.bodySmall)
            for (motor in 1..2) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.usbMotorTest(motor, 1) }, enabled = vm.usbConnected && benchConfirmed) { Text("Motor $motor +90°") }
                OutlinedButton(onClick = { vm.usbMotorTest(motor, -1) }, enabled = vm.usbConnected && benchConfirmed) { Text("Motor $motor −90°") }
            }
        }

        SectionCard("Parámetros del sistema (se envían al ESP32)") {
            Text("Humedad objetivo: ${target.toInt()} %")
            Slider(value = target, onValueChange = { target = it }, valueRange = 10f..60f)
            Text("Margen de activación (histéresis): ${hyst.toInt()} %")
            Slider(value = hyst, onValueChange = { hyst = it }, valueRange = 1f..20f)
            Text("Umbral UV guardado (requiere calibración del sensor): ${"%.1f".format(uv)}")
            Slider(value = uv, onValueChange = { uv = it }, valueRange = 1f..11f)
            Button(
                onClick = { vm.applySettings(target.toDouble(), hyst.toDouble(), uv.toDouble()) },
                enabled = vm.connState == ConnState.ONLINE,
            ) { Text("Aplicar al dispositivo") }
        }

        SectionCard("Simulador de dispositivo (prueba con 2 teléfonos)") {
            OutlinedTextField(
                value = token, onValueChange = { token = it },
                label = { Text("Token del simulador local") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                "Este teléfono puede actuar como un ESP32 virtual (sensores, compuertas y ventilador simulados). " +
                    "Otro teléfono con esta misma app se conecta por Wi‑Fi, igual que lo haría con el equipo real.",
                style = MaterialTheme.typography.bodySmall,
            )
            SwitchRow("Simulador activo (puerto ${AppViewModel.SIM_PORT})", vm.simRunning, true) {
                if (it) vm.startSim(token.trim()) else vm.stopSim()
            }
            if (vm.simRunning) {
                Text("Con el simulador activo, la pestaña «Simulador» permite cambiar los valores de los sensores y cargar escenarios.", style = MaterialTheme.typography.bodySmall)
                Text("IP de este teléfono: ${vm.simIp ?: "sin red Wi‑Fi"}")
                Text(
                    "El simulador local sigue disponible para pruebas independientes. La conexión real de Filament Box utiliza Firebase.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        SectionCard("Seguridad implementada (ISO/IEC 27400 · informe §16.1)") {
            listOf(
                "Autenticación de usuarios con Firebase Authentication (correo y contraseña).",
                "Registro con confirmación de contraseña y correo único por cuenta.",
                "Recuperación de contraseña mediante enlace enviado por Firebase Authentication.",
                "Las políticas de protección y límites de intentos las gestiona Firebase Authentication.",
                "Sesión Firebase por correo y contraseña, sin base de datos SQLite.",
                "Comunicación con Firebase mediante el SDK oficial y TLS administrado por Firebase.",
                "Las reglas de Realtime Database deben restringirse antes de publicar el sistema.",
                "USB requiere autorización del teléfono; clave protegida con Android Keystore.",
                "Sin copias de seguridad de la aplicación (allowBackup=false).",
            ).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
