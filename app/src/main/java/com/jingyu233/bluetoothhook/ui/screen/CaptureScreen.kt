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
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
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
    var showFilter by remember { mutableStateOf(false) }
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
            viewModel.exportTo(uri, context)
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

                    // 过滤切换按钮
                    IconButton(
                        onClick = { showFilter = !showFilter },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            if (showFilter) Icons.Default.FilterListOff else Icons.Default.FilterList,
                            contentDescription = "过滤",
                            tint = if (viewModel.hasActiveFilter()) MaterialTheme.colorScheme.primary
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
                    onRssiMinChange = { viewModel.setRssiMinFilter(it) },
                    onRssiMaxChange = { viewModel.setRssiMaxFilter(it) },
                    onMacPatternChange = { viewModel.setMacFilterPattern(it) },
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

                    // 自动滚动到最新
                    LaunchedEffect(records.size) {
                        if (records.isNotEmpty()) {
                            listState.animateScrollToItem(records.size - 1)
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

// ── 过滤条组件 ────────────────────────────────────────────

@Composable
private fun FilterBar(
    rssiMin: Int?,
    rssiMax: Int?,
    macPattern: String,
    onRssiMinChange: (Int?) -> Unit,
    onRssiMaxChange: (Int?) -> Unit,
    onMacPatternChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text("过滤条件", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RSSI:", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.width(4.dp))
                OutlinedTextField(
                    value = rssiMin?.toString() ?: "",
                    onValueChange = { onRssiMinChange(it.toIntOrNull()) },
                    placeholder = { Text("最小", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.width(64.dp).height(48.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Text(" ~ ", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = rssiMax?.toString() ?: "",
                    onValueChange = { onRssiMaxChange(it.toIntOrNull()) },
                    placeholder = { Text("最大", style = MaterialTheme.typography.bodySmall) },
                    modifier = Modifier.width(64.dp).height(48.dp),
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("dBm", style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(6.dp))
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

            // 第三行：附加参数（含中文标注）
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Event: ${record.eventTypeLabel}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
