package com.jingyu233.bluetoothhook.ui.screen

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.core.content.ContextCompat
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jingyu233.bluetoothhook.data.model.CaptureRecord
import com.jingyu233.bluetoothhook.ui.components.HookStatusStrip
import com.jingyu233.bluetoothhook.ui.viewmodel.CaptureViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onNavigateBack: () -> Unit,
    onNavigateToDetail: (Long) -> Unit,
    viewModel: CaptureViewModel = viewModel()
) {
    val records by viewModel.filteredRecords.collectAsState()
    val allRecords by viewModel.captureRecords.collectAsState()
    val serverError by viewModel.serverError.collectAsState()
    val hookStatus by viewModel.hookStatus.collectAsState()
    val isListening by viewModel.isListening.collectAsState()
    val captureEnabled by viewModel.captureEnabled.collectAsState()
    val bleScanning by viewModel.bleScanning.collectAsState()
    val rssiMin by viewModel.rssiMinFilter.collectAsState()
    val rssiMax by viewModel.rssiMaxFilter.collectAsState()
    val macPattern by viewModel.macFilterPattern.collectAsState()
    val eventTypeBits by viewModel.eventTypeFilter.collectAsState()
    val hasActiveFilter by viewModel.hasActiveFilter.collectAsState()
    var showFilter by remember { mutableStateOf(false) }
    var showChart by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // BLE 扫描权限
    val blePermissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    val blePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            viewModel.startBleScan()
        }
    }

    // SAF 导出启动器
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            viewModel.exportTo(uri)
        }
    }

    // LazyColumn 自动滚动到最新
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("扫描抓包")
                        if (isListening) {
                            Spacer(modifier = Modifier.width(8.dp))
                            PulsingDot()
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, "返回")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 错误提示卡片
            serverError?.let { error ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "抓包服务启动失败：$error",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            // Hook 状态条（紧凑型，红/绿区分）
            HookStatusStrip(
                status = hookStatus,
                onRefresh = { viewModel.refreshHookStatus() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            // 合并的控制行（App扫描 + 抓包开关 + 操作按钮）
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // App扫描开关（图标+Switch精简）
                    Icon(
                        imageVector = Icons.Default.BluetoothSearching,
                        contentDescription = null,
                        tint = if (bleScanning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "扫描",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = bleScanning,
                        onCheckedChange = {
                            if (bleScanning) {
                                viewModel.stopBleScan()
                            } else {
                                val hasPermission = blePermissions.all { perm ->
                                    ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED
                                }
                                if (hasPermission) {
                                    viewModel.startBleScan()
                                } else {
                                    blePermissionLauncher.launch(blePermissions)
                                }
                            }
                        },
                        modifier = Modifier.height(28.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    // 抓包开关
                    Text(
                        text = "抓包",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                    Switch(
                        checked = captureEnabled,
                        onCheckedChange = { viewModel.setCaptureEnabled(it) },
                        modifier = Modifier.height(28.dp)
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // 信号图切换按钮
                    IconButton(
                        onClick = { showChart = !showChart },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (showChart) Icons.Default.BarChart else Icons.Default.ShowChart,
                            contentDescription = "信号图",
                            tint = if (showChart) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 过滤切换按钮
                    IconButton(
                        onClick = { showFilter = !showFilter },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (showFilter) Icons.Default.FilterListOff else Icons.Default.FilterList,
                            contentDescription = "过滤",
                            tint = if (hasActiveFilter) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // 已捕获条数徽标
                    if (allRecords.isNotEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.padding(end = 4.dp)
                        ) {
                            Text(
                                text = "${allRecords.size}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }

                    // 导出按钮（仅图标）
                    IconButton(
                        onClick = {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                            exportLauncher.launch("bluetooth_capture_$timestamp.csv")
                        },
                        enabled = allRecords.isNotEmpty(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "导出CSV",
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // 清空按钮（仅图标）
                    IconButton(
                        onClick = { viewModel.clear() },
                        enabled = allRecords.isNotEmpty(),
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "清空",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 过滤条（可折叠）
            if (showFilter) {
                FilterBar(
                    rssiMin = rssiMin,
                    rssiMax = rssiMax,
                    macPattern = macPattern,
                    selectedEventTypeBits = eventTypeBits,
                    onRssiMinChange = { viewModel.setRssiMinFilter(it) },
                    onRssiMaxChange = { viewModel.setRssiMaxFilter(it) },
                    onMacPatternChange = { viewModel.setMacFilterPattern(it) },
                    onEventTypeFilterChange = { viewModel.setEventTypeFilter(it) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 信号趋势图（可折叠）
            if (showChart && records.isNotEmpty()) {
                SignalChart(
                    records = records,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            // 记录列表 / 空状态
            if (records.isEmpty()) {
                CaptureEmptyState(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp)
                )
            } else {
                Box(modifier = Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        itemsIndexed(
                            items = records,
                            key = { _, record -> record.id }
                        ) { _, record ->
                            CaptureRecordCard(
                                record = record,
                                onDataClick = { onNavigateToDetail(record.id) }
                            )
                        }
                    }

                    // 自动滚动到最新（仅当用户在底部附近时）
                    LaunchedEffect(records.size) {
                        if (records.isNotEmpty()) {
                            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            if (lastVisibleItem >= records.size - 2) {
                                listState.animateScrollToItem(records.size - 1)
                            }
                        }
                    }
                }
            }
        }
    }
}

// ---- 子组件 ----

@Composable
private fun PulsingDot() {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
    )
}

// ── 信号趋势图组件 ────────────────────────────────────────

/**
 * RSSI 随时间变化的折线图。
 * 按 MAC 分组绘制不同颜色的曲线，X 轴为时间，Y 轴为 RSSI (-100..0)。
 */
@Composable
private fun SignalChart(
    records: List<CaptureRecord>,
    modifier: Modifier = Modifier
) {
    // 按 MAC 分组并按时间排序
    val grouped = remember(records) {
        records
            .groupBy { it.mac }
            .mapValues { (_, list) -> list.sortedBy { it.timestamp } }
            .toList()
            .sortedByDescending { (_, list) -> list.lastOrNull()?.timestamp ?: 0L }
    }

    // 稳定色板（设备多时循环使用）
    val palette = listOf(
        Color(0xFF4FC3F7), // 浅蓝
        Color(0xFFFF7043), // 橙
        Color(0xFF66BB6A), // 绿
        Color(0xFFAB47BC), // 紫
        Color(0xFFFFA726), // 琥珀
        Color(0xFFEC407A), // 粉
        Color(0xFF26A69A), // 青
        Color(0xFF5C6BC0)  // 靛
    )

    // 时间范围
    val minTime = records.minOfOrNull { it.timestamp } ?: 0L
    val maxTime = records.maxOfOrNull { it.timestamp } ?: 0L
    val timeSpan = (maxTime - minTime).coerceAtLeast(1L)
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "信号趋势",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = "${grouped.size} 个设备",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 图表区
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp)
            ) {
                val chartLeft = 0f
                val chartRight = size.width
                val chartTop = 0f
                val chartBottom = size.height

                // 横向网格线 + RSSI 刻度标注
                val rssiTicks = listOf(-100, -80, -60, -40, -20, 0)
                rssiTicks.forEach { rssiVal ->
                    val y = chartBottom - (rssiVal + 100) / 100f * chartBottom
                    drawLine(
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
                        start = Offset(chartLeft, y),
                        end = Offset(chartRight, y),
                        strokeWidth = 1f
                    )
                }

                // 每条设备曲线
                grouped.forEachIndexed { index, (_, deviceRecords) ->
                    val color = palette[index % palette.size]
                    if (deviceRecords.size < 2) {
                        // 单点画圆点
                        val rssi = deviceRecords.first().rssi
                        val x = chartLeft + (deviceRecords.first().timestamp - minTime).toFloat() / timeSpan * (chartRight - chartLeft)
                        val y = chartBottom - (rssi + 100) / 100f * chartBottom
                        drawCircle(
                            color = color,
                            radius = 3.dp.toPx(),
                            center = Offset(x, y)
                        )
                    } else {
                        val path = Path()
                        deviceRecords.forEachIndexed { i, rec ->
                            val x = chartLeft + (rec.timestamp - minTime).toFloat() / timeSpan * (chartRight - chartLeft)
                            val y = chartBottom - (rec.rssi + 100) / 100f * chartBottom
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(
                            path = path,
                            color = color,
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // X 轴时间标注
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = timeFormat.format(Date(minTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = timeFormat.format(Date(maxTime)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 图例（每设备一行：色点 + MAC + 最新RSSI）
            OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                grouped.forEachIndexed { index, (mac, deviceRecords) ->
                    val color = palette[index % palette.size]
                    val latest = deviceRecords.lastOrNull()?.rssi
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (latest != null) "$mac  $latest dBm" else mac,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

// ── 过滤条组件 ────────────────────────────────────────────

@Composable
private fun FilterBar(
    rssiMin: Int?,
    rssiMax: Int?,
    macPattern: String,
    selectedEventTypeBits: Int,
    onRssiMinChange: (Int?) -> Unit,
    onRssiMaxChange: (Int?) -> Unit,
    onMacPatternChange: (String) -> Unit,
    onEventTypeFilterChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentMin = rssiMin ?: -100
    val currentMax = rssiMax ?: 0
    val rssiRange = remember(rssiMin, rssiMax) {
        mutableStateOf(currentMin.toFloat()..currentMax.toFloat())
    }

    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("过滤条件", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (rssiMin != null || rssiMax != null) {
                    TextButton(
                        onClick = {
                            onRssiMinChange(null)
                            onRssiMaxChange(null)
                        },
                        contentPadding = PaddingValues(0.dp),
                        modifier = Modifier.height(24.dp)
                    ) {
                        Text("重置RSSI", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // RSSI 范围滑块
            Text("RSSI 范围", style = MaterialTheme.typography.labelSmall)
            RangeSlider(
                value = rssiRange.value,
                onValueChange = { newRange ->
                    rssiRange.value = newRange
                    val startInt = newRange.start.toInt()
                    val endInt = newRange.endInclusive.toInt()
                    if (startInt == -100 && endInt == 0) {
                        onRssiMinChange(null)
                        onRssiMaxChange(null)
                    } else {
                        onRssiMinChange(startInt)
                        onRssiMaxChange(endInt)
                    }
                },
                valueRange = -100f..0f,
                steps = 199,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = "${rssiRange.value.start.toInt()} ~ ${rssiRange.value.endInclusive.toInt()} dBm",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 蓝牙类型过滤
            Text("蓝牙类型", style = MaterialTheme.typography.labelSmall)
            Spacer(modifier = Modifier.height(4.dp))
            val eventTypeOptions = listOf(
                "可连接" to 0x01,
                "可扫描" to 0x02,
                "传统" to 0x10,
                "定向" to 0x04,
                "扫描响应" to 0x08
            )
            OptIn(ExperimentalLayoutApi::class)
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                eventTypeOptions.forEach { (label, bit) ->
                    val isSelected = (selectedEventTypeBits and bit) != 0
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            if (isSelected) {
                                onEventTypeFilterChange(selectedEventTypeBits and bit.inv())
                            } else {
                                onEventTypeFilterChange(selectedEventTypeBits or bit)
                            }
                        },
                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("MAC:", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.width(4.dp))
                OutlinedTextField(
                    value = macPattern,
                    onValueChange = onMacPatternChange,
                    placeholder = { Text("?匹配单字符 *匹配多字符", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.weight(1f).height(48.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text("例: AA:??:??:??:??:* 或 *:FF:*", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * 单条抓包记录卡片
 */
@Composable
private fun CaptureRecordCard(
    record: CaptureRecord,
    onDataClick: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
    val rssiColor = when {
        record.rssi > -60 -> Color(0xFF4CAF50)   // 强
        record.rssi > -80 -> Color(0xFFFF9800)   // 中
        else -> Color(0xFFF44336)                 // 弱
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 第一行：时间、MAC、RSSI
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 时间戳
                Text(
                    text = timeFormat.format(Date(record.timestamp)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(12.dp))

                // MAC 地址
                Text(
                    text = record.mac,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )

                // RSSI
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = rssiColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${record.rssi} dBm",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = rssiColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // 第二行：广播数据（可点击跳转到详情页）
            if (record.advDataHex.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier.clickable { onDataClick() }
                    ) {
                        Text(
                            text = record.advDataHex,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            lineHeight = 16.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // 第三行：附加参数（含中文标注，Event 长标签独占一行避免挤压 PHY/Addr）
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Event: ${record.eventTypeLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Phy: ${record.phyLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Addr: ${record.addressTypeLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 空状态
 */
@Composable
private fun CaptureEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.BluetoothSearching,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "暂无抓包数据",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "开启抓包开关并让设备蓝牙扫描。Hook 状态请查看首页。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
    }
}
