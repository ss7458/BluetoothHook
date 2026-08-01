package com.jingyu233.bluetoothhook.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jingyu233.bluetoothhook.data.model.ScalePayloadCodec
import com.jingyu233.bluetoothhook.data.model.ScaleSimulatorConfig
import com.jingyu233.bluetoothhook.ui.components.HookStatusStrip
import com.jingyu233.bluetoothhook.ui.viewmodel.ScaleSimulatorViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScaleSimulatorScreen(
    onNavigateBack: () -> Unit,
    viewModel: ScaleSimulatorViewModel = viewModel()
) {
    val config by viewModel.config.collectAsState()
    val hookStatus by viewModel.hookStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("体重秤模拟") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Hook 状态提示
            item {
                HookStatusStrip(
                    status = hookStatus,
                    onRefresh = { viewModel.refreshHookStatus() }
                )
            }

            // 启用开关
            item {
                EnableScaleCard(
                    enabled = config.enabled,
                    onEnabledChange = { viewModel.setEnabled(it) }
                )
            }

            // 实时注入体重（本地模拟爬升曲线）
            item {
                LiveWeightCard(
                    enabled = config.enabled,
                    targetWeightKg = config.targetWeightKg,
                    rampDurationMs = config.rampDurationMs
                )
            }

            // 体重设置
            item {
                WeightCard(
                    weightKg = config.targetWeightKg,
                    onWeightChange = { viewModel.setTargetWeightKg(it) }
                )
            }

            // 阻抗设置
            item {
                ImpedanceCard(
                    impedanceOhm = config.impedanceOhm,
                    onImpedanceChange = { viewModel.setImpedanceOhm(it) }
                )
            }

            // RSSI 设置
            item {
                RssiCard(
                    baseRssi = config.baseRssi,
                    onBaseRssiChange = { viewModel.setBaseRssi(it) }
                )
            }

            // 广播参数
            item {
                BroadcastParamsCard(
                    intervalMs = config.intervalMs,
                    rampDurationMs = config.rampDurationMs,
                    onIntervalChange = { viewModel.setIntervalMs(it) },
                    onRampDurationChange = { viewModel.setRampDurationMs(it) }
                )
            }

            // MAC 地址
            item {
                MacCard(
                    mac = config.mac,
                    onMacChange = { viewModel.setMac(it) }
                )
            }

            // 手动模式
            item {
                ManualModeCard(
                    manualMode = config.manualMode,
                    manualAdvHex = config.manualAdvHex,
                    onManualModeChange = { viewModel.setManualMode(it) },
                    onManualAdvHexChange = { viewModel.setManualAdvHex(it) }
                )
            }

            // 实时预览
            item {
                PreviewCard(
                    config = config,
                    manualMode = config.manualMode
                )
            }
        }
    }
}

// ── 启用开关 ────────────────────────────────────────────────

@Composable
private fun EnableScaleCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "启用体重秤模拟",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "开启后模拟真实体重秤广播，京东健康等 App 可识别",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange
            )
        }
    }
}

// ── 实时注入体重 ────────────────────────────────────────────

/**
 * 实时注入体重（界面本地模拟，不依赖 hook 回传）。
 * 开启后体重按 ease-out 曲线 1-(1-t)^2 在 rampDurationMs 内从 0 爬到目标值，
 * 之后保持"已稳定"。暂停或配置变更（目标体重/爬升时长）时从头重置。
 */
