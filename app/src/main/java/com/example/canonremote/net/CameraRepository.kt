package com.example.canonremote.net

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * High-level operations built on top of [CcapiClient], matching the common
 * CCAPI "shooting" endpoint group. Endpoint names follow Canon's published
 * CCAPI reference; adjust [apiPaths] if your firmware differs (see the
 * in-app Raw Request screen to check what your camera actually exposes).
 */
class CameraRepository(private val client: CcapiClient) {

    object apiPaths {
        const val DEVICE_INFO = "/deviceinformation"
        const val SHUTTER_BUTTON = "/shooting/control/shutterbutton"
        const val SHUTTER_BUTTON_MANUAL = "/shooting/control/shutterbutton/manual"
        const val AF = "/shooting/control/af"
        const val LIVEVIEW = "/shooting/liveview"
        const val LIVEVIEW_FLIP = "/shooting/liveview/flip"
        const val SETTINGS = "/shooting/settings"
        const val SETTING_ISO = "/shooting/settings/iso"
        const val SETTING_AV = "/shooting/settings/av"
        const val SETTING_TV = "/shooting/settings/tv"
        const val SETTING_EC = "/shooting/settings/exposurecompensation"
        const val SETTING_WB = "/shooting/settings/wb"
        const val SETTING_SHOOTMODE = "/shooting/settings/shootingmode"
    }

    fun connectAndDiscover(): JSONObject = client.discoverVersion()

    fun deviceInfo(): JSONObject {
        client.get(client.path(apiPaths.DEVICE_INFO)).use { r ->
            check(r.isSuccessful) { "deviceinformation HTTP ${r.code}" }
            return JSONObject(r.body?.string() ?: "{}")
        }
    }

    /** Starts the camera's live-view pipeline. Call once before reading frames. */
    fun startLiveView(size: String = "medium") {
        val body = JSONObject().put("liveviewsize", size).put("cameradisplay", "keep")
        client.post(client.path(apiPaths.LIVEVIEW), body).use { r ->
            // Some firmware returns 200 even if liveview is already running; ignore 4xx "already started".
            check(r.isSuccessful || r.code == 400) { "start liveview HTTP ${r.code}: ${r.body?.string()}" }
        }
    }

    /** One-shot: triggers autofocus + capture, mirroring a half-then-full shutter press. */
    fun capturePhoto(useAutoFocus: Boolean = true) {
        val body = JSONObject().put("af", useAutoFocus)
        client.post(client.path(apiPaths.SHUTTER_BUTTON), body).use { r ->
            check(r.isSuccessful) { "shutter HTTP ${r.code}: ${r.body?.string()}" }
        }
    }

    /** Manual half-press / full-press / release, for a press-and-hold shutter button in the UI. */
    fun manualShutter(action: String) {
        // action: "half_press" | "full_press" | "release"
        val body = JSONObject().put("action", action)
        client.post(client.path(apiPaths.SHUTTER_BUTTON_MANUAL), body).use { r ->
            check(r.isSuccessful) { "manual shutter ($action) HTTP ${r.code}: ${r.body?.string()}" }
        }
    }

    fun getSetting(path: String): JSONObject {
        client.get(client.path(path)).use { r ->
            check(r.isSuccessful) { "GET $path HTTP ${r.code}" }
            return JSONObject(r.body?.string() ?: "{}")
        }
    }

    fun putSettingValue(path: String, value: String) {
        val body = JSONObject().put("value", value)
        client.put(client.path(path), body).use { r ->
            check(r.isSuccessful) { "PUT $path HTTP ${r.code}: ${r.body?.string()}" }
        }
    }

    /**
     * Streams decoded JPEG frames from the MJPEG (multipart/x-mixed-replace)
     * live-view feed as Bitmaps. Cancel the collecting coroutine to stop.
     */
    fun liveViewFrames(): Flow<Bitmap> = callbackFlow {
        val response = client.openStream(client.path(apiPaths.LIVEVIEW_FLIP))
        if (!response.isSuccessful) {
            response.close()
            throw IllegalStateException("liveview/flip HTTP ${response.code}")
        }
        val input = BufferedInputStream(response.body!!.byteStream())
        val job = Thread {
            try {
                readMjpegFrames(input) { bytes ->
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp != null) {
                        val sent = trySend(bmp)
                        if (sent.isFailure) return@readMjpegFrames false
                    }
                    true
                }
            } catch (_: CancellationException) {
                // normal on close
            } catch (_: Exception) {
                // stream ended / network dropped; just stop
            } finally {
                close()
            }
        }
        job.isDaemon = true
        job.start()
        awaitClose {
            try { response.close() } catch (_: Exception) {}
            try { input.close() } catch (_: Exception) {}
            job.interrupt()
        }
    }
}

/**
 * Reads a multipart/x-mixed-replace MJPEG stream, scanning for JPEG SOI/EOI
 * markers (0xFFD8 ... 0xFFD9) directly, which is robust regardless of the
 * exact multipart boundary string the camera uses.
 * [onFrame] returns false to stop reading.
 */
private fun readMjpegFrames(input: InputStream, onFrame: (ByteArray) -> Boolean) {
    val buffer = ByteArrayOutputStream()
    var prev = -1
    var inFrame = false
    while (true) {
        val b = input.read()
        if (b == -1) break
        if (!inFrame) {
            if (prev == 0xFF && b == 0xD8) {
                inFrame = true
                buffer.reset()
                buffer.write(0xFF)
                buffer.write(0xD8)
            }
        } else {
            buffer.write(b)
            if (prev == 0xFF && b == 0xD9) {
                inFrame = false
                val frame = buffer.toByteArray()
                if (!onFrame(frame)) return
            }
        }
        prev = b
    }
}
