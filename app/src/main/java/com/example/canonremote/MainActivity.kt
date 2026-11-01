package com.example.canonremote

import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.canonremote.model.CameraViewModel
import com.example.canonremote.model.ConnState

class MainActivity : ComponentActivity() {
    private val vm: CameraViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot(vm)
                }
            }
        }
    }
}

@Composable
fun AppRoot(vm: CameraViewModel) {
    when (vm.connState) {
        ConnState.CONNECTED -> CameraScreen(vm)
        else -> ConnectScreen(vm)
    }
}

@Composable
fun ConnectScreen(vm: CameraViewModel) {
    var host by remember { mutableStateOf("192.168.1.2") }
    var port by remember { mutableStateOf("8080") }
    var useHttps by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Canon Remote", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Connects directly to your EOS R8 over Wi-Fi via Canon's CCAPI. " +
                "No Canon ID or internet connection is used.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(8.dp))
        Text("1. On the camera: Menu → Communication functions → Camera Control API → On, " +
            "set up a username/password under User authentic., then Connect → Add with wizard.", style = MaterialTheme.typography.bodySmall)
        Text("2. On this phone: join the camera's Wi-Fi network (or the shared access point), " +
            "in Android Wi-Fi settings.", style = MaterialTheme.typography.bodySmall)
        Text("3. The camera's screen shows its IP address and port — enter them below.", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Camera IP address") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Port") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useHttps, onCheckedChange = { useHttps = it })
            Text("Use HTTPS (camera's self-signed cert is trusted automatically)")
        }
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username (if auth enabled on camera)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.connect(host.trim(), port.trim(), useHttps, username.trim(), password) },
            enabled = vm.connState != ConnState.CONNECTING,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (vm.connState == ConnState.CONNECTING) "Connecting…" else "Connect")
        }

        vm.errorMessage?.let {
            Text("Error: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun CameraScreen(vm: CameraViewModel) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(vm)
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Shoot") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Settings") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Debug") })
        }
        when (tab) {
            0 -> ShootTab(vm)
            1 -> SettingsTab(vm)
            else -> DebugTab(vm)
        }
    }
}

@Composable
fun TopAppBar(vm: CameraViewModel) {
    Row(
        Modifier.fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(vm.deviceName ?: "Camera", fontWeight = FontWeight.Bold)
            Text(vm.apiVersion ?: "", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { vm.disconnect() }) { Text("Disconnect") }
    }
}

@Composable
fun ShootTab(vm: CameraViewModel) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 2f)
                .background(androidx.compose.ui.graphics.Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val bmp: Bitmap? = vm.frame
            if (bmp != null) {
                Image(bitmap = bmp.asImageBitmap(), contentDescription = "Live view", modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    if (vm.liveViewRunning) "Waiting for frames…" else "Live view off",
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (vm.liveViewRunning) vm.stopLiveView() else vm.startLiveView() }) {
                Text(if (vm.liveViewRunning) "Stop live view" else "Start live view")
            }
            Button(onClick = { vm.refreshSettings() }) { Text("Refresh settings") }
        }

        Button(
            onClick = { vm.capturePhoto() },
            modifier = Modifier.size(84.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("Shoot") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.halfPressShutter() }) { Text("Half-press (AF)") }
            OutlinedButton(onClick = { vm.pressShutter() }) { Text("Full press") }
            OutlinedButton(onClick = { vm.releaseShutter() }) { Text("Release") }
        }

        vm.lastCaptureMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun SettingsTab(vm: CameraViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingRow("ISO", vm.iso) { vm.pushIso(it) }
        SettingRow("Aperture (Av)", vm.av) { vm.pushAv(it) }
        SettingRow("Shutter speed (Tv)", vm.tv) { vm.pushTv(it) }
        SettingRow("Exposure comp.", vm.ec) { vm.pushEc(it) }
        Text(
            "Values are the raw strings CCAPI uses on your camera (e.g. ISO \"400\", Av \"5.6\", Tv \"1/125\"). " +
                "Check the Debug tab → GET shooting/settings/iso (etc.) to see the exact allowed values list for your firmware.",
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
fun SettingRow(label: String, current: String?, onSet: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current ?: "") }
    Column {
        Text(label, fontWeight = FontWeight.Bold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = value, onValueChange = { value = it }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = { onSet(value) }) { Text("Set") }
        }
    }
}

@Composable
fun DebugTab(vm: CameraViewModel) {
    var path by remember { mutableStateOf("/ccapi/") }
    var result by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Send a raw GET request to inspect what your camera's CCAPI actually exposes.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Path") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.rawGet(path) { result = it } }) { Text("GET") }
        Divider()
        Text(result, modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