@Composable
private fun LiveWeightCard(
    enabled: Boolean,
    targetWeightKg: Double,
    rampDurationMs: Long
) {
    val progress = remember { Animatable(0f) }

    LaunchedEffect(enabled, targetWeightKg, rampDurationMs) {
        progress.snapTo(0f)
        if (enabled) {
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = rampDurationMs.toInt(),
                    easing = { t -> 1f - (1f - t) * (1f - t) }
                )
            )
        }
    }

    val currentWeight = targetWeightKg * progress.value
    val isRamping = progress.value < 1f
    val statusText = when {
        !enabled -> "未启用"
        isRamping -> "爬升中…"
        else -> "已稳定"
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "实时注入体重",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format("%.2f kg", currentWeight),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = if (enabled)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.weight(1f))
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.surfaceVariant
                        isRamping -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.primaryContainer
                    }
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium,
                        color = when {
                            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant
                            isRamping -> MaterialTheme.colorScheme.onSecondaryContainer
                            else -> MaterialTheme.colorScheme.onPrimaryContainer
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "界面模拟显示，实际以第三方 BLE 扫描 App 收到的广播为准",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── 体重设置 ────────────────────────────────────────────────

@Composable
private fun WeightCard(
    weightKg: Double,
    onWeightChange: (Double) -> Unit
) {
    val sliderValue = remember(weightKg) { mutableFloatStateOf(weightKg.toFloat()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "目标体重",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "爬升曲线先快后慢（约1.8s），稳定后保持",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("0.5 kg", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = String.format("%.1f kg", sliderValue.value),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text("200 kg", style = MaterialTheme.typography.bodySmall)
            }

            Slider(
                value = sliderValue.value,
                onValueChange = { sliderValue.value = it },
                onValueChangeFinished = {
                    onWeightChange(roundToDecimal(sliderValue.value.toDouble()))
                },
                valueRange = 0.5f..200.0f,
                steps = 400
            )
        }
    }
}

// ── 阻抗设置 ────────────────────────────────────────────────

@Composable
private fun ImpedanceCard(
    impedanceOhm: Int,
    onImpedanceChange: (Int) -> Unit
) {
    val sliderValue = remember(impedanceOhm) { mutableFloatStateOf(impedanceOhm.coerceIn(0, 600).toFloat()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "阻抗",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "爬升期间自动广播为 0（未测），稳定后跳变到设定值；真实秤默认 600Ω",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("0 Ω", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "${sliderValue.value.toInt()} Ω",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text("600 Ω", style = MaterialTheme.typography.bodySmall)
            }

            Slider(
                value = sliderValue.value,
                onValueChange = { sliderValue.value = it },
                onValueChangeFinished = {
                    onImpedanceChange(sliderValue.value.toInt())
                },
                valueRange = 0f..600f,
                steps = 59
            )
        }
    }
}

// ── RSSI 设置 ───────────────────────────────────────────────

@Composable
private fun RssiCard(
    baseRssi: Int,
    onBaseRssiChange: (Int) -> Unit
) {
    val sliderValue = remember(baseRssi) { mutableFloatStateOf(baseRssi.toFloat()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "信号强度 (RSSI)",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "基础 ${sliderValue.value.toInt()} dBm，±5 随机抖动",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("-100 dBm", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "${sliderValue.value.toInt()} dBm",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Text("-30 dBm", style = MaterialTheme.typography.bodySmall)
            }

            Slider(
                value = sliderValue.value,
                onValueChange = { sliderValue.value = it },
                onValueChangeFinished = {
                    onBaseRssiChange(sliderValue.value.toInt())
                },
                valueRange = -100f..-30f,
                steps = 69
            )
        }
    }
}

// ── 广播参数 ────────────────────────────────────────────────

@Composable
private fun BroadcastParamsCard(
    intervalMs: Long,
    rampDurationMs: Long,
    onIntervalChange: (Long) -> Unit,
    onRampDurationChange: (Long) -> Unit
) {
    val intervalValue = remember(intervalMs) { mutableFloatStateOf(intervalMs.toFloat()) }
    val rampValue = remember(rampDurationMs) { mutableFloatStateOf(rampDurationMs.toFloat()) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "广播参数",
                style = MaterialTheme.typography.titleMedium
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 广播间隔
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("广播间隔", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${intervalValue.value.toInt()} ms",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = intervalValue.value,
                onValueChange = { intervalValue.value = it },
                onValueChangeFinished = {
                    onIntervalChange(intervalValue.value.toInt().toLong())
                },
                valueRange = 50f..1000f,
                steps = 94
            )
            Text(
                text = "真实秤约 100ms",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 爬升时长
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("爬升时长", style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = "${rampValue.value.toInt()} ms",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }
            Slider(
                value = rampValue.value,
                onValueChange = { rampValue.value = it },
                onValueChangeFinished = {
                    onRampDurationChange(rampValue.value.toInt().toLong())
                },
                valueRange = 500f..10000f,
                steps = 94
            )
            Text(
                text = "真实秤约 1.7-2s",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── MAC 地址 ────────────────────────────────────────────────

@Composable
private fun MacCard(
    mac: String,
    onMacChange: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "广播源 MAC",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = mac,
                onValueChange = onMacChange,
                label = { Text("MAC 地址") },
                placeholder = { Text("98:F6:7A:A3:9E:F4") },
                supportingText = { Text("广播源MAC，载荷内嵌MAC必须与之一致") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
            )
        }
    }
}

// ── 手动模式 ────────────────────────────────────────────────

@Composable
private fun ManualModeCard(
    manualMode: Boolean,
    manualAdvHex: String,
    onManualModeChange: (Boolean) -> Unit,
    onManualAdvHexChange: (String) -> Unit
) {
    val parsed = remember(manualAdvHex) { ScalePayloadCodec.parse(manualAdvHex) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "手动指定广播数据",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "开启后不再自动生成，直接广播下方输入的数据",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = manualMode,
                    onCheckedChange = onManualModeChange
                )
            }

            if (manualMode) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = manualAdvHex,
                    onValueChange = onManualAdvHexChange,
                    label = { Text("完整 AD hex") },
                    placeholder = { Text("0DFF… 30 位十六进制") },
                    supportingText = { Text("0DFF + 13 字节载荷，共 30 位十六进制") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii)
                )

                Spacer(modifier = Modifier.height(12.dp))

                // 实时解析结果
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (manualAdvHex.isNotBlank() && !parsed.valid)
                            MaterialTheme.colorScheme.errorContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "实时解析",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        when {
                            manualAdvHex.isBlank() -> {
                                Text(
                                    text = "输入完整广播数据后自动解析",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            parsed.valid -> {
                                Text(
                                    text = "体重: ${String.format("%.1f kg", parsed.weightKg)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "阻抗: ${formatImpedance(parsed.impedanceOhm)} Ω",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "状态: 0x${parsed.statusByte.toString(16).padStart(2, '0').uppercase()}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "MAC: ${parsed.mac}",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            else -> {
                                Text(
                                    text = "无法解析：请输入完整的 30 位 AD hex（0DFF + 13 字节载荷）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 实时预览 ────────────────────────────────────────────────

@Composable
private fun PreviewCard(
    config: ScaleSimulatorConfig,
    manualMode: Boolean
) {
    val previewHex = remember(config) {
        config.buildStableAdvHex(config.targetWeightKg, false)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "广播数据预览",
                style = MaterialTheme.typography.titleSmall
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "自动模式稳定帧（${String.format("%.1f kg", config.targetWeightKg)}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surface
            ) {
                Text(
                    text = previewHex,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "格式：0DFF + 体重2B + 阻抗2B + 0A11 + 状态1B + MAC6B",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )

            if (manualMode) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "手动模式已开启，实际广播以手动数据为准",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

// ── 工具函数 ────────────────────────────────────────────────

/** 保留 1 位小数 */
private fun roundToDecimal(value: Double): Double =
    (value * 10).roundToInt() / 10.0

/** 阻抗显示（整数） */
private fun formatImpedance(ohm: Int): String = "$ohm"
