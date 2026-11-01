package com.example.canonremote.model

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.canonremote.net.CameraRepository
import com.example.canonremote.net.CcapiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ConnState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

class CameraViewModel : ViewModel() {

    var connState by mutableStateOf(ConnState.DISCONNECTED)
        private set
    var errorMessage by mutableStateOf<String?>(null)
        private set
    var deviceName by mutableStateOf<String?>(null)
        private set
    var apiVersion by mutableStateOf<String?>(null)
        private set

    var frame by mutableStateOf<Bitmap?>(null)
        private set
    var liveViewRunning by mutableStateOf(false)
        private set

    var lastCaptureMessage by mutableStateOf<String?>(null)
        private set

    // Current setting readouts (raw CCAPI "value" strings)
    var iso by mutableStateOf<String?>(null)
        private set
    var av by mutableStateOf<String?>(null)
        private set
    var tv by mutableStateOf<String?>(null)
        private set
    var ec by mutableStateOf<String?>(null)
        private set

    private var client: CcapiClient? = null
    private var repo: CameraRepository? = null
    private var liveViewJob: Job? = null

    fun connect(host: String, port: String, useHttps: Boolean, username: String, password: String) {
        connState = ConnState.CONNECTING
        errorMessage = null
        viewModelScope.launch {
            try {
                val scheme = if (useHttps) "https" else "http"
                val base = "$scheme://$host:$port"
                val c = CcapiClient(
                    baseUrl = base,
                    username = username.ifBlank { null },
                    password = password.ifBlank { null },
                )
                val r = CameraRepository(c)
                withContext(Dispatchers.IO) {
                    r.connectAndDiscover()
                    val info = r.deviceInfo()
                    deviceName = info.optString("productname", info.optString("modelid", "Canon camera"))
                }
                client = c
                repo = r
                apiVersion = c.apiBase
                connState = ConnState.CONNECTED
                refreshSettings()
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
                connState = ConnState.ERROR
            }
        }
    }

    fun disconnect() {
        stopLiveView()
        client = null
        repo = null
        connState = ConnState.DISCONNECTED
        frame = null
    }

    fun startLiveView() {
        val r = repo ?: return
        if (liveViewRunning) return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { r.startLiveView() }
                liveViewRunning = true
                liveViewJob = viewModelScope.launch(Dispatchers.IO) {
                    r.liveViewFrames()
                        .catch { e -> errorMessage = "Live view stopped: ${e.message}" }
                        .collect { bmp -> frame = bmp }
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: e.toString()
            }
        }
    }

    fun stopLiveView() {
        liveViewJob?.cancel()
        liveViewJob = null
        liveViewRunning = false
    }

    fun capturePhoto() {
        val r = repo ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { r.capturePhoto() }
                lastCaptureMessage = "Shutter triggered"
            } catch (e: Exception) {
                lastCaptureMessage = "Capture failed: ${e.message}"
            }
        }
    }

    fun pressShutter() = manualShutter("full_press")
    fun releaseShutter() = manualShutter("release")
    fun halfPressShutter() = manualShutter("half_press")

    private fun manualShutter(action: String) {
        val r = repo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                r.manualShutter(action)
            } catch (e: Exception) {
                lastCaptureMessage = "Shutter($action) failed: ${e.message}"
            }
        }
    }

    fun refreshSettings() {
        val r = repo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            iso = runCatching { r.getSetting(CameraRepository.apiPaths.SETTING_ISO).optString("value") }.getOrNull()
            av = runCatching { r.getSetting(CameraRepository.apiPaths.SETTING_AV).optString("value") }.getOrNull()
            tv = runCatching { r.getSetting(CameraRepository.apiPaths.SETTING_TV).optString("value") }.getOrNull()
            ec = runCatching { r.getSetting(CameraRepository.apiPaths.SETTING_EC).optString("value") }.getOrNull()
        }
    }

    fun setIso(value: String) = setSetting(CameraRepository.apiPaths.SETTING_ISO, value) { iso = it }
    fun setAv(value: String) = setSetting(CameraRepository.apiPaths.SETTING_AV, value) { av = it }
    fun setTv(value: String) = setSetting(CameraRepository.apiPaths.SETTING_TV, value) { tv = it }
    fun setEc(value: String) = setSetting(CameraRepository.apiPaths.SETTING_EC, value) { ec = it }

    private fun setSetting(path: String, value: String, onOk: (String) -> Unit) {
        val r = repo ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                r.putSettingValue(path, value)
                onOk(value)
            } catch (e: Exception) {
                lastCaptureMessage = "Set failed: ${e.message}"
            }
        }
    }

    /** For the debug screen: fire an arbitrary GET against the camera and return the raw text. */
    fun rawGet(path: String, onResult: (String) -> Unit) {
        val c = client ?: run { onResult("Not connected"); return }
        viewModelScope.launch(Dispatchers.IO) {
            val text = try {
                c.get(path).use { resp -> "HTTP ${resp.code}\n\n" + (resp.body?.string() ?: "") }
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            withContext(Dispatchers.Main) { onResult(text) }
        }
    }
}
