package com.firepad.remote

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class CommandStatus(val ok: Boolean, val via: String = "", val error: String? = null)

class FireTvClient(private val context: Context) {
    companion object {
        private const val API_KEY = "0987654321"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    private val client: OkHttpClient by lazy {
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        })
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustAll, SecureRandom())
        OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustAll[0] as X509TrustManager)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
    }

    suspend fun discoverFireSticks(): List<String> = withContext(Dispatchers.IO) {
        val found = linkedSetOf<String>()
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("firepad_ssdp").apply { acquire() }
        try {
            val socket = DatagramSocket()
            socket.soTimeout = 1400
            val payload = (
                "M-SEARCH * HTTP/1.1\r\n" +
                "HOST: 239.255.255.250:1900\r\n" +
                "MAN: \"ssdp:discover\"\r\n" +
                "MX: 1\r\n" +
                "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n"
            ).toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            socket.send(DatagramPacket(payload, payload.size, group, 1900))
            val buf = ByteArray(2048)
            val deadline = System.currentTimeMillis() + 1500
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val ip = pkt.address?.hostAddress
                    if (!ip.isNullOrBlank() && isPortOpen(ip, 8080, 450)) {
                        found.add(ip)
                    }
                } catch (_: Exception) {
                    break
                }
            }
            socket.close()
        } finally {
            if (lock.isHeld) lock.release()
        }
        found.toList()
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun post(host: String, path: String, token: String? = null, body: JSONObject = JSONObject()): Pair<Int, String> {
        val urls = listOf("https://$host:8080$path", "http://$host:8080$path")
        var lastErr = "Unreachable"
        for (url in urls) {
            try {
                val builder = Request.Builder()
                    .url(url)
                    .header("X-Api-Key", API_KEY)
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                if (!token.isNullOrBlank()) builder.header("X-Client-Token", token)
                client.newCall(builder.build()).execute().use { resp ->
                    return Pair(resp.code, resp.body?.string().orEmpty())
                }
            } catch (e: Exception) {
                lastErr = e.message ?: "Network error"
            }
        }
        return Pair(500, lastErr)
    }

    suspend fun requestPin(host: String): CommandStatus = withContext(Dispatchers.IO) {
        try {
            val wake = Request.Builder().url("http://$host:8009/apps/FireTVRemote").post("".toRequestBody()).build()
            runCatching { client.newCall(wake).execute().close() }
            delay(500)
        } catch (_: Exception) {}
        val (code, raw) = post(host, "/v1/FireTV/pin/display", null, JSONObject().put("friendlyName", "FirePad Android"))
        if (code in 200..299) CommandStatus(true, "pin_display") else CommandStatus(false, error = "HTTP $code: $raw")
    }

    suspend fun verifyPin(host: String, pin: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val (code, raw) = post(host, "/v1/FireTV/pin/verify", null, JSONObject().put("pin", pin))
        val token = runCatching { JSONObject(raw).optString("description") }.getOrNull()
        if (code in 200..299 && !token.isNullOrBlank()) Pair(token, null) else Pair(null, "Verification failed ($code)")
    }

    suspend fun sendKey(host: String, token: String, action: String): CommandStatus = withContext(Dispatchers.IO) {
        val (code, raw) = post(host, "/v1/FireTV?action=$action", token)
        if (code in 200..299) CommandStatus(true, "lightning") else CommandStatus(false, error = "HTTP $code: $raw")
    }

    // FIX 1: Settings — tries Lightning action, system packages, then Quick Menu macro
    suspend fun openSettings(host: String, token: String): CommandStatus = withContext(Dispatchers.IO) {
        val (actCode, actRaw) = post(host, "/v1/FireTV?action=settings", token)
        if (actCode in 200..299 && !actRaw.contains("error", true)) {
            return@withContext CommandStatus(true, "lightning_action")
        }
        val pkgs = listOf("com.amazon.tv.settings.v2", "com.amazon.tv.settings", "com.amazon.vega.settings", "com.android.tv.settings")
        for (pkg in pkgs) {
            val (c, _) = post(host, "/v1/FireTV/app/$pkg", token)
            if (c in 200..299) return@withContext CommandStatus(true, "pkg:$pkg")
        }
        // Universal Quick Menu Macro: hold HOME -> RIGHT x4 -> SELECT
        val (downCode, _) = post(host, "/v1/FireTV?action=home", token, JSONObject().put("keyAction", JSONObject().put("keyActionType", "keyDown")))
        if (downCode in 200..299) {
            delay(1150)
            post(host, "/v1/FireTV?action=home", token, JSONObject().put("keyAction", JSONObject().put("keyActionType", "keyUp")))
            delay(650)
            repeat(4) {
                post(host, "/v1/FireTV?action=dpad_right", token)
                delay(140)
            }
            delay(150)
            post(host, "/v1/FireTV?action=select", token)
            return@withContext CommandStatus(true, "quick_menu_macro")
        }
        CommandStatus(false, error = "Unable to open Settings")
    }

    // FIX 2: Volume — probes multiple Lightning endpoints and reports CEC requirement cleanly
    suspend fun sendVolume(host: String, token: String, kind: String): CommandStatus = withContext(Dispatchers.IO) {
        val paths = when (kind) {
            "VOL_UP" -> listOf("/v1/FireTV?action=volume_up", "/v1/media?action=volume_up", "/v1/FireTV?action=vol_up", "/v1/FireTV/volume?action=up")
            "VOL_DOWN" -> listOf("/v1/FireTV?action=volume_down", "/v1/media?action=volume_down", "/v1/FireTV?action=vol_down", "/v1/FireTV/volume?action=down")
            else -> listOf("/v1/FireTV?action=mute", "/v1/media?action=mute", "/v1/FireTV/volume?action=mute")
        }
        for (p in paths) {
            val (code, raw) = post(host, p, token)
            if (code in 200..299 && !raw.contains("error", true)) {
                return@withContext CommandStatus(true, "lightning:$p")
            }
        }
        CommandStatus(false, error = "Enable HDMI-CEC Device Control in TV Settings > Display & Sounds, or pair ADB port 5555.")
    }

    // FIX 3: Cast button navigates Settings -> Display & Sounds -> Enable Display Mirroring
    suspend fun openDisplayMirroring(
        host: String,
        token: String,
        displayTileOffset: Int = 3,
        mirrorRowOffset: Int = 3
    ): CommandStatus = withContext(Dispatchers.IO) {
        val opened = openSettings(host, token)
        if (!opened.ok) return@withContext opened
        delay(950)
        repeat(displayTileOffset) {
            post(host, "/v1/FireTV?action=dpad_right", token)
            delay(160)
        }
        post(host, "/v1/FireTV?action=select", token)
        delay(800)
        repeat(mirrorRowOffset) {
            post(host, "/v1/FireTV?action=dpad_down", token)
            delay(160)
        }
        post(host, "/v1/FireTV?action=select", token)
        CommandStatus(true, "settings_display_mirroring")
    }
}
