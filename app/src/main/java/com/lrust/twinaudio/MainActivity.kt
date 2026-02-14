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
    // 获取当前上下文（用来发广播）
    val context = androidx.compose.ui.platform.LocalContext.current

    var delayMs by remember { mutableStateOf(0f) }
    var volumeBt by remember { mutableStateOf(1.0f) }
    var hookEnabled by remember { mutableStateOf(true) }

    // 🚀 核心：统一的广播发送器，替换原来的 IPCManager
    val sendConfigUpdate = {
        val intent = Intent("com.lrust.twinaudio.UPDATE_CONFIG")
        intent.putExtra("delayMs", delayMs.roundToInt())
        intent.putExtra("volumeBt", volumeBt)
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
                            text = if (hookEnabled) "✓ 双音频中转站已激活" else "✗ 已暂停分发",
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
                        text = "蓝牙端额外延迟",
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
                            sendConfigUpdate() // 滑块停止时发送更新
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
                        text = "物理限制提示：由于蓝牙无线传输本身就比有线慢，增加此值会让蓝牙端的声音进一步延后。纯 Java 方案无法延迟底层的有线端。",
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
                        text = "蓝牙端独立音量",
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
                            sendConfigUpdate() // 滑块停止时发送更新
                        },
                        valueRange = 0f..1f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("静音", style = MaterialTheme.typography.bodySmall)
                        Text("100%", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // ================================================================
            // 应用按钮
            // ================================================================
            Button(
                onClick = {
                    sendConfigUpdate()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(
                    text = "下发指令到底层",
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
                        text = "⚠️ 终极版使用说明",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        text = """
                        1. 本方案已实现「纯 Java 层降维打击」。
                        2. 彻底抛弃 Magisk！请确保旧版 Magisk 模块已卸载。
                        3. 仅需在 LSPosed 中勾选【系统框架 (system_server)】。
                        4. 插入 USB 音响与蓝牙耳机即可自动触发双流分发。
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}