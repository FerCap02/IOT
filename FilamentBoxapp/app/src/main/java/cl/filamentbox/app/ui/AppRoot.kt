package cl.filamentbox.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cl.filamentbox.app.AppViewModel
import cl.filamentbox.app.AuthScreen
import cl.filamentbox.app.data.ConnState

@Composable
fun AppRoot(vm: AppViewModel) {
    if (vm.user != null) {
        MainScaffold(vm)
    } else {
        when (vm.authScreen) {
            AuthScreen.LOGIN -> LoginScreen(vm)
            AuthScreen.REGISTER -> RegisterScreen(vm)
            AuthScreen.RECOVER -> RecoverScreen(vm)
        }
    }
}

@Composable
fun LoginScreen(vm: AppViewModel) {
    var user by rememberSaveable { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Filament Box", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.primary)
        Text(
            "Control ambiental de almacenamiento de filamento 3D",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = user, onValueChange = { user = it }, label = { Text("Correo electrónico") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = pass, onValueChange = { pass = it }, label = { Text("Contraseña") },
            singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Ocultar" else "Ver") } },
        )
        vm.authError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = { vm.login(user, pass) },
            enabled = !vm.authBusy, modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text(if (vm.authBusy) "Verificando…" else "Ingresar") }
        TextButton(onClick = { vm.goTo(AuthScreen.REGISTER) }) { Text("Crear cuenta nueva") }
        TextButton(onClick = { vm.goTo(AuthScreen.RECOVER) }) { Text("¿Olvidaste tu contraseña?") }
        Spacer(Modifier.height(12.dp))
        Text(
            "Autenticación con Firebase Authentication.",
            style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScaffold(vm: AppViewModel) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snack.showSnackbar(it); vm.message = null }
    }
    val tabs = listOf("Panel", "Control", "Historial", "Simulador", "Ajustes")
    val icons = listOf(Icons.Filled.Home, Icons.Filled.Build, Icons.Filled.DateRange, Icons.Filled.PlayArrow, Icons.Filled.Settings)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Filament Box") },
                actions = {
                    val color = when (vm.connState) {
                        ConnState.ONLINE -> Color(0xFF2E7D32)
                        ConnState.CONNECTING -> Color(0xFFF9A825)
                        else -> Color(0xFFC62828)
                    }
                    Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).background(color, CircleShape))
                        Text("  " + connLabel(vm.connState), style = MaterialTheme.typography.labelMedium)
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                tabs.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(icons[i], contentDescription = label) },
                        label = { Text(label) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> DashboardScreen(vm)
                1 -> ControlScreen(vm)
                2 -> HistoryScreen(vm)
                3 -> SimulatorScreen(vm)
                else -> SettingsScreen(vm)
            }
        }
    }
}
