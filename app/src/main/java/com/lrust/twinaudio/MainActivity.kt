package com.lrust.twinaudio

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState // 滚动状态记忆
import androidx.compose.foundation.verticalScroll   //垂直滚动修饰符
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

    // 获取本地存储实例
    val sharedPrefs = remember {
        context.getSharedPreferences("TwinAudioPrefs", Context.MODE_PRIVATE)
    }

    // 优先从本地存储读取
    var delayMs by remember { mutableStateOf(sharedPrefs.getFloat("delayMs", 0f)) }
    var volumeUsb by remember { mutableStateOf(sharedPrefs.getFloat("volumeUsb", 1.0f)) }
    var hookEnabled by remember { mutableStateOf(sharedPrefs.getBoolean("hookEnabled", true)) }

    // 发送广播到底层并保存本地
    val sendConfigUpdate = {
        sharedPrefs.edit().apply {
            putFloat("delayMs", delayMs)
            putFloat("volumeUsb", volumeUsb)
            putBoolean("hookEnabled", hookEnabled)
            apply()
        }

        val intent = Intent("com.lrust.twinaudio.UPDATE_CONFIG")
        intent.putExtra("delayMs", delayMs.roundToInt())
        intent.putExtra("volumeUsb", volumeUsb)
        intent.putExtra("hookEnabled", hookEnabled)
        context.sendBroadcast(intent)
    }

    // 自动化同步
    LaunchedEffect(Unit) {
        sendConfigUpdate()
    }

    // 创建一个滚动状态
    val scrollState = rememberScrollState()

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
                .verticalScroll(scrollState) // 允许整个内容区域垂直滚动！
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
            // USB 延迟控制 (滑块粗调 + 按钮精调)
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

                    // 1. 滑块：用于大范围快速粗调
                    Slider(
                        value = delayMs,
                        onValueChange = { delayMs = it },
                        onValueChangeFinished = { sendConfigUpdate() },
                        valueRange = 0f..1000f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 滑块刻度说明
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = (-8).dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("0 ms", style = MaterialTheme.typography.bodySmall)
                        Text("1000 ms", style = MaterialTheme.typography.bodySmall)
                    }

                    // 2. 盲操大按钮：用于 1ms 极限精度微调
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        FilledTonalButton(
                            onClick = {
                                delayMs = (delayMs - 1f).coerceAtLeast(0f)
                                sendConfigUpdate()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                        ) {
                            Text("- 1ms", style = MaterialTheme.typography.titleMedium)
                        }

                        FilledTonalButton(
                            onClick = {
                                delayMs = (delayMs + 1f).coerceAtMost(1000f)
                                sendConfigUpdate()
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(64.dp)
                        ) {
                            Text("+ 1ms", style = MaterialTheme.typography.titleMedium)
                        }
                    }

                    Text(
                        text = "💡 提示：先用滑块粗调，再闭上眼睛用下方按钮进行 1ms 精度的极致微调对齐。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ================================================================
            // USB 独立音量控制 (支持 300% 暴增)
            // ================================================================
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "有线端数字放大器",
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
                        valueRange = 0f..3f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset(y = (-8).dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("静音", style = MaterialTheme.typography.bodySmall)
                        Text("100%", style = MaterialTheme.typography.bodySmall)
                        Text("暴增 300%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    Text(
                        text = "💡 提示：向右拉突破 100%，引擎将通过 CPU 对 PCM 信号进行底层软件级暴增放大！",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // ================================================================
            // 操作说明卡片
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
                        • 有线音量：使用上方【数字放大器】控制，最高可达 300%。
                        • 记忆功能：引擎已直连系统底层数据库，重启手机自动恢复参数，切歌自动重置防断流。
                        """.trimIndent(),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}