package com.lrust.twinaudio

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
    var delayMs by remember { mutableStateOf(0f) }
    var volumeBt by remember { mutableStateOf(1.0f) }
    var volumeUsb by remember { mutableStateOf(1.0f) }
    var hookEnabled by remember { mutableStateOf(true) }

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
            // 状态卡片
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
                        text = "模块状态",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (hookEnabled) "✓ 已激活" else "✗ 未激活",
                            style = MaterialTheme.typography.bodyLarge
                        )

                        Switch(
                            checked = hookEnabled,
                            onCheckedChange = { enabled ->
                                hookEnabled = enabled
                                if (enabled) {
                                    IPCManager.enableHook()
                                } else {
                                    IPCManager.disableHook()
                                }
                            }
                        )
                    }
                }
            }

            // ================================================================
            // 延迟调节
            // ================================================================
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "USB 音频延迟",
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
                        onValueChangeFinished = {
                            // 滑块停止时发送更新
                            IPCManager.updateConfig(
                                delayMs.roundToInt(),
                                volumeBt,
                                volumeUsb
                            )
                        },
                        valueRange = 0f..1000f,
                        steps = 99,  // 10ms 步进
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
                        text = "调整此值以同步蓝牙和有线音频。如果蓝牙延迟，增加此值；如果有线延迟，减少此值。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ================================================================
            // 蓝牙音量
            // ================================================================
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "蓝牙音量系数",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "${(volumeBt * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )

                    Slider(
                        value = volumeBt,
                        onValueChange = { volumeBt = it },
                        onValueChangeFinished = {
                            IPCManager.updateConfig(
                                delayMs.roundToInt(),
                                volumeBt,
                                volumeUsb
                            )
                        },
                        valueRange = 0f..2f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0%", style = MaterialTheme.typography.bodySmall)
                        Text("100%", style = MaterialTheme.typography.bodySmall)
                        Text("200%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ================================================================
            // USB 音量
            // ================================================================
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "USB/AUX 音量系数",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = "${(volumeUsb * 100).roundToInt()}%",
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.tertiary
                    )

                    Slider(
                        value = volumeUsb,
                        onValueChange = { volumeUsb = it },
                        onValueChangeFinished = {
                            IPCManager.updateConfig(
                                delayMs.roundToInt(),
                                volumeBt,
                                volumeUsb
                            )
                        },
                        valueRange = 0f..2f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0%", style = MaterialTheme.typography.bodySmall)
                        Text("100%", style = MaterialTheme.typography.bodySmall)
                        Text("200%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ================================================================
            // 应用按钮
            // ================================================================
            Button(
                onClick = {
                    IPCManager.updateConfig(
                        delayMs.roundToInt(),
                        volumeBt,
                        volumeUsb
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "立即应用设置",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            // ================================================================
            // 说明文字
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
                        text = "⚠️ 使用说明",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = """
                        1. 确保已在 LSPosed 中激活本模块
                        2. 勾选 system_server 作用域
                        3. 确保 Magisk Zygisk 已启用
                        4. 安装 TwinAudio Magisk 模块
                        5. 重启设备后生效
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

