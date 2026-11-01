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
            "Подключается напрямую к вашей EOS R8 по Wi-Fi через CCAPI Canon. " +
                "Canon ID и интернет-соединение не используются.",
            style = MaterialTheme.typography.bodyMedium
        )

        Spacer(Modifier.height(8.dp))
        Text("1. На камере: Меню → Функции связи → Camera Control API → Вкл., " +
            "задайте логин/пароль в разделе «Проверка польз.», затем Подключение → Добавить с помощью мастера.", style = MaterialTheme.typography.bodySmall)
        Text("2. На телефоне: подключитесь к Wi-Fi камеры (или к общей точке доступа) " +
            "в настройках Wi-Fi Android.", style = MaterialTheme.typography.bodySmall)
        Text("3. На экране камеры появится её IP-адрес и порт — введите их ниже.", style = MaterialTheme.typography.bodySmall)

        Spacer(Modifier.height(8.dp))
        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("IP-адрес камеры") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Порт") }, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = useHttps, onCheckedChange = { useHttps = it })
            Text("Использовать HTTPS (самоподписанный сертификат камеры доверяется автоматически)")
        }
        OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Имя пользователя (если на камере включена проверка)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Пароль") }, modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { vm.connect(host.trim(), port.trim(), useHttps, username.trim(), password) },
            enabled = vm.connState != ConnState.CONNECTING,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (vm.connState == ConnState.CONNECTING) "Подключение…" else "Подключиться")
        }

        vm.errorMessage?.let {
            Text("Ошибка: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun CameraScreen(vm: CameraViewModel) {
    var tab by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(vm)
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Съёмка") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Настройки") })
            Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("Отладка") })
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
            Text(vm.deviceName ?: "Камера", fontWeight = FontWeight.Bold)
            Text(vm.apiVersion ?: "", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { vm.disconnect() }) { Text("Отключиться") }
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
                    if (vm.liveViewRunning) "Ожидание кадров…" else "Live view выключен",
                    color = androidx.compose.ui.graphics.Color.White
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (vm.liveViewRunning) vm.stopLiveView() else vm.startLiveView() }) {
                Text(if (vm.liveViewRunning) "Остановить live view" else "Запустить live view")
            }
            Button(onClick = { vm.refreshSettings() }) { Text("Обновить настройки") }
        }

        Button(
            onClick = { vm.capturePhoto() },
            modifier = Modifier.size(84.dp),
            shape = androidx.compose.foundation.shape.CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
        ) { Text("Снять") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.halfPressShutter() }) { Text("Полунажатие (АФ)") }
            OutlinedButton(onClick = { vm.pressShutter() }) { Text("Полное нажатие") }
            OutlinedButton(onClick = { vm.releaseShutter() }) { Text("Отпустить") }
        }

        vm.lastCaptureMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun SettingsTab(vm: CameraViewModel) {
    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        SettingRow("ISO", vm.iso) { vm.pushIso(it) }
        SettingRow("Диафрагма (Av)", vm.av) { vm.pushAv(it) }
        SettingRow("Выдержка (Tv)", vm.tv) { vm.pushTv(it) }
        SettingRow("Экспокоррекция", vm.ec) { vm.pushEc(it) }
        Text(
            "Значения — это «сырые» строки, которые CCAPI использует на вашей камере (например, ISO «400», Av «5.6», Tv «1/125»). " +
                "Проверьте во вкладке «Отладка» → GET shooting/settings/iso (и т.д.), чтобы увидеть точный список допустимых значений для вашей прошивки.",
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
            Button(onClick = { onSet(value) }) { Text("Применить") }
        }
    }
}

@Composable
fun DebugTab(vm: CameraViewModel) {
    var path by remember { mutableStateOf("/ccapi/") }
    var result by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Отправьте произвольный GET-запрос, чтобы посмотреть, что именно отдаёт CCAPI вашей камеры.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("Путь") }, modifier = Modifier.fillMaxWidth())
        Button(onClick = { vm.rawGet(path) { result = it } }) { Text("GET") }
        Divider()
        Text(result, modifier = Modifier.verticalScroll(rememberScrollState()).weight(1f), style = MaterialTheme.typography.bodySmall)
    }
}
