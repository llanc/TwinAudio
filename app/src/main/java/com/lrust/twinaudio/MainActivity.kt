package com.lrust.twinaudio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lrust.twinaudio.ui.theme.TwinAudioTheme
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TwinAudioTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TwinAudioControlPanel()
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TwinAudioControlPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current

    // 绑定到底层的变量 (默认 USB 无延迟，音量最大)
    var delayMs by remember { mutableStateOf(0f) }
    var volumeUsb by remember { mutableStateOf(1.0f) }
    var hookEnabled by remember { mutableStateOf(true) }

    // 🚀 发送广播到底层 (AudioServiceHook 会接收这些参数)
    val sendConfigUpdate = {
        val intent = Intent("com.lrust.twinaudio.UPDATE_CONFIG")
        intent.putExtra("delayMs", delayMs.roundToInt())
        intent.putExtra("volumeUsb", volumeUsb)
        intent.putExtra("hookEnabled", hookEnabled)
        context.sendBroadcast(intent)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("TwinAudio 双音频输出") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // ================================================================
            // 模块状态卡片
            // ================================================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (hookEnabled) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "引擎状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (hookEnabled) "✓ 逆向分发引擎已激活" else "✗ 已挂起",
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Switch(
                            checked = hookEnabled,
                            onCheckedChange = { enabled ->
                                hookEnabled = enabled
                                sendConfigUpdate()
                            }
                        )
                    }
                }
            }

            // ================================================================
            // USB 延迟控制 (核心同步功能)
            // ================================================================
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "有线端 (USB/AUX) 空轨延迟",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "${delayMs.roundToInt()} ms",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Slider(
                        value = delayMs,
                        onValueChange = { delayMs = it },
                        onValueChangeFinished = { sendConfigUpdate() },
                        valueRange = 0f..1000f,
                        steps = 999,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0 ms", style = MaterialTheme.typography.bodySmall)
                        Text("1000 ms", style = MaterialTheme.typography.bodySmall)
                    }

                    Text(
                        text = "💡 提示：蓝牙耳机天生有 150ms 左右的延迟。向右拖动此滑块，可强制让有线音响等待蓝牙，从而实现双通道完美同步。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ================================================================
            // USB 独立音量控制
            // ================================================================
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "有线端 (USB/AUX) 独立音量",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "${(volumeUsb * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Slider(
                        value = volumeUsb,
                        onValueChange = { volumeUsb = it },
                        onValueChangeFinished = { sendConfigUpdate() },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("静音", style = MaterialTheme.typography.bodySmall)
                        Text("最大音量", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ================================================================
            // 操作说明
            // ================================================================
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "⚠️ 操作指南",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = """
                        • 蓝牙音量：请直接使用手机侧边的【实体音量键】控制。
                        • 有线音量：使用上方【独立音量】滑块控制。
                        • 延迟变动时，底层引擎会极速重启以填充空数据，声音会有半秒停顿，属正常现象。
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}