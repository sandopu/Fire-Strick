package com.firepad.remote

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val client = FireTvClient(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF6B2C),
                    onPrimary = Color.White,
                    background = Color(0xFF0B0D12),
                    onBackground = Color.White,
                    surface = Color(0xFF131720),
                    onSurface = Color.White,
                    onSurfaceVariant = Color(0xFF94A3B8)
                )
            ) {
                FirePadScreen(client)
            }
        }
    }
}

@Composable
fun FirePadScreen(client: FireTvClient) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("firepad_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var pin by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var bridgeUrl by remember { mutableStateOf(prefs.getString("bridge_url", "") ?: "") }
    var displayTileOffset by remember { mutableIntStateOf(prefs.getInt("display_offset", 3)) }
    var mirrorRowOffset by remember { mutableIntStateOf(prefs.getInt("mirror_offset", 3)) }

    var discovered by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var showSetup by remember { mutableStateOf(token.isBlank()) }
    var showKeyboard by remember { mutableStateOf(false) }
    var kbText by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(if (token.isNotBlank() && host.isNotBlank()) "Paired: $host" else "Tap Scan Wi-Fi to find your Fire Stick")
    }

    val bg = Color(0xFF0B0D12)
    val cardBg = Color(0xFF131720)
    val keyBg = Color(0xFF1B2230)
    val accent = Color(0xFFFF6B2C)
    val border = Color(0x1FFFFFFF)

    fun saveConnection(newHost: String, newToken: String) {
        prefs.edit().putString("host", newHost).putString("token", newToken).apply()
    }

    fun saveBridge(url: String) {
        prefs.edit().putString("bridge_url", url.trim()).apply()
    }

    fun requirePaired(): Boolean {
        if (host.isBlank() || token.isBlank()) {
            showSetup = true
            status = "Pair with your Fire Stick IP & 4-digit PIN first"
            return false
        }
        return true
    }

    val quickApps = listOf(
        "Prime Video" to "com.amazon.avod",
        "Netflix" to "com.netflix.ninja",
        "YouTube" to "com.amazon.firetv.youtube",
        "Disney+" to "com.disney.disneyplus",
        "Spotify" to "com.spotify.tv.android",
        "Settings" to "com.amazon.tv.settings.v2"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .systemBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header Row (never wraps or collapses)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(
                    text = "FirePad v1.1.1",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = status,
                    color = Color(0xFFFF9B70),
                    fontSize = 12.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            OutlinedButton(
                onClick = { showSetup = !showSetup },
                border = BorderStroke(1.dp, border),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                modifier = Modifier.height(44.dp)
            ) {
                Text(
                    text = if (showSetup) "Hide Setup" else if (token.isNotBlank()) "Setup" else "Connect",
                    color = Color.White,
                    fontSize = 13.sp,
                    maxLines = 1
                )
            }
        }

        // Connection & Scan Card
        if (showSetup) {
            Surface(
                color = cardBg,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, border),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        text = "Connect Fire TV Stick",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it.trim() },
                            label = { Text("Firestick IP", maxLines = 1) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                scope.launch {
                                    scanning = true
                                    status = "Scanning Wi-Fi subnet for Fire Stick..."
                                    val result = client.discoverFireSticks(bridgeUrl)
                                    discovered = result.devices
                                    scanning = false
                                    if (result.devices.isNotEmpty()) {
                                        host = result.devices.first().ip
                                        status = "Found ${result.devices.size} device(s). Selected $host"
                                    } else if (result.subnets.isEmpty()) {
                                        status = "Phone is not on Wi-Fi. Connect to TV Wi-Fi or enter IP manually."
                                    } else {
                                        status = "Scanned ${result.subnets.joinToString() { "$it.x" }} — 0 found. Enter TV IP manually."
                                    }
                                }
                            },
                            enabled = !scanning,
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text(
                                text = if (scanning) "Scanning..." else "Scan",
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }

                    if (discovered.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Detected Devices (tap to select):", color = Color(0xFF94A3B8), fontSize = 12.sp)
                            discovered.forEach { dev ->
                                OutlinedButton(
                                    onClick = {
                                        host = dev.ip
                                        status = "Selected ${dev.ip}. Tap Send PIN next."
                                    },
                                    border = BorderStroke(1.dp, if (host == dev.ip) accent else border),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.fillMaxWidth(),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = if (dev.name.isNotBlank()) "${dev.ip} · ${dev.name}" else dev.ip,
                                            color = Color.White,
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Text(
                                            text = buildString {
                                                if (dev.hasPin8080) append("PIN ")
                                                if (dev.hasAdb5555) append("ADB")
                                            }.trim(),
                                            color = accent,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Button(
                        onClick = {
                            if (host.isBlank()) {
                                status = "Enter Fire Stick IP or tap Scan first"
                            } else {
                                scope.launch {
                                    status = "Requesting 4-digit PIN on TV ($host)..."
                                    val r = client.requestPin(host, bridgeUrl)
                                    status = if (r.ok) "Enter the 4-digit PIN shown on your TV" else (r.error ?: "PIN failed")
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().height(48.dp)
                    ) {
                        Text("1. Send 4-Digit PIN to TV", color = Color.White, maxLines = 1)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = pin,
                            onValueChange = { pin = it.filter { ch -> ch.isDigit() }.take(4) },
                            label = { Text("4-Digit PIN", maxLines = 1) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                if (host.isBlank() || pin.length != 4) {
                                    status = "Enter IP and 4-digit PIN"
                                } else {
                                    scope.launch {
                                        val (tok, err) = client.verifyPin(host, pin, bridgeUrl)
                                        if (tok != null) {
                                            token = tok
                                            saveConnection(host, tok)
                                            showSetup = false
                                            status = "Paired: $host"
                                        } else {
                                            status = err ?: "Verify failed"
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            Text("2. Verify", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        }
                    }

                    // Optional Termux / PC Bridge URL for ADB Volume & Direct Mirroring
                    OutlinedTextField(
                        value = bridgeUrl,
                        onValueChange = {
                            bridgeUrl = it
                            saveBridge(it)
                        },
                        label = { Text("Optional Bridge URL (e.g. http://127.0.0.1:8765)", maxLines = 1) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Mirroring Path Calibration Steppers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            color = bg,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, border),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Display → $displayTileOffset", color = Color(0xFFCBD5E1), fontSize = 11.sp, maxLines = 1)
                                Row {
                                    TextButton(onClick = {
                                        displayTileOffset = (displayTileOffset - 1).coerceAtLeast(0)
                                        prefs.edit().putInt("display_offset", displayTileOffset).apply()
                                    }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("-", color = Color.White) }
                                    TextButton(onClick = {
                                        displayTileOffset = (displayTileOffset + 1).coerceAtMost(8)
                                        prefs.edit().putInt("display_offset", displayTileOffset).apply()
                                    }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("+", color = accent) }
                                }
                            }
                        }
                        Surface(
                            color = bg,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, border),
                            modifier = Modifier.weight(1f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Mirror ↓ $mirrorRowOffset", color = Color(0xFFCBD5E1), fontSize = 11.sp, maxLines = 1)
                                Row {
                                    TextButton(onClick = {
                                        mirrorRowOffset = (mirrorRowOffset - 1).coerceAtLeast(0)
                                        prefs.edit().putInt("mirror_offset", mirrorRowOffset).apply()
                                    }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("-", color = Color.White) }
                                    TextButton(onClick = {
                                        mirrorRowOffset = (mirrorRowOffset + 1).coerceAtMost(8)
                                        prefs.edit().putInt("mirror_offset", mirrorRowOffset).apply()
                                    }, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(32.dp)) { Text("+", color = accent) }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Quick App Strip
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(quickApps) { (label, pkg) ->
                OutlinedButton(
                    onClick = {
                        if (!requirePaired()) return@OutlinedButton
                        scope.launch {
                            val r = client.launchApp(host, token, pkg, bridgeUrl)
                            if (!r.ok) status = r.error ?: "Failed to launch $label"
                        }
                    },
                    border = BorderStroke(1.dp, border),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    modifier = Modifier.height(44.dp)
                ) {
                    Text(label, color = Color.White, fontSize = 13.sp, maxLines = 1)
                }
            }
        }

        // Primary Cast (Settings -> Display -> Enable Display Mirroring) & Settings Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    if (!requirePaired()) return@Button
                    scope.launch {
                        status = "Opening Settings → Display → Enable Display Mirroring..."
                        val r = client.openDisplayMirroring(host, token, displayTileOffset, mirrorRowOffset, bridgeUrl)
                        status = if (r.ok) "Display Mirroring opened (${r.via})" else (r.error ?: "Failed")
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Text("Cast · Mirroring", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            }

            Button(
                onClick = {
                    if (!requirePaired()) return@Button
                    scope.launch {
                        status = "Opening TV Settings..."
                        val r = client.openSettings(host, token, bridgeUrl)
                        status = if (r.ok) "Opened Settings (${r.via})" else (r.error ?: "Settings failed")
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
                modifier = Modifier.weight(1f).height(52.dp)
            ) {
                Text("TV Settings", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
            }
        }

        // Ergonomic D-Pad Card (Fixed sizes so it NEVER collapses on LG G8X)
        Surface(
            color = cardBg,
            shape = RoundedCornerShape(24.dp),
            border = BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Centered D-Pad (196dp wide — fits cleanly on every Android screen)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "UP", bridgeUrl) } },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(60.dp)
                    ) { Text("▲", fontSize = 18.sp, color = Color.White, maxLines = 1) }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "LEFT", bridgeUrl) } },
                            colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(60.dp)
                        ) { Text("◀", fontSize = 18.sp, color = Color.White, maxLines = 1) }

                        Button(
                            onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "SELECT", bridgeUrl) } },
                            colors = ButtonDefaults.buttonColors(containerColor = accent),
                            shape = CircleShape,
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(62.dp)
                        ) { Text("OK", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }

                        Button(
                            onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "RIGHT", bridgeUrl) } },
                            colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                            shape = RoundedCornerShape(16.dp),
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(60.dp)
                        ) { Text("▶", fontSize = 18.sp, color = Color.White, maxLines = 1) }
                    }

                    Button(
                        onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "DOWN", bridgeUrl) } },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.size(60.dp)
                    ) { Text("▼", fontSize = 18.sp, color = Color.White, maxLines = 1) }
                }

                // Navigation Row: Back / Home / Menu
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "BACK", bridgeUrl) } },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Back", color = Color.White, fontSize = 13.sp, maxLines = 1) }

                    Button(
                        onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "HOME", bridgeUrl) } },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF283246)),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Home", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) }

                    Button(
                        onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "MENU", bridgeUrl) } },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Menu", color = Color.White, fontSize = 13.sp, maxLines = 1) }
                }

                // Volume Rocker Strip: Vol - / Mute / Vol +
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            if (!requirePaired()) return@Button
                            scope.launch {
                                val r = client.sendVolume(host, token, "VOL_DOWN", bridgeUrl)
                                status = if (r.ok) "Volume -" else (r.error ?: "Volume failed")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Vol -", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) }

                    Button(
                        onClick = {
                            if (!requirePaired()) return@Button
                            scope.launch {
                                val r = client.sendVolume(host, token, "MUTE", bridgeUrl)
                                status = if (r.ok) "Mute toggled" else (r.error ?: "Mute failed")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Mute", color = Color(0xFFCBD5E1), fontSize = 13.sp, maxLines = 1) }

                    Button(
                        onClick = {
                            if (!requirePaired()) return@Button
                            scope.launch {
                                val r = client.sendVolume(host, token, "VOL_UP", bridgeUrl)
                                status = if (r.ok) "Volume +" else (r.error ?: "Volume failed")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) { Text("Vol +", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1) }
                }

                // Media & Keyboard Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "REWIND", bridgeUrl) } },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) { Text("◀◀", color = Color.White, fontSize = 13.sp, maxLines = 1) }

                    Button(
                        onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "PLAY_PAUSE", bridgeUrl) } },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) { Text("▶ / ❚❚", color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1) }

                    Button(
                        onClick = { if (requirePaired()) scope.launch { client.sendKey(host, token, "FAST_FORWARD", bridgeUrl) } },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) { Text("▶▶", color = Color.White, fontSize = 13.sp, maxLines = 1) }

                    Button(
                        onClick = { if (requirePaired()) showKeyboard = true },
                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                        shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        modifier = Modifier.weight(1f).height(46.dp)
                    ) { Text("Keys", color = Color.White, fontSize = 12.sp, maxLines = 1) }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }

    if (showKeyboard) {
        AlertDialog(
            onDismissRequest = { showKeyboard = false },
            containerColor = cardBg,
            title = { Text("TV Keyboard", color = Color.White) },
            text = {
                OutlinedTextField(
                    value = kbText,
                    onValueChange = { kbText = it },
                    label = { Text("Type text or search phrase") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val r = client.sendText(host, token, kbText, bridgeUrl)
                            if (r.ok) {
                                client.sendKey(host, token, "SELECT", bridgeUrl)
                                kbText = ""
                                showKeyboard = false
                            } else {
                                status = r.error ?: "Failed to send text"
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) { Text("Send", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { showKeyboard = false }) { Text("Close", color = Color(0xFF94A3B8)) }
            }
        )
    }
}
