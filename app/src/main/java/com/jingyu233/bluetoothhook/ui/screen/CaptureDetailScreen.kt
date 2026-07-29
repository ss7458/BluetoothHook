package com.jingyu233.bluetoothhook.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jingyu233.bluetoothhook.data.model.CaptureRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 抓包记录详情页
 * 解析 AD (Advertisement Data) 结构，展示 hex 各字段
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureDetailScreen(
    record: CaptureRecord?,
    onNavigateBack: () -> Unit
) {
    val clipboardManager = LocalClipboardManager.current
    val timeFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()) }
    val adStructures = remember(record) {
        record?.advDataHex?.let { parseAdStructures(it) } ?: emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("数据详情") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (record != null) {
                        IconButton(onClick = {
                            clipboardManager.setText(AnnotatedString(record.advDataHex))
                        }) {
                            Icon(Icons.Default.ContentCopy, contentDescription = "复制 Hex")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (record == null) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("未找到记录", style = MaterialTheme.typography.bodyLarge)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // ── 基本信息卡片 ────────────────────────────────
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp)) {
                    DetailRow("时间", timeFormat.format(Date(record.timestamp)))
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DetailRow("MAC 地址", record.mac)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DetailRow("RSSI", "${record.rssi} dBm")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DetailRow("事件类型", "0x${record.eventType.toString(16).uppercase()} (${record.eventTypeLabel})")
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DetailRow("主 PHY", record.phyLabel)
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    DetailRow("地址类型", record.addressTypeLabel)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── AD Structure 解析 ────────────────────────────
            Text(
                text = "AD Structure 解析 (${adStructures.size} 个)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (adStructures.isEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "无 AD 数据",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            } else {
                adStructures.forEachIndexed { index, ad ->
                    AdStructureCard(index = index, ad = ad)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ── 原始 Hex ──────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "原始数据 (${record.advDataHex.length / 2} 字节)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    clipboardManager.setText(AnnotatedString(record.advDataHex))
                }) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "复制",
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("复制", style = MaterialTheme.typography.labelSmall)
                }
            }
            Spacer(modifier = Modifier.height(4.dp))

            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        text = record.advDataHex,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * AD (Advertisement Data) 结构卡片
 */
@Composable
private fun AdStructureCard(index: Int, ad: AdStructure) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "#$index",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "0x${ad.typeHex} — ${ad.typeName}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${ad.dataLen} 字节",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = ad.dataHex,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ── AD 结构数据模型 ──────────────────────────────────────

data class AdStructure(
    val typeHex: String,
    val typeName: String,
    val dataLen: Int,
    val dataHex: String
)

/**
 * 解析 BLE AD (Advertisement Data) 结构。
 * 输入: 纯 hex 字符串（无空格，例如 "0201060303464142"）
 * 格式: 每段 -> [1 byte 长度(L)][1 byte AD Type][L-1 bytes 数据]
 */
private fun parseAdStructures(hex: String): List<AdStructure> {
    val bytes = hexUtilsHexToBytes(hex) ?: return emptyList()
    val result = mutableListOf<AdStructure>()
    var i = 0
    while (i < bytes.size) {
        val len = bytes[i].toInt() and 0xFF
        if (len == 0) { i++; continue }          // 结束标记
        if (i + len >= bytes.size) break          // 数据不完整
        if (len < 1) { i++; continue }            // 异常长度
        val type = bytes[i + 1].toInt() and 0xFF
        val dataBytes = if (len > 1) bytes.sliceArray(i + 2..i + len) else byteArrayOf()
        result.add(
            AdStructure(
                typeHex = "0x${type.toString(16).uppercase().padStart(2, '0')}",
                typeName = adTypeName(type),
                dataLen = dataBytes.size,
                dataHex = dataBytes.joinToString("") { b ->
                    String.format("%02X", b.toInt() and 0xFF)
                }
            )
        )
        i += len + 1
    }
    return result
}

/**
 * AD Type 名称映射（常见类型）
 */
private fun adTypeName(type: Int): String = when (type) {
    0x01 -> "Flags"
    0x02 -> "Incomplete Service UUIDs (16-bit)"
    0x03 -> "Complete Service UUIDs (16-bit)"
    0x04 -> "Incomplete Service UUIDs (32-bit)"
    0x05 -> "Complete Service UUIDs (32-bit)"
    0x06 -> "Incomplete Service UUIDs (128-bit)"
    0x07 -> "Complete Service UUIDs (128-bit)"
    0x08 -> "Shortened Local Name"
    0x09 -> "Complete Local Name"
    0x0A -> "Tx Power Level"
    0x0D -> "Class of Device"
    0x0E -> "Simple Pairing Hash C"
    0x0F -> "Simple Pairing Randomizer R"
    0x10 -> "Device ID"
    0x12 -> "Security Manager TK Value"
    0x14 -> "LE Role"
    0x15 -> "Simple Pairing Hash C (256-bit)"
    0x16 -> "Service Data (16-bit UUID)"
    0x17 -> "Public Target Address"
    0x18 -> "Random Target Address"
    0x19 -> "Appearance"
    0x1A -> "Advertising Interval"
    0x1B -> "LE Bluetooth Device Address"
    0x1C -> "LE Role"
    0x1D -> "Simple Pairing Hash C (256-bit)"
    0x1E -> "Service Data (32-bit UUID)"
    0x1F -> "Service Data (128-bit UUID)"
    0x20 -> "LE Secure Connections Confirmation Value"
    0x21 -> "LE Secure Connections Random Value"
    0x22 -> "URI"
    0x24 -> "Indoor Positioning"
    0x2A -> "Mesh Beacon"
    0x2B -> "Mesh Message"
    0xFF -> "Manufacturer Specific Data"
    else -> "Unknown"
}

/** 将 hex 字符串转为字节数组（支持空白分隔） */
internal fun hexUtilsHexToBytes(hex: String): ByteArray? {
    val sanitized = hex.replace("\\s".toRegex(), "")
    if (sanitized.isEmpty()) return ByteArray(0)
    if (sanitized.length % 2 != 0) return null
    return ByteArray(sanitized.length / 2) { i ->
        ((sanitized[i * 2].digitToIntOrNull(16) ?: return null) * 16 +
         (sanitized[i * 2 + 1].digitToIntOrNull(16) ?: return null)).toByte()
    }
}
