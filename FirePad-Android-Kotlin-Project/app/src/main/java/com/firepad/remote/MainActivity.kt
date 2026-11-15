package com.firepad.remote

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

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

private data class QuickAppEntry(
    val name: String,
    val pkg: String,
    val badge: String,
    val badgeColor: Color
)

@Composable
fun FirePadScreen(client: FireTvClient) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("firepad_prefs", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
    var pin by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var bridgeUrl by remember { mutableStateOf(prefs.getString("bridge_url", "") ?: "") }
    var displayTileOffset by remember { mutableIntStateOf(prefs.getInt("display_offset", 2)) }
    var mirrorRowOffset by remember { mutableIntStateOf(prefs.getInt("mirror_offset", 3)) }

    var activeTab by remember { mutableStateOf(if (token.isBlank()) "setup" else "remote") }
    var discovered by remember { mutableStateOf<List<DiscoveredDevice>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var showKeyboard by remember { mutableStateOf(false) }
    var kbText by remember { mutableStateOf("") }
    var status by remember {
        mutableStateOf(
            if (token.isNotBlank() && host.isNotBlank()) "Paired $host"
            else "Not paired · Tap Setup or Connect TV"
        )
    }

    val bg = Color(0xFF0B0D12)
    val cardBg = Color(0xFF131720)
    val keyBg = Color(0xFF1A202C)
    val wellBg = Color(0xFF0E1118)
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
            activeTab = "setup"
            status = "Pair with your Fire Stick IP & 4-digit PIN first"
            return false
        }
        return true
    }

    fun triggerKey(key: String) {
        if (!requirePaired()) return
        scope.launch {
            val r = client.sendKey(host, token, key, bridgeUrl)
            if (!r.ok) status = r.error ?: "$key failed"
        }
    }

    val quickApps = listOf(
        QuickAppEntry("Prime Video", "com.amazon.avod", "PV", Color(0xFF00A8E1)),
        QuickAppEntry("Netflix", "com.netflix.ninja", "N", Color(0xFFE50914)),
        QuickAppEntry("YouTube", "com.amazon.firetv.youtube", "YT", Color(0xFFFF0000)),
        QuickAppEntry("Disney+", "com.disney.disneyplus", "D+", Color(0xFF113CCF)),
        QuickAppEntry("Spotify", "com.spotify.tv.android", "SP", Color(0xFF1DB954)),
        QuickAppEntry("TV Settings", "com.amazon.tv.settings.v2", "SET", Color(0xFF334155))
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(bg)
            .systemBarsPadding()
            .imePadding()
    ) {
        // Top Header (matches image.png)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "FirePad",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = "v1.2.0",
                    color = Color(0xFF64748B),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1
                )
            }
            Button(
                onClick = { activeTab = if (activeTab == "setup") "remote" else "setup" },
                colors = ButtonDefaults.buttonColors(containerColor = accent),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                modifier = Modifier.height(40.dp)
            ) {
                Text(
                    text = if (host.isNotBlank() && token.isNotBlank()) host else "Connect TV",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
            }
        }

        // Quiet Monospace Connection & Feedback Line (matches image.png)
        Text(
            text = status,
            color = Color(0xFFFF9B70),
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp).padding(bottom = 8.dp)
        )

        // Main Screen Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (activeTab) {
                "remote" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Quick App Strip with colored brand badges (matches image.png)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(quickApps) { app ->
                                OutlinedButton(
                                    onClick = {
                                        if (!requirePaired()) return@OutlinedButton
                                        scope.launch {
                                            val r = client.launchApp(host, token, app.pkg, bridgeUrl)
                                            if (!r.ok) status = r.error ?: "Failed to launch ${app.name}"
                                        }
                                    },
                                    border = BorderStroke(1.dp, border),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
                                    modifier = Modifier.height(44.dp)
                                ) {
                                    Box(
                                        contentAlignment = Alignment.Center,
                                        modifier = Modifier
                                            .size(24.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(app.badgeColor)
                                    ) {
                                        Text(app.badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(app.name, color = Color.White, fontSize = 13.sp, maxLines = 1)
                                }
                            }
                        }

                        // Primary Cast · Mirroring & TV Settings Row (matches image.png)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Text("Cast · Mirroring", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                            }

                            OutlinedButton(
                                onClick = {
                                    if (!requirePaired()) return@OutlinedButton
                                    scope.launch {
                                        status = "Opening TV Settings..."
                                        val r = client.openSettings(host, token, bridgeUrl)
                                        status = if (r.ok) "Opened Settings (${r.via})" else (r.error ?: "Settings failed")
                                    }
                                },
                                border = BorderStroke(1.dp, border),
                                shape = RoundedCornerShape(16.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp),
                                modifier = Modifier.weight(1f).height(48.dp)
                            ) {
                                Text("TV Settings", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1)
                            }
                        }

                        // Main Ergonomic Remote Card matching image.png (Left Rail | Center D-Pad | Right Vol Rocker)
                        Surface(
                            color = cardBg,
                            shape = RoundedCornerShape(24.dp),
                            border = BorderStroke(1.dp, border),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Left Quick Rail (50.dp)
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(
                                            onClick = { triggerKey("SEARCH") },
                                            colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                            shape = RoundedCornerShape(14.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.width(50.dp).height(48.dp)
                                        ) { Text("Mic", color = accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }

                                        Button(
                                            onClick = { if (requirePaired()) showKeyboard = true },
                                            colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                            shape = RoundedCornerShape(14.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.width(50.dp).height(48.dp)
                                        ) { Text("Keys", color = Color.White, fontSize = 11.sp, maxLines = 1) }

                                        Button(
                                            onClick = { triggerKey("POWER") },
                                            colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                            shape = RoundedCornerShape(14.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.width(50.dp).height(48.dp)
                                        ) { Text("Sleep", color = Color(0xFFFB7185), fontSize = 11.sp, maxLines = 1) }
                                    }

                                    // Center Tactile D-Pad (168.dp wide — never overflows on 360dp phones)
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Button(
                                            onClick = { triggerKey("UP") },
                                            colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                            shape = RoundedCornerShape(14.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.size(52.dp)
                                        ) { Text("▲", fontSize = 16.sp, color = Color.White, maxLines = 1) }

                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Button(
                                                onClick = { triggerKey("LEFT") },
                                                colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                                shape = RoundedCornerShape(14.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.size(52.dp)
                                            ) { Text("◀", fontSize = 16.sp, color = Color.White, maxLines = 1) }

                                            Button(
                                                onClick = { triggerKey("SELECT") },
                                                colors = ButtonDefaults.buttonColors(containerColor = accent),
                                                shape = CircleShape,
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.size(54.dp)
                                            ) { Text("OK", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1) }

                                            Button(
                                                onClick = { triggerKey("RIGHT") },
                                                colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                                shape = RoundedCornerShape(14.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.size(52.dp)
                                            ) { Text("▶", fontSize = 16.sp, color = Color.White, maxLines = 1) }
                                        }

                                        Button(
                                            onClick = { triggerKey("DOWN") },
                                            colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                            shape = RoundedCornerShape(14.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.size(52.dp)
                                        ) { Text("▼", fontSize = 16.sp, color = Color.White, maxLines = 1) }
                                    }

                                    // Right Volume Rocker (56.dp wide)
                                    Surface(
                                        color = wellBg,
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.dp, border)
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(4.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Button(
                                                onClick = {
                                                    if (!requirePaired()) return@Button
                                                    scope.launch {
                                                        val r = client.sendVolume(host, token, "VOL_UP", bridgeUrl)
                                                        status = if (r.ok) "Volume +" else (r.error ?: "Volume failed")
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                                shape = RoundedCornerShape(12.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.width(48.dp).height(46.dp)
                                            ) { Text("Vol+", color = accent, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }

                                            Button(
                                                onClick = {
                                                    if (!requirePaired()) return@Button
                                                    scope.launch {
                                                        val r = client.sendVolume(host, token, "MUTE", bridgeUrl)
                                                        status = if (r.ok) "Mute toggled" else (r.error ?: "Mute failed")
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                                shape = RoundedCornerShape(12.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.width(48.dp).height(44.dp)
                                            ) { Text("Mute", color = Color(0xFFCBD5E1), fontSize = 11.sp, maxLines = 1) }

                                            Button(
                                                onClick = {
                                                    if (!requirePaired()) return@Button
                                                    scope.launch {
                                                        val r = client.sendVolume(host, token, "VOL_DOWN", bridgeUrl)
                                                        status = if (r.ok) "Volume -" else (r.error ?: "Volume failed")
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                                shape = RoundedCornerShape(12.dp),
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.width(48.dp).height(46.dp)
                                            ) { Text("Vol-", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1) }
                                        }
                                    }
                                }

                                // Navigation Row (Back / Home / Menu)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { triggerKey("BACK") },
                                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.weight(1f).height(46.dp)
                                    ) { Text("← Back", color = Color.White, fontSize = 12.sp, maxLines = 1) }

                                    Button(
                                        onClick = { triggerKey("HOME") },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252D3D)),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.weight(1f).height(46.dp)
                                    ) { Text("Home", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp, maxLines = 1) }

                                    Button(
                                        onClick = { triggerKey("MENU") },
                                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.weight(1f).height(46.dp)
                                    ) { Text("Menu", color = Color.White, fontSize = 12.sp, maxLines = 1) }
                                }

                                // Media Transport Row (◀◀ / ▶ Play / ▶▶)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = { triggerKey("REWIND") },
                                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.weight(1f).height(44.dp)
                                    ) { Text("◀◀", color = Color.White, fontSize = 13.sp, maxLines = 1) }

                                    Button(
                                        onClick = { triggerKey("PLAY_PAUSE") },
                                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.weight(1f).height(44.dp)
                                    ) { Text("▶ Play", color = accent, fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1) }

                                    Button(
                                        onClick = { triggerKey("FAST_FORWARD") },
                                        colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.weight(1f).height(44.dp)
                                    ) { Text("▶▶", color = Color.White, fontSize = 13.sp, maxLines = 1) }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                "touchpad" -> {
                    var padSize by remember { mutableStateOf(IntSize(1, 1)) }
                    var cursorX by remember { mutableFloatStateOf(960f) }
                    var cursorY by remember { mutableFloatStateOf(540f) }
                    var speed by remember { mutableFloatStateOf(1.5f) }
                    var padBadge by remember { mutableStateOf("") }

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(24.dp))
                                .background(cardBg)
                                .border(1.dp, border, RoundedCornerShape(24.dp))
                                .onSizeChanged { padSize = it }
                                .pointerInput(speed) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        val downTime = System.currentTimeMillis()
                                        var totalMoved = 0f
                                        var accX = 0f
                                        var accY = 0f
                                        var lastStep = 0L
                                        var holdFired = false
                                        val holdJob: Job = scope.launch {
                                            delay(500)
                                            if (totalMoved < 16f) {
                                                holdFired = true
                                                padBadge = "Back"
                                                triggerKey("BACK")
                                            }
                                        }
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                            if (!change.pressed) {
                                                change.consume()
                                                break
                                            }
                                            val delta = change.positionChange()
                                            if (delta != Offset.Zero) {
                                                change.consume()
                                                totalMoved += hypot(delta.x, delta.y)
                                                if (totalMoved >= 16f && holdJob.isActive) holdJob.cancel()
                                                if (padSize.width > 0 && padSize.height > 0) {
                                                    cursorX = (cursorX + delta.x * (1920f / padSize.width) * speed).coerceIn(0f, 1920f)
                                                    cursorY = (cursorY + delta.y * (1080f / padSize.height) * speed).coerceIn(0f, 1080f)
                                                }
                                                accX += delta.x
                                                accY += delta.y
                                                val threshold = (64f / speed).coerceAtLeast(26f)
                                                val now = System.currentTimeMillis()
                                                if ((abs(accX) >= threshold || abs(accY) >= threshold) && now - lastStep >= 95L) {
                                                    lastStep = now
                                                    if (abs(accY) >= abs(accX)) {
                                                        val dir = if (accY > 0f) "DOWN" else "UP"
                                                        padBadge = if (dir == "UP") "Up" else "Down"
                                                        accX = 0f
                                                        accY = 0f
                                                        triggerKey(dir)
                                                    } else {
                                                        val dir = if (accX > 0f) "RIGHT" else "LEFT"
                                                        padBadge = if (dir == "LEFT") "Left" else "Right"
                                                        accX = 0f
                                                        accY = 0f
                                                        triggerKey(dir)
                                                    }
                                                }
                                            }
                                        }
                                        if (holdJob.isActive) holdJob.cancel()
                                        if (!holdFired && totalMoved < 16f && (System.currentTimeMillis() - downTime) < 420L) {
                                            padBadge = "OK"
                                            triggerKey("SELECT")
                                        }
                                    }
                                }
                        ) {
                            // Subtle Dot Grid
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val step = 24.dp.toPx()
                                var x = step
                                while (x < size.width) {
                                    var y = step
                                    while (y < size.height) {
                                        drawCircle(color = Color(0x18FFFFFF), radius = 1.5f, center = Offset(x, y))
                                        y += step
                                    }
                                    x += step
                                }
                            }

                            // Live Pointer Indicator
                            val px = ((cursorX / 1920f) * padSize.width).roundToInt().coerceIn(24, (padSize.width - 24).coerceAtLeast(24))
                            val py = ((cursorY / 1080f) * padSize.height).roundToInt().coerceIn(24, (padSize.height - 24).coerceAtLeast(24))
                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(px - 18, py - 18) }
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(Color.White)
                                    .border(2.dp, accent, CircleShape)
                            )

                            // Center Hint
                            Column(
                                modifier = Modifier.align(Alignment.Center).padding(horizontal = 56.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text("Touchpad", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Swipe to steer · Tap for OK · Hold for Back",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 11.sp
                                )
                                if (padBadge.isNotBlank()) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Surface(color = wellBg, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.dp, accent)) {
                                        Text(
                                            padBadge,
                                            color = Color(0xFFFF9B70),
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 11.sp,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            // Right Scroll Up/Down Rail
                            Column(
                                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 10.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { padBadge = "Scroll Up"; triggerKey("UP") },
                                    colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(44.dp)
                                ) { Text("▲", color = Color.White, fontSize = 14.sp) }

                                Button(
                                    onClick = { padBadge = "Scroll Down"; triggerKey("DOWN") },
                                    colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                    shape = RoundedCornerShape(12.dp),
                                    contentPadding = PaddingValues(0.dp),
                                    modifier = Modifier.size(44.dp)
                                ) { Text("▼", color = Color.White, fontSize = 14.sp) }
                            }

                            // Bottom Coords & Speed Switcher
                            Row(
                                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(
                                    onClick = { cursorX = 960f; cursorY = 540f; padBadge = "Centered" },
                                    border = BorderStroke(1.dp, border),
                                    shape = RoundedCornerShape(10.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text("${cursorX.roundToInt()}, ${cursorY.roundToInt()}", color = Color(0xFFCBD5E1), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                                }

                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    listOf(1f to "1x", 1.5f to "1.5x", 2f to "2x").forEach { (s, label) ->
                                        Button(
                                            onClick = { speed = s },
                                            colors = ButtonDefaults.buttonColors(containerColor = if (speed == s) accent else wellBg),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                                            modifier = Modifier.height(32.dp)
                                        ) { Text(label, color = Color.White, fontSize = 11.sp) }
                                    }
                                }
                            }
                        }

                        // Quick Back / Home / Menu bar below Touchpad
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = { triggerKey("BACK") },
                                colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(44.dp)
                            ) { Text("Back", color = Color.White, fontSize = 12.sp) }

                            Button(
                                onClick = { triggerKey("HOME") },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF252D3D)),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(44.dp)
                            ) { Text("Home", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 12.sp) }

                            Button(
                                onClick = { triggerKey("MENU") },
                                colors = ButtonDefaults.buttonColors(containerColor = keyBg),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.weight(1f).height(44.dp)
                            ) { Text("Menu", color = Color.White, fontSize = 12.sp) }
                        }
                    }
                }

                "cast" -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
                                Text("Display Mirroring Automation", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Opens Quick Menu → Settings → Display & Sounds → Enable Display Mirroring without calling broken app intents.",
                                    color = Color(0xFF94A3B8),
                                    fontSize = 12.sp
                                )

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
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().height(48.dp)
                                ) { Text("Cast · Enable Display Mirroring", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }

                                OutlinedButton(
                                    onClick = {
                                        if (!requirePaired()) return@OutlinedButton
                                        scope.launch {
                                            status = "Opening Quick Menu Mirroring..."
                                            val r = client.quickMenuMirror(host, token, bridgeUrl)
                                            status = if (r.ok) "Quick Menu Mirroring sent" else (r.error ?: "Failed")
                                        }
                                    },
                                    border = BorderStroke(1.dp, border),
                                    shape = RoundedCornerShape(14.dp),
                                    modifier = Modifier.fillMaxWidth().height(46.dp)
                                ) { Text("Quick Menu Mirror Shortcut", color = Color.White, fontSize = 13.sp) }
                            }
                        }

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
                                Text("Menu Path Calibration", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf(
                                        Triple("OS 7/8 (→2, ↓3)", 2, 3),
                                        Triple("Alt (→3, ↓3)", 3, 3),
                                        Triple("OS 6 (→2, ↓4)", 2, 4)
                                    ).forEach { (label, dOff, mOff) ->
                                        OutlinedButton(
                                            onClick = {
                                                displayTileOffset = dOff
                                                mirrorRowOffset = mOff
                                                prefs.edit().putInt("display_offset", dOff).putInt("mirror_offset", mOff).apply()
                                            },
                                            border = BorderStroke(1.dp, if (displayTileOffset == dOff && mirrorRowOffset == mOff) accent else border),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(horizontal = 6.dp),
                                            modifier = Modifier.weight(1f).height(38.dp)
                                        ) { Text(label, color = Color.White, fontSize = 10.sp, maxLines = 1) }
                                    }
                                }

                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(color = bg, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, border), modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Display → $displayTileOffset", color = Color(0xFFCBD5E1), fontSize = 11.sp)
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
                                    Surface(color = bg, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, border), modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Mirror ↓ $mirrorRowOffset", color = Color(0xFFCBD5E1), fontSize = 11.sp)
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

                                // Quick Nudge Controls
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("◀" to "LEFT", "▲" to "UP", "OK" to "SELECT", "▼" to "DOWN", "▶" to "RIGHT").forEach { (lbl, key) ->
                                        Button(
                                            onClick = { triggerKey(key) },
                                            colors = ButtonDefaults.buttonColors(containerColor = if (key == "SELECT") accent else keyBg),
                                            shape = RoundedCornerShape(10.dp),
                                            contentPadding = PaddingValues(0.dp),
                                            modifier = Modifier.weight(1f).height(42.dp)
                                        ) { Text(lbl, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }

                else -> {
                    // Setup & Scan Tab
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
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
                                Text("Connect Fire TV Stick", color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)

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
                                                    status = "Scanned ${result.subnets.joinToString { "$it.x" }} — 0 found. Enter TV IP manually."
                                                }
                                            }
                                        },
                                        enabled = !scanning,
                                        colors = ButtonDefaults.buttonColors(containerColor = accent),
                                        shape = RoundedCornerShape(12.dp),
                                        contentPadding = PaddingValues(horizontal = 14.dp),
                                        modifier = Modifier.height(52.dp)
                                    ) {
                                        Text(if (scanning) "Scanning..." else "Scan", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
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
                                                        fontWeight = FontWeight.Bold
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
                                ) { Text("1. Send 4-Digit PIN to TV", color = Color.White, maxLines = 1) }

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
                                                        activeTab = "remote"
                                                        status = "Paired $host"
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
                                    ) { Text("2. Verify", color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1) }
                                }

                                OutlinedTextField(
                                    value = bridgeUrl,
                                    onValueChange = {
                                        bridgeUrl = it
                                        saveBridge(it)
                                    },
                                    label = { Text("Optional Bridge URL (http://127.0.0.1:8765)", maxLines = 1) },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                    }
                }
            }
        }

        // Fixed Bottom Navigation Bar matching image.png (Remote | Touchpad | Cast | Setup)
        Surface(
            color = bg,
            border = BorderStroke(1.dp, border),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                listOf(
                    Triple("remote", "Remote", "⌖"),
                    Triple("touchpad", "Touchpad", "⬚"),
                    Triple("cast", "Cast", "Cast"),
                    Triple("setup", "Setup", "⚙")
                ).forEach { (id, label, icon) ->
                    val selected = activeTab == id
                    TextButton(
                        onClick = { activeTab = id },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.weight(1f).height(52.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = icon,
                                color = if (selected) accent else Color(0xFF94A3B8),
                                fontSize = if (id == "cast") 11.sp else 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                            Text(
                                text = label,
                                color = if (selected) accent else Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
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
