package com.firepad.remote

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.Socket
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class DiscoveredDevice(
    val ip: String,
    val name: String = "",
    val hasPin8080: Boolean = false,
    val hasAdb5555: Boolean = false
)

data class ScanResult(
    val devices: List<DiscoveredDevice>,
    val subnets: List<String> = emptyList()
)

data class CommandStatus(
    val ok: Boolean,
    val via: String = "",
    val error: String? = null
)

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
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun getLocalIpv4Prefixes(): List<String> {
        val prefixes = linkedSetOf<String>()
        // 1) Prefer active Wi-Fi LinkProperties (ignores 4G/5G cellular CGNAT on LG G8X)
        runCatching {
            val cm = context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            for (network in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(network) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(network) ?: continue
                for (la in lp.linkAddresses) {
                    val addr = la.address
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val parts = addr.hostAddress?.split(".") ?: continue
                        if (parts.size == 4) prefixes.add("${parts[0]}.${parts[1]}.${parts[2]}")
                    }
                }
            }
        }
        // 2) Fallback: WifiManager IPv4
        if (prefixes.isEmpty()) {
            runCatching {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                val ipInt = wifi.connectionInfo?.ipAddress ?: 0
                if (ipInt != 0) {
                    val b0 = ipInt and 0xff
                    val b1 = (ipInt shr 8) and 0xff
                    val b2 = (ipInt shr 16) and 0xff
                    prefixes.add("$b0.$b1.$b2")
                }
            }
        }
        // 3) Fallback: NetworkInterface excluding cellular/VPN tunnels
        if (prefixes.isEmpty()) {
            runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val iface = ifaces.nextElement()
                    val n = iface.name.lowercase()
                    if (!iface.isUp || iface.isLoopback) continue
                    if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("pdp") || n.startsWith("wwan") || n.startsWith("tun")) continue
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress && addr.isSiteLocalAddress) {
                            val parts = addr.hostAddress?.split(".") ?: continue
                            if (parts.size == 4) prefixes.add("${parts[0]}.${parts[1]}.${parts[2]}")
                        }
                    }
                }
            }
        }
        return prefixes.toList()
    }

    private fun isPortOpen(ip: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(ip, port), timeoutMs)
                true
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun readDialFriendlyName(ip: String): String {
        for (port in listOf(8009, 8008)) {
            try {
                val req = Request.Builder().url("http://$ip:$port/ssdp/device-desc.xml").get().build()
                client.newCall(req).execute().use { resp ->
                    val xml = resp.body?.string().orEmpty()
                    val m = Regex("<friendlyName>([^<]+)</friendlyName>", RegexOption.IGNORE_CASE).find(xml)
                    if (m != null) return m.groupValues[1].trim()
                }
            } catch (_: Throwable) {}
        }
        return ""
    }

    suspend fun discoverFireSticks(bridgeUrl: String = ""): ScanResult = withContext(Dispatchers.IO) {
        if (bridgeUrl.isNotBlank()) {
            val bridgeFound = callBridge(bridgeUrl, JSONObject().put("action", "discover"))
            if (bridgeFound != null && bridgeFound.optBoolean("ok")) {
                val arr = bridgeFound.optJSONArray("devices")
                if (arr != null) {
                    val out = mutableListOf<DiscoveredDevice>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val ports = obj.optJSONObject("ports")
                        out.add(
                            DiscoveredDevice(
                                ip = obj.optString("ip"),
                                name = obj.optString("name"),
                                hasPin8080 = ports?.optBoolean("p8080") ?: true,
                                hasAdb5555 = ports?.optBoolean("p5555") ?: false
                            )
                        )
                    }
                    if (out.isNotEmpty()) return@withContext ScanResult(out, listOf("bridge"))
                }
            }
        }

        val foundMap = linkedMapOf<String, DiscoveredDevice>()

        // 1) Fast parallel /24 Wi-Fi subnet port scan (reliable on all Android phones including LG G8X)
        val prefixes = getLocalIpv4Prefixes()
        val candidates = prefixes.flatMap { prefix -> (2..254).map { "$prefix.$it" } }
        coroutineScope {
            candidates.chunked(32).forEach { batch ->
                val checks = batch.map { ip ->
                    async {
                        val p8080 = isPortOpen(ip, 8080, 420)
                        val p8009 = isPortOpen(ip, 8009, 420)
                        val p8008 = if (!p8009) isPortOpen(ip, 8008, 350) else false
                        val p5555 = isPortOpen(ip, 5555, 350)
                        if (p8080 || p8009 || p8008 || p5555) {
                            val name = if (p8009 || p8008) readDialFriendlyName(ip) else ""
                            DiscoveredDevice(ip = ip, name = name, hasPin8080 = p8080 || p8009 || p8008, hasAdb5555 = p5555)
                        } else null
                    }
                }.awaitAll()
                checks.filterNotNull().forEach { dev -> foundMap[dev.ip] = dev }
            }
        }

        // 2) Supplement with SSDP multicast discovery bound directly to Wi-Fi interface
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("firepad_ssdp").apply { runCatching { acquire() } }
        try {
            val socket = MulticastSocket()
            socket.soTimeout = 1000
            runCatching {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val ni = ifaces.nextElement()
                    if (ni.isUp && !ni.isLoopback && ni.name.startsWith("wlan", true)) {
                        socket.networkInterface = ni
                        break
                    }
                }
            }
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
            val deadline = System.currentTimeMillis() + 1200
            while (System.currentTimeMillis() < deadline) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    socket.receive(pkt)
                    val ip = pkt.address?.hostAddress ?: continue
                    if (!ip.endsWith(".1") && !foundMap.containsKey(ip)) {
                        val p8080 = isPortOpen(ip, 8080, 350)
                        val p5555 = isPortOpen(ip, 5555, 350)
                        val name = readDialFriendlyName(ip)
                        foundMap[ip] = DiscoveredDevice(ip = ip, name = name, hasPin8080 = p8080, hasAdb5555 = p5555)
                    }
                } catch (_: Throwable) {
                    break
                }
            }
            socket.close()
        } catch (_: Throwable) {
        } finally {
            runCatching { if (lock.isHeld) lock.release() }
        }

        val sorted = foundMap.values.sortedByDescending {
            it.name.contains("fire", true) || it.name.contains("amazon", true) || it.hasPin8080
        }
        ScanResult(sorted, prefixes)
    }

    private fun callBridge(bridgeUrl: String, payload: JSONObject): JSONObject? {
        val base = bridgeUrl.trim().trimEnd('/')
        if (base.isBlank()) return null
        return try {
            val req = Request.Builder()
                .url("$base/api")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string().orEmpty()
                if (txt.isNotBlank()) JSONObject(txt) else null
            }
        } catch (_: Throwable) {
            null
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
                    .header("User-Agent", "okhttp/4.10.0")
                    .post(body.toString().toRequestBody(JSON_MEDIA))
                if (!token.isNullOrBlank()) builder.header("X-Client-Token", token)
                client.newCall(builder.build()).execute().use { resp ->
                    return Pair(resp.code, resp.body?.string().orEmpty())
                }
            } catch (e: Throwable) {
                lastErr = e.message ?: "Network error"
            }
        }
        return Pair(500, lastErr)
    }

    suspend fun requestPin(host: String, bridgeUrl: String = ""): CommandStatus = withContext(Dispatchers.IO) {
        if (bridgeUrl.isNotBlank()) {
            val r = callBridge(bridgeUrl, JSONObject().put("action", "pin_display").put("host", host))
            if (r != null) {
                return@withContext if (r.optBoolean("ok")) CommandStatus(true, "bridge") else CommandStatus(false, error = r.optString("error"))
            }
        }
        runCatching {
            val wake = Request.Builder().url("http://$host:8009/apps/FireTVRemote").post("".toRequestBody()).build()
            client.newCall(wake).execute().close()
            delay(500)
        }
        val (code, raw) = post(host, "/v1/FireTV/pin/display", null, JSONObject().put("friendlyName", "FirePad Android"))
        if (code in 200..299) CommandStatus(true, "pin_display") else CommandStatus(false, error = "HTTP $code: $raw")
    }

    suspend fun verifyPin(host: String, pin: String, bridgeUrl: String = ""): Pair<String?, String?> = withContext(Dispatchers.IO) {
        if (bridgeUrl.isNotBlank()) {
            val r = callBridge(bridgeUrl, JSONObject().put("action", "pin_verify").put("host", host).put("pin", pin))
            if (r != null) {
                val tok = r.optString("token")
                return@withContext if (r.optBoolean("ok") && tok.isNotBlank()) Pair(tok, null) else Pair(null, r.optString("error", "Verification failed"))
            }
        }
        val (code, raw) = post(host, "/v1/FireTV/pin/verify", null, JSONObject().put("pin", pin))
        val token = runCatching { JSONObject(raw).optString("description") }.getOrNull()
        if (code in 200..299 && !token.isNullOrBlank()) Pair(token, null) else Pair(null, "Verification failed ($code)")
    }

    suspend fun sendKey(host: String, token: String, key: String, bridgeUrl: String = ""): CommandStatus = withContext(Dispatchers.IO) {
        if (bridgeUrl.isNotBlank()) {
            val r = callBridge(bridgeUrl, JSONObject().put("action", "pin_key").put("host", host).put("token", token).put("key", key))
            if (r != null) {
                return@withContext if (r.optBoolean("ok")) CommandStatus(true, r.optString("via", "bridge")) else CommandStatus(false, error = r.optString("error"))
            }
        }
        val map = mapOf(
            "UP" to "/v1/FireTV?action=dpad_up",
            "DOWN" to "/v1/FireTV?action=dpad_down",
            "LEFT" to "/v1/FireTV?action=dpad_left",
            "RIGHT" to "/v1/FireTV?action=dpad_right",
            "SELECT" to "/v1/FireTV?action=select",
            "BACK" to "/v1/FireTV?action=back",
            "HOME" to "/v1/FireTV?action=home",
            "MENU" to "/v1/FireTV?action=menu",
            "PLAY_PAUSE" to "/v1/media?action=play",
            "REWIND" to "/v1/media?action=scan&direction=back",
            "FAST_FORWARD" to "/v1/media?action=scan&direction=forward",
            "POWER" to "/v1/FireTV?action=sleep",
            "SEARCH" to "/v1/FireTV?action=search"
        )
        val path = map[key] ?: "/v1/FireTV?action=$key"
        val (code, raw) = post(host, path, token)
        if (code in 200..299) CommandStatus(true, "lightning") else CommandStatus(false, error = "HTTP $code: $raw")
    }

    suspend fun launchApp(host: String, token: String, pkg: String, bridgeUrl: String = ""): CommandStatus = withContext(Dispatchers.IO) {
        if (pkg.contains("setting", true)) return@withContext openSettings(host, token, bridgeUrl)
        if (bridgeUrl.isNotBlank()) {
            val r = callBridge(bridgeUrl, JSONObject().put("action", "pin_launch").put("host", host).put("token", token).put("packageName", pkg))
            if (r != null) return@withContext if (r.optBoolean("ok")) CommandStatus(true, "bridge") else CommandStatus(false, error = r.optString("error"))
        }
        val (code, raw) = post(host, "/v1/FireTV/app/$pkg", token)
        if (code in 200..299) CommandStatus(true, "app") else CommandStatus(false, error = "HTTP $code: $raw")
    }

    suspend fun sendText(host: String, token: String, text: String, bridgeUrl: String = ""): CommandStatus = withContext(Dispatchers.IO) {
        if (bridgeUrl.isNotBlank()) {
            val r = callBridge(bridgeUrl, JSONObject().put("action", "pin_text").put("host", host).put("token", token).put("text", text))
            if (r != null) return@withContext if (r.optBoolean("ok")) CommandStatus(true, "bridge") else CommandStatus(false, error = r.optString("error"))
        }
        val body = JSONObject().put("text", text)
        for (p in listOf("/v1/FireTV/keyboard", "/v1/FireTV/text")) {
            val (code, _) = post(host, p, token, body)
            if (code in 200..299) return@withContext CommandStatus(true, "keyboard")
        }
        CommandStatus(false, error = "Open a system text box on TV first, or connect Bridge for ADB text input.")
    }

    suspend fun openSettings(host: String, token: String, bridgeUrl: String = ""): CommandStatus = withContext(Dispatchers.IO) {
        if (bridgeUrl.isNotBlank()) {
            val r = callBridge(bridgeUrl, JSONObject().put("action", "pin_key").put("host", host).put("token", token).put("key", "SETTINGS"))
            if (r != null) return@withContext if (r.optBoolean("ok")) CommandStatus(true, r.optString("via", "bridge")) else CommandStatus(false, error = r.optString("error"))
        }
        val (actCode, actRaw) = post(host, "/v1/FireTV?action=settings", token)
        if (actCode in 200..299 && !actRaw.contains("error", true)) {
            return@withContext CommandStatus(true, "lightning_action")
        }
        val pkgs = listOf("com.amazon.tv.settings.v2", "com.amazon.tv.settings", "com.amazon.vega.settings", "com.android.tv.settings")
        for (pkg in pkgs) {
            val (c, _) = post(host, "/v1/FireTV/app/$pkg", token)
            if (c in 200..299) return@withContext CommandStatus(true, "pkg:$pkg")
        }
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

    suspend fun sendVolume(host: String, token: String, kind: String, bridgeUrl: String = ""): CommandStatus = withContext(Dispatchers.IO) {
        if (bridgeUrl.isNotBlank()) {
            val r = callBridge(bridgeUrl, JSONObject().put("action", "pin_key").put("host", host).put("token", token).put("key", kind))
            if (r != null) return@withContext if (r.optBoolean("ok")) CommandStatus(true, r.optString("via", "bridge")) else CommandStatus(false, error = r.optString("error"))
        }
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
        CommandStatus(false, error = "Enable HDMI-CEC in TV Settings > Display & Sounds, or run Termux Bridge for ADB volume.")
    }

    suspend fun openDisplayMirroring(
        host: String,
        token: String,
        displayTileOffset: Int = 3,
        mirrorRowOffset: Int = 3,
        bridgeUrl: String = ""
    ): CommandStatus = withContext(Dispatchers.IO) {
        if (bridgeUrl.isNotBlank()) {
            val r = callBridge(
                bridgeUrl,
                JSONObject()
                    .put("action", "open_display_mirroring")
                    .put("host", host)
                    .put("token", token)
                    .put("displayTileOffset", displayTileOffset)
                    .put("mirrorRowOffset", mirrorRowOffset)
            )
            if (r != null) return@withContext if (r.optBoolean("ok")) CommandStatus(true, r.optString("via", "bridge")) else CommandStatus(false, error = r.optString("error"))
        }
        val opened = openSettings(host, token, "")
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
