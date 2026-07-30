package com.jingyu233.bluetoothhook.ui.screen

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jingyu233.bluetoothhook.data.model.BleAdConstants
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
                    DetailRow("距离", BleAdConstants.distanceDescription(record.rssi))
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
                Row(modifier = Modifier.horizontalScroll(rememberScrollState()).padding(8.dp)) {
                    if (adStructures.isEmpty()) {
                        Text(
                            text = record.advDataHex,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(4.dp)
                        )
                    } else {
                        adStructures.forEach { ad ->
                            val bgColor = adCategoryColor(ad.colorCategory)
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = bgColor.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    text = ad.fullHex,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = bgColor,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                    }
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

/** AD 类别 → 颜色 */
private fun adCategoryColor(cat: Int): Color = when (cat) {
    1 -> Color(0xFF1565C0) // UUID → blue
    2 -> Color(0xFF2E7D32) // Name → green
    3 -> Color(0xFFE65100) // Tx Power → orange
    4 -> Color(0xFF6A1B9A) // Flags → purple
    5 -> Color(0xFFAD1457) // Appearance → pink
    6 -> Color(0xFFC62828) // Manufacturer → red
    7 -> Color(0xFF00838F) // Service Data → cyan
    else -> Color(0xFF616161) // other → gray
}

/**
 * AD (Advertisement Data) 结构卡片
 */
@Composable
private fun AdStructureCard(index: Int, ad: AdStructure) {
    val catColor = adCategoryColor(ad.colorCategory)
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            // ── 标题行 ──
            Row(modifier = Modifier.fillMaxWidth()) {
                Surface(
                    shape = MaterialTheme.shapes.extraSmall,
                    color = catColor.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "#$index",
                        style = MaterialTheme.typography.labelSmall,
                        color = catColor,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = ad.typeHex,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    color = catColor,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = ad.typeName,
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

            // ── 解析详情 ──
            if (ad.details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                HorizontalDivider(
                    color = catColor.copy(alpha = 0.2f),
                    thickness = 1.dp
                )
                Spacer(modifier = Modifier.height(4.dp))
                ad.details.forEach { (label, value) ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(96.dp)
                        )
                        Text(
                            text = value,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            fontFamily = if (value.startsWith("0x") || value.contains("-"))
                                FontFamily.Monospace else FontFamily.Default,
                            fontSize = if (value.length > 32) 10.sp else 13.sp
                        )
                    }
                }
            }

            // ── Hex 数据（着色） ──
            Spacer(modifier = Modifier.height(4.dp))
            Surface(
                shape = MaterialTheme.shapes.extraSmall,
                color = catColor.copy(alpha = 0.08f)
            ) {
                Text(
                    text = ad.dataHex,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    color = catColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

// ── AD 结构数据模型 ──────────────────────────────────────

data class AdStructure(
    val typeHex: String,
    val typeName: String,
    val dataLen: Int,
    val dataHex: String,
    /** 完整段 hex（含长度 + 类型 + 数据） */
    val fullHex: String = "",
    /** AD 字节在原始数据中的起始偏移 */
    val offset: Int = 0,
    /** 解析后的键值对详情 */
    val details: List<Pair<String, String>> = emptyList(),
    /** 颜色分类 (0=gray, 1=blue/UUID, 2=green/name, 3=orange/tx, 4=purple/flags, 5=pink/appearance, 6=red/manuf, 7=cyan/service) */
    val colorCategory: Int = 0
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
        val dataHex = dataBytes.joinToString("") { b ->
            String.format("%02X", b.toInt() and 0xFF)
        }
        val fullHex = bytes.sliceArray(i..(i + len))
            .joinToString("") { String.format("%02X", it.toInt() and 0xFF) }
        val offset = i
        val details = mutableListOf<Pair<String, String>>()
        val colorCategory = adColorCategory(type)

        // 按 AD Type 解析详情
        when (type) {
            0x01 -> { // Flags
                if (dataBytes.isNotEmpty()) {
                    val flags = BleAdConstants.parseFlags(dataBytes[0].toInt() and 0xFF)
                    flags.forEach { details.add("Flag" to it) }
                }
            }
            0x02, 0x03 -> { // 16-bit UUIDs
                val list = BleAdConstants.parseUuid16List(dataBytes, type == 0x03)
                list.forEach { (name, uuid) ->
                    if (name == "Unknown") {
                        details.add("UUID" to uuid)
                    } else {
                        details.add(uuid to name)
                    }
                }
            }
            0x04, 0x05 -> { // 32-bit UUIDs
                val list = BleAdConstants.parseUuid32List(dataBytes)
                list.forEach { details.add("UUID" to it) }
            }
            0x06, 0x07 -> { // 128-bit UUIDs
                val list = BleAdConstants.parseUuid128List(dataBytes)
                list.forEach { details.add("UUID" to it) }
            }
            0x08, 0x09 -> { // Local Name
                val name = dataBytes.joinToString("") { c ->
                    val v = c.toInt() and 0xFF
                    if (v in 0x20..0x7E) v.toChar().toString() else "?"
                }
                details.add("名称" to name)
            }
            0x0A -> { // Tx Power Level
                if (dataBytes.isNotEmpty()) {
                    val txPower = dataBytes[0].toInt() // already signed byte
                    details.add("Tx Power" to "$txPower dBm")
                }
            }
            0x16 -> { // Service Data (16-bit UUID)
                if (dataBytes.size >= 2) {
                    val uuid = (dataBytes[0].toInt() and 0xFF) or
                               ((dataBytes[1].toInt() and 0xFF) shl 8)
                    val name = BleAdConstants.uuid16Name(uuid) ?: "Unknown"
                    val uuidStr = "0x${uuid.toString(16).uppercase().padStart(4, '0')}"
                    details.add("Service" to "$name ($uuidStr)")
                    if (dataBytes.size > 2) {
                        val extra = dataBytes.sliceArray(2..dataBytes.lastIndex)
                            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                        details.add("数据" to extra)
                    }
                }
            }
            0x19 -> { // Appearance
                if (dataBytes.size >= 2) {
                    val value = (dataBytes[0].toInt() and 0xFF) or
                                ((dataBytes[1].toInt() and 0xFF) shl 8)
                    details.add("外观" to BleAdConstants.parseAppearance(value))
                }
            }
            0xFF -> { // Manufacturer Specific Data
                val manuf = BleAdConstants.parseManufacturerId(dataBytes)
                if (manuf != null) {
                    val (id, name) = manuf
                    details.add("制造商" to "$name (0x${id.toString(16).uppercase().padStart(4, '0')})")
                    // 检测 iBeacon
                    val ibeacon = BleAdConstants.parseIBeacon(dataBytes)
                    if (ibeacon != null) {
                        details.add("iBeacon" to "✓")
                        details.add("Proximity UUID" to ibeacon.proximityUuid)
                        details.add("Major" to ibeacon.major.toString())
                        details.add("Minor" to ibeacon.minor.toString())
                        details.add("参考 RSSI" to "${ibeacon.txPower} dBm")
                    } else if (dataBytes.size > 2) {
                        val extra = dataBytes.sliceArray(2..dataBytes.lastIndex)
                            .joinToString("") { "%02X".format(it.toInt() and 0xFF) }
                        details.add("自定义数据" to extra)
                    }
                }
            }
        }

        result.add(
            AdStructure(
                typeHex = "0x${type.toString(16).uppercase().padStart(2, '0')}",
                typeName = adTypeName(type),
                dataLen = dataBytes.size,
                dataHex = dataHex,
                fullHex = fullHex,
                offset = offset,
                details = details,
                colorCategory = colorCategory
            )
        )
        i += len + 1
    }
    return result
}

/** AD Type → 颜色分类 */
private fun adColorCategory(type: Int): Int = when (type) {
    0x01 -> 4  // Flags → purple
    0x02, 0x03, 0x04, 0x05, 0x06, 0x07 -> 1  // UUIDs → blue
    0x08, 0x09 -> 2  // Local Name → green
    0x0A -> 3  // Tx Power → orange
    0x16, 0x1E, 0x1F -> 7  // Service Data → cyan
    0x19 -> 5  // Appearance → pink
    0xFF -> 6  // Manufacturer → red
    else -> 0  // other → gray
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
