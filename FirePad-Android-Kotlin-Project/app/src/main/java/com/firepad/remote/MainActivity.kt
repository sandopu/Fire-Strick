package com.firepad.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val client = FireTvClient(this)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                FirePadScreen(client)
            }
        }
    }
}

@Composable
fun FirePadScreen(client: FireTvClient) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Scan Wi-Fi or enter Fire Stick IP") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D12))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("FirePad Remote", style = MaterialTheme.typography.headlineSmall, color = Color.White, fontWeight = FontWeight.Bold)
        Text(status, color = Color(0xFFFF6B2C), style = MaterialTheme.typography.bodySmall)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Firestick IP") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = {
                scope.launch {
                    status = "Scanning Wi-Fi..."
                    val list = client.discoverFireSticks()
                    if (list.isNotEmpty()) {
                        host = list.first()
                        status = "Found Firestick: $host"
                    } else status = "No Firestick found via SSDP"
                }
            }) { Text("Scan") }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    val r = client.requestPin(host)
                    status = if (r.ok) "Enter the 4-digit PIN shown on TV" else (r.error ?: "PIN failed")
                }
            }) { Text("Send PIN") }
            OutlinedTextField(value = pin, onValueChange = { pin = it }, label = { Text("PIN") }, modifier = Modifier.weight(1f), singleLine = true)
            Button(onClick = {
                scope.launch {
                    val (tok, err) = client.verifyPin(host, pin)
                    if (tok != null) { token = tok; status = "Paired with $host" } else status = err ?: "Verify failed"
                }
            }) { Text("Verify") }
        }

        // D-Pad + Volume + Settings + Cast -> Display Mirroring
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { status = client.openSettings(host, token).let { if (it.ok) "Opened Settings" else it.error ?: "Error" } } }) { Text("Settings") }
                Button(onClick = {
                    scope.launch {
                        status = "Navigating Settings -> Display -> Enable Display Mirroring..."
                        val r = client.openDisplayMirroring(host, token)
                        status = if (r.ok) "Display Mirroring opened" else (r.error ?: "Failed")
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B2C))) { Text("Cast / Mirror") }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { scope.launch { client.sendKey(host, token, "dpad_up") } }) { Text("▲") }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { scope.launch { client.sendKey(host, token, "dpad_left") } }) { Text("◀") }
                    Button(onClick = { scope.launch { client.sendKey(host, token, "select") } }, shape = CircleShape) { Text("OK") }
                    Button(onClick = { scope.launch { client.sendKey(host, token, "dpad_right") } }) { Text("▶") }
                }
                Button(onClick = { scope.launch { client.sendKey(host, token, "dpad_down") } }) { Text("▼") }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { status = client.sendVolume(host, token, "VOL_UP").error ?: "Volume +" } }) { Text("Vol +") }
                Button(onClick = { scope.launch { status = client.sendVolume(host, token, "MUTE").error ?: "Muted" } }) { Text("Mute") }
                Button(onClick = { scope.launch { status = client.sendVolume(host, token, "VOL_DOWN").error ?: "Volume -" } }) { Text("Vol -") }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { scope.launch { client.sendKey(host, token, "back") } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Back") }
            Button(onClick = { scope.launch { client.sendKey(host, token, "home") } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Home") }
            Button(onClick = { scope.launch { client.sendKey(host, token, "menu") } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(12.dp)) { Text("Menu") }
        }
    }
}
