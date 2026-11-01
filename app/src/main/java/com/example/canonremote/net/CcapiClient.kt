package com.example.canonremote.net

import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Minimal client for Canon's CCAPI (Camera Control API).
 *
 * CCAPI is a plain HTTP(S) REST API served *by the camera itself* over Wi-Fi.
 * It is enabled on-camera (Menu > Communication functions > Camera Control API)
 * with just a local username/password you choose there - no Canon ID, no
 * internet connection, no Canon account of any kind.
 *
 * The exact set of endpoint paths differs slightly between CCAPI protocol
 * versions (ver100, ver110, ver120, ver130, ver140 ...), which Canon has
 * revised across firmware updates. Rather than hard-coding one version, this
 * client:
 *  1. GETs the camera's root "/ccapi/" endpoint, which returns the list of
 *     protocol versions the camera actually supports, each with its own
 *     base path.
 *  2. Picks the newest version offered and uses it for everything else.
 *
 * If your camera's firmware exposes endpoints under slightly different
 * names than the ones used here, use the in-app "Raw request" debug screen
 * to inspect "/ccapi/" and adjust the path - no need to touch this file for
 * that.
 */
class CcapiClient(
    private val baseUrl: String,          // e.g. "https://192.168.1.2:443" or "http://192.168.1.2:8080"
    private val username: String?,
    private val password: String?,
    trustSelfSignedCert: Boolean = true,
) {
    private val client: OkHttpClient = run {
        val builder = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
        if (trustSelfSignedCert && baseUrl.startsWith("https")) {
            val trustAll = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            }
            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
            builder.sslSocketFactory(sslContext.socketFactory, trustAll)
            builder.hostnameVerifier { _, _ -> true }
        }
        builder.build()
    }

    /** Protocol-version base path discovered from GET /ccapi/, e.g. "/ccapi/ver140". Filled by [discoverVersion]. */
    var apiBase: String? = null
        private set

    private fun authHeader(): String? =
        if (!username.isNullOrEmpty()) Credentials.basic(username, password ?: "") else null

    private fun requestBuilder(path: String): Request.Builder {
        val url = if (path.startsWith("http")) path else baseUrl.trimEnd('/') + path
        val b = Request.Builder().url(url)
        authHeader()?.let { b.header("Authorization", it) }
        return b
    }

    @Throws(IOException::class)
    fun get(path: String): Response = client.newCall(requestBuilder(path).get().build()).execute()

    @Throws(IOException::class)
    fun put(path: String, json: JSONObject? = null): Response {
        val body = (json ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())
        return client.newCall(requestBuilder(path).put(body).build()).execute()
    }

    @Throws(IOException::class)
    fun post(path: String, json: JSONObject? = null): Response {
        val body = (json ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())
        return client.newCall(requestBuilder(path).post(body).build()).execute()
    }

    @Throws(IOException::class)
    fun delete(path: String): Response = client.newCall(requestBuilder(path).delete().build()).execute()

    /** Opens a streaming GET for the MJPEG live-view feed; caller must close the response/body. */
    @Throws(IOException::class)
    fun openStream(path: String): Response = client.newCall(requestBuilder(path).get().build()).execute()

    /**
     * Calls GET /ccapi/ and picks the highest-numbered protocol version the
     * camera advertises, storing its base path (e.g. "/ccapi/ver140") in [apiBase].
     * Returns the raw JSON for display/debugging.
     */
    @Throws(IOException::class)
    fun discoverVersion(): JSONObject {
        get("/ccapi/").use { resp ->
            if (!resp.isSuccessful) throw IOException("GET /ccapi/ failed: HTTP ${resp.code}")
            val text = resp.body?.string() ?: "{}"
            val root = JSONObject(text)
            // Known shape: {"url":["/ccapi/ver100","/ccapi/ver110",...]}
            var best: String? = null
            val urls: JSONArray? = root.optJSONArray("url")
            if (urls != null) {
                for (i in 0 until urls.length()) {
                    val p = urls.getString(i)
                    if (best == null || p > best!!) best = p
                }
            }
            if (best == null) {
                // Fallback shape seen on some firmware: {"ver100":{...}, "ver110":{...}}
                val keys = root.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    if (k.startsWith("ver")) {
                        val candidate = "/ccapi/$k"
                        if (best == null || candidate > best!!) best = candidate
                    }
                }
            }
            apiBase = best ?: "/ccapi/ver100"
            return root
        }
    }

    fun path(suffix: String): String = (apiBase ?: "/ccapi/ver100") + suffix
}
