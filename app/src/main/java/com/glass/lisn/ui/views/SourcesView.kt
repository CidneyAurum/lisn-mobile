package com.glass.lisn.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glass.lisn.ui.AppViewModel
import com.glass.lisn.ui.theme.Ok
import com.glass.lisn.ui.theme.Text2
import com.glass.lisn.ui.theme.Text3
import com.glass.lisn.ui.theme.Warn
import com.glass.lisn.ui.views.dialogs.TextInputDialog

@Composable
private fun HealthDot(status: String) {
    val color = when (status) {
        "ok" -> Ok; "error" -> MaterialTheme.colorScheme.error; "disabled" -> Text3
        else -> Warn
    }
    Box(Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun SourcesView(vm: AppViewModel) {
    val sources = vm.sources
    var addHttp by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }

    if (sources == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("音源中心", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                checking = true
                vm.checkUpdates { checking = false }
            }) {
                if (checking) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else Icon(Icons.Filled.Refresh, "检查更新", tint = Text2)
            }
            IconButton(onClick = { vm.view = com.glass.lisn.ui.View.SETTINGS }) {
                Icon(Icons.Filled.Settings, "设置", tint = Text2)
            }
        }

        // 解析模式
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("解析模式", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (sources.mode == "auto") "自动:按音源顺序竞速,首个成功者播放" else "手动:锁定单个音源解析",
                    style = MaterialTheme.typography.bodySmall, color = Text2
                )
            }
            Text(
                if (sources.mode == "auto") "自动" else "手动",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable { vm.setMode(if (sources.mode == "auto") "manual" else "auto") }
            )
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 20.dp)) {
            // 聚合 Provider 概览
            item {
                Text("已加载音源", style = MaterialTheme.typography.titleMedium, color = Text2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            items(sources.providers.size) { i ->
                val p = sources.providers[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.testSource(p.id) { ok, detail -> vm.showToast(if (ok) "✓ $detail" else "✗ $detail") } }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HealthDot(p.health.status)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${p.kind} · ${p.caps.platforms.joinToString("/")} · ${p.caps.qualities.joinToString("/")}" +
                                (p.health.latencyMs?.let { " · ${it}ms" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = Text2, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                        p.health.lastError?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Text("测试", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
            // lx 自定义源脚本
            item {
                Text("lx 自定义源", style = MaterialTheme.typography.titleMedium, color = Text2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
            items(sources.lxEntries.size) { i ->
                val e = sources.lxEntries[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                if (e.platforms.isNotEmpty()) e.platforms.joinToString("/").uppercase() else null,
                                if (e.qualities.isNotEmpty()) e.qualities.joinToString("/") else null,
                                e.remoteDate?.take(10)?.let { "更新 $it" } ?: "未加载"
                            ).joinToString(" · ").ifEmpty { " " },
                            style = MaterialTheme.typography.bodySmall, color = Text2
                        )
                    }
                    if (e.enabled) {
                        Text(
                            "升级",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { vm.upgradeLx(e.id) { ok, detail -> vm.showToast(detail) } }
                                .padding(8.dp)
                        )
                    }
                    Switch(
                        checked = e.enabled,
                        onCheckedChange = { vm.setLxEnabled(e.id, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }
            // MusicFree 插件管理
            items(sources.mfEntries.size) { i ->
                val e = sources.mfEntries[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(e.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            listOfNotNull(
                                e.platform.uppercase(),
                                e.version?.let { "v$it" },
                                e.remoteDate?.take(10)?.let { "更新 $it" }
                            ).joinToString(" · ").ifEmpty { " " },
                            style = MaterialTheme.typography.bodySmall, color = Text2
                        )
                    }
                    if (e.enabled) {
                        Text(
                            "升级",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clickable { vm.upgradeMf(e.id) { ok, detail -> vm.showToast(detail) } }
                                .padding(8.dp)
                        )
                    }
                    Switch(
                        checked = e.enabled,
                        onCheckedChange = { vm.setMfEnabled(e.id, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }
            // HTTP 模板
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("HTTP 模板音源", style = MaterialTheme.typography.titleMedium, color = Text2, modifier = Modifier.weight(1f))
                    Text(
                        "添加",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable { addHttp = true }.padding(6.dp)
                    )
                }
            }
            items(sources.templates.size) { i ->
                val t = sources.templates[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            t.urlTemplate.take(60),
                            style = MaterialTheme.typography.bodySmall, color = Text2, maxLines = 1, overflow = TextOverflow.Ellipsis
                        )
                    }
                    Switch(
                        checked = t.enabled ?: false,
                        onCheckedChange = { vm.setHttpEnabled(t.id, it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                    )
                }
            }
        }
    }

    if (addHttp) {
        AddHttpDialog(
            onDismiss = { addHttp = false },
            onConfirm = { name, tpl ->
                vm.addHttpTemplate(name, tpl) { msg -> vm.showToast(msg) }
                addHttp = false
            }
        )
    }
}

@Composable
private fun AddHttpDialog(onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var tpl by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("添加 HTTP 音源", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "形如 https://host/url/{source}/{songId}/{quality}",
                    style = MaterialTheme.typography.bodySmall, color = Text3
                )
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    placeholder = { Text("名称", color = Text2) },
                    singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                )
                androidx.compose.material3.OutlinedTextField(
                    value = tpl, onValueChange = { tpl = it },
                    placeholder = { Text("URL 模板", color = Text2) },
                    singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { onConfirm(name, tpl) },
                enabled = name.isNotBlank() && tpl.isNotBlank()
            ) { Text("添加", color = MaterialTheme.colorScheme.primary) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消", color = Text2) }
        }
    )
}
