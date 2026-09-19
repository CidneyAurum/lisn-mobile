package com.glass.lisn.ui.views

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.glass.lisn.model.Settings
import com.glass.lisn.ui.AppViewModel
import com.glass.lisn.ui.theme.Text2

@Composable
fun SettingsView(vm: AppViewModel) {
    val s = vm.settings
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.view = com.glass.lisn.ui.View.DISCOVER }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Text2)
            }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        // 音质
        SectionCard(title = "默认音质", desc = "解析时按 flac→320k→128k 自动降级(取决于音源能力)") {
            val opts = listOf("128k", "320k", "flac")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                opts.forEachIndexed { i, q ->
                    SegmentedButton(
                        selected = s.quality == q,
                        onClick = { vm.patchSettings(s.copy(quality = q)) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = opts.size),
                        label = { Text(q.uppercase()) }
                    )
                }
            }
        }

        // 播放模式
        SectionCard(title = "播放模式", desc = "队列播完后的行为;随机模式在整队内随机跳曲") {
            val opts = listOf("loop" to "列表循环", "one" to "单曲循环", "shuffle" to "随机")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                opts.forEachIndexed { i, (m, label) ->
                    SegmentedButton(
                        selected = s.playMode == m,
                        onClick = {
                            vm.patchSettings(s.copy(playMode = m))
                            vm.player.setPlayMode(m)
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = opts.size),
                        label = { Text(label) }
                    )
                }
            }
        }

        // 解析模式
        SectionCard(
            title = "解析模式",
            desc = if (s.mode == "auto") "自动:多音源按序竞速,首个成功者播放" else "手动:在音源中心锁定单个音源"
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (s.mode == "auto") "自动竞速" else "手动锁定",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = s.mode == "manual",
                    onCheckedChange = { manual -> vm.patchSettings(s.copy(mode = if (manual) "manual" else "auto")) },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                )
            }
        }

        // 桌面歌词
        val ctx = androidx.compose.ui.platform.LocalContext.current
        SectionCard(
            title = "桌面歌词",
            desc = "在其他应用上层显示 Limbus 风格歌词(逐字显现/飘散消逝),全穿透不遮挡触控"
        ) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (s.desktopLyrics) "已开启" else "已关闭",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (s.desktopLyrics) MaterialTheme.colorScheme.primary else Text2,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = s.desktopLyrics,
                    onCheckedChange = { v ->
                        if (v && !com.glass.lisn.ui.overlay.DesktopLyricsOverlay.canOverlay(ctx)) {
                            vm.toast = "请先在系统设置中允许「显示在其他应用上层」,再回来开启"
                            ctx.startActivity(
                                android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + ctx.packageName)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        } else {
                            vm.patchSettings(s.copy(desktopLyrics = v))
                            if (v) com.glass.lisn.ui.overlay.DesktopLyricsOverlay.start(ctx)
                            else com.glass.lisn.ui.overlay.DesktopLyricsOverlay.stop()
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary)
                )
            }
        }

        // 更新
        SectionCard(title = "自动检查音源更新", desc = "启动时查询 keep-alive 仓库的插件脚本更新") {
            Switch(
                checked = s.autoCheckUpdates,
                onCheckedChange = { v -> vm.patchSettings(s.copy(autoCheckUpdates = v)) },
                colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // 关于
        SectionCard(title = "关于", desc = null) {
            Text("聆 LISN Mobile v${com.glass.lisn.BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Text(
                "基于桌面端「聆 LISN」移植 · 聚合音源播放器\nGD音乐台 / MusicFree 插件 / HTTP 模板音源",
                style = MaterialTheme.typography.bodySmall, color = Text2,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun SectionCard(title: String, desc: String?, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(14.dp)
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        desc?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Text2, modifier = Modifier.padding(top = 2.dp)) }
        content()
    }
}
