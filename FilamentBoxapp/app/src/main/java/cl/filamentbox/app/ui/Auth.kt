package cl.filamentbox.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cl.filamentbox.app.AppViewModel
import cl.filamentbox.app.AuthScreen

@Composable
private fun AuthColumn(title: String, subtitle: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(20.dp))
        content()
    }
}

/** Campo de contraseña con botón Ver/Ocultar. */
@Composable
fun PwField(label: String, value: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) },
        singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Ocultar" else "Ver") } },
    )
}

@Composable
private fun ErrorText(vm: AppViewModel) {
    vm.authError?.let {
        Spacer(Modifier.height(8.dp))
        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
    }
}

@Composable
fun RegisterScreen(vm: AppViewModel) {
    var user by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }

    AuthColumn("Crear cuenta", "Asocia un correo a tu cuenta para poder recuperar la contraseña") {
        OutlinedTextField(
            value = user, onValueChange = { user = it }, label = { Text("Usuario") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Correo electrónico") },
            singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        PwField("Contraseña", pass) { pass = it }
        PwField("Confirmar contraseña", confirm) { confirm = it }
        if (confirm.isNotEmpty() && confirm != pass) {
            Text("Las contraseñas no coinciden", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        ErrorText(vm)
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.register(user, email, pass, confirm) },
            enabled = !vm.authBusy, modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (vm.authBusy) "Creando cuenta…" else "Crear cuenta") }
        TextButton(onClick = { vm.goTo(AuthScreen.LOGIN) }) { Text("Ya tengo cuenta") }
        Text(
            "La contraseña debe tener 8 o más caracteres, con letras y números.",
            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun RecoverScreen(vm: AppViewModel) {
    var email by rememberSaveable { mutableStateOf("") }
    AuthColumn("Recuperar contraseña", "Firebase enviará un enlace al correo de tu cuenta") {
        OutlinedTextField(
            value = email, onValueChange = { email = it }, label = { Text("Correo electrónico") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        )
        ErrorText(vm)
        vm.recoverInfo?.let { Text(it, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(16.dp))
        if (vm.recoverStage == 0) {
            Button(onClick = { vm.requestReset(email) }, enabled = !vm.authBusy,
                modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(if (vm.authBusy) "Enviando…" else "Enviar enlace")
            }
        }
        TextButton(onClick = { vm.goTo(AuthScreen.LOGIN) }) { Text("Volver al inicio de sesión") }
    }
}
