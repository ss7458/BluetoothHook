package com.jingyu233.bluetoothhook.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jingyu233.bluetoothhook.data.bridge.CaptureBridge
import com.jingyu233.bluetoothhook.data.bridge.HookStatusHelper

/**
 * 统一的 Hook 状态展示（首页唯一入口，抓包页不再重复）
 */
@Composable
fun HookStatusSection(
    status: HookStatusHelper.Status,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    initiallyExpanded: Boolean = false
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }

    val (containerColor, icon) = when (status.activation) {
        HookStatusHelper.Activation.Active -> Pair(
            MaterialTheme.colorScheme.primaryContainer,
            Icons.Default.CheckCircle
        )
        HookStatusHelper.Activation.Inactive -> Pair(
            MaterialTheme.colorScheme.errorContainer,
            Icons.Default.Warning
        )
        HookStatusHelper.Activation.Unknown -> Pair(
            MaterialTheme.colorScheme.surfaceVariant,
            Icons.Default.Info
        )
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = status.summary,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "请在 LSPosed 中启用模块并重启蓝牙服务",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新 Hook 状态")
                }
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(
                        if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        contentDescription = if (expanded) "收起详情" else "展开详情"
                    )
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))

                val detail = status.detail
                if (detail != null) {
                    HookDetailRow("SDK", "${detail.sdkInt}")
                    Spacer(modifier = Modifier.height(8.dp))
                    HookDetailRow(
                        label = "Class",
                        value = detail.classFound,
                        ok = detail.classFound != "NONE"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HookDetailRow(
                        label = "Method",
                        value = detail.methodFound,
                        ok = detail.methodFound != "NONE"
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    HookDetailRow(
                        label = "Fields",
                        value = detail.fieldsResolved,
                        ok = detail.fieldsResolved != "NONE" && detail.fieldsResolved != "pending"
                    )
                } else {
                    Text(
                        text = "等待蓝牙扫描启动…（可开启任意 App 的 BLE 扫描，或重启蓝牙）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun HookDetailRow(
    label: String,
    value: String,
    ok: Boolean? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$label:",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(56.dp)
        )
        if (ok != null) {
            Icon(
                imageVector = if (ok) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (ok) Color(0xFF4CAF50) else Color(0xFFFF9800),
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = when {
                ok == true -> Color(0xFF4CAF50)
                ok == false -> Color(0xFFFF9800)
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}
