package com.glass.lisn.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.glass.lisn.EngineHub
import com.glass.lisn.ui.AppViewModel
import com.glass.lisn.ui.theme.Aurora3
import com.glass.lisn.ui.theme.Text1
import com.glass.lisn.ui.theme.Text2
import com.glass.lisn.ui.theme.Text3
import kotlinx.coroutines.launch

data class LrcLine(val timeMs: Long, val text: String)

object LrcParser {
    /** 兼容标准 [mm:ss.xx] 与酷我的 [ss.xx] 秒制时间戳 */
    private val lineRegex = Regex("""\[(\d{1,3})(?::(\d{1,2}))?(?:[.:](\d{1,3}))?]""")

    private fun fracMs(raw: String): Long = when (raw.length) {
        0 -> 0L; 1 -> raw.toLong() * 100; 2 -> raw.toLong() * 10; else -> raw.take(3).toLong()
    }

    fun parse(lrc: String): List<LrcLine> {
        val out = mutableListOf<LrcLine>()
        for (raw in lrc.lineSequence()) {
            val stamps = lineRegex.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val text = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val g1 = m.groupValues[1]; val g2 = m.groupValues[2]; val g3 = m.groupValues[3]
                val timeMs = if (g2.isEmpty()) {
                    g1.toLong() * 1000 + fracMs(g3)          // [ss.xx]
                } else {
                    g1.toLong() * 60000 + g2.toLong() * 1000 + fracMs(g3)  // [mm:ss.xx]
                }
                out += LrcLine(timeMs, text)
            }
        }
        return out.sortedBy { it.timeMs }
    }
}

@Composable
fun NowPlayingSheet(vm: AppViewModel) {
    val playback by vm.player.playback.collectAsState()
    val isPlaying by vm.player.isPlaying.collectAsState()
    val buffering by vm.player.isBuffering.collectAsState()
    val localTitle by vm.player.currentLocalTitle.collectAsState()
    val playMode by vm.player.playMode.collectAsState()

    val song = playback.queue.getOrNull(playback.queueIdx)
    val title = song?.name ?: (localTitle ?: "未在播放")
    val artist = song?.artist ?: "本地音乐"

    var showSleepDialog by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var lrc by remember(song?.key ?: localTitle) { mutableStateOf<List<LrcLine>>(emptyList()) }
    // 中部区域三态:封面 / 歌词 / 队列
    var centerTab by remember { mutableStateOf("cover") } // cover | lyric | queue
    // 切歌/自动跳曲时收起遗留对话框,避免标题错乱
    LaunchedEffect(song?.key ?: localTitle) {
        showSleepDialog = false
        showAddDialog = false
    }
    LaunchedEffect(song?.key ?: localTitle) {
        lrc = emptyList()
        val s = song ?: return@LaunchedEffect
        runCatching { EngineHub.registry.getLyric(s) }.getOrNull()?.let { raw ->
            lrc = LrcParser.parse(raw)
        }
    }


    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 沉浸背景:封面铺满 + 重遮罩(叠层,不占布局空间)
        if (!song?.picUrl.isNullOrEmpty()) {
            AsyncImage(song.picUrl, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.86f)))
        }
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {
        // 顶部:收起 + 曲名区
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.nowPlayingOpen = false }) {
                Icon(Icons.Filled.ExpandMore, "收起", tint = Text2)
            }
            IconButton(onClick = { showSleepDialog = true }) {
                Icon(Icons.Filled.Bedtime, "睡眠定时", tint = Text2)
            }
            Column(
                Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(listOfNotNull(artist, song?.album?.takeIf { it.isNotBlank() }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = Text2, maxLines = 1)
            }
            IconButton(onClick = { if (song != null) showAddDialog = true }) {
                Icon(Icons.Filled.PlaylistAdd, "加入歌单", tint = Text2)
            }
        }

        // 中部:封面 / 歌词 / 队列
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (centerTab) {
                "lyric" -> Column(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xD90D1017))
                        .padding(horizontal = 8.dp)
                ) {
                    LyricSection(vm, lrc)
                }
                "queue" -> Column(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0xD90D1017))
                        .padding(horizontal = 8.dp)
                ) {
                    LazyColumn(modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (playback.queue.isEmpty()) {
                            item {
                                Text("队列是空的", style = MaterialTheme.typography.bodyMedium, color = Text3,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), textAlign = TextAlign.Center)
                            }
                        } else {
                            items(playback.queue.size) { i ->
                                val q = playback.queue[i]
                                val active = i == playback.queueIdx
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val s = playback.queue.getOrNull(i)
                                            if (s != null) vm.player.play(s, playback.queue, i)
                                        }
                                        .padding(vertical = 8.dp, horizontal = 12.dp)
                                ) {
                                    Text(
                                        "${i + 1}. ${q.name}",
                                        fontSize = if (active) 15.sp else 13.sp,
                                        color = if (active) MaterialTheme.colorScheme.primary else Text1,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        q.artist,
                                        fontSize = 11.sp,
                                        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.7f) else Text3,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
                else -> {
                    Box(
                        Modifier
                            .size(280.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!song?.picUrl.isNullOrEmpty()) {
                            AsyncImage(song.picUrl, null, Modifier.size(280.dp), contentScale = ContentScale.Crop)
                        } else {
                            Text("♪", fontSize = 64.sp, color = Text3)
                        }
                    }
                }
            }
        }

        // 封面/歌词/队列 切换 + 解析信息
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val quality = vm.settings.quality
            Text(
                when (quality) { "flac" -> "SQ"; "128k" -> "128K"; else -> "320K" },
                fontSize = 12.sp,
                color = Aurora3,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        val next = when (quality) { "128k" -> "320k"; "320k" -> "flac"; else -> "128k" }
                        vm.patchSettings(vm.settings.copy(quality = next))
                        vm.showToast("音质切换为 " + next.uppercase() + ",下一首生效")
                    }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
            Spacer(Modifier.size(6.dp))
            listOf("cover" to "封面", "lyric" to "歌词", "queue" to "队列").forEach { (tab, label) ->
                Text(
                    label,
                    fontSize = 12.sp,
                    color = if (centerTab == tab) MaterialTheme.colorScheme.primary else Text2,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { centerTab = tab }
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                )
            }
        }
        if (playback.resolveInfo != null) {
            Text(
                "来源:${playback.resolveInfo}",
                style = MaterialTheme.typography.labelSmall, color = Text3,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
            )
        }
        if (playback.error != null) {
            Text(
                playback.error!!,
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp), textAlign = TextAlign.Center, maxLines = 2
            )
        }

        // 进度条(高频状态下放到子组件,避免整页高频重组)
        ProgressSection(vm)

        // 控制区
        Row(
            Modifier.fillMaxWidth().padding(vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    val next = when (playMode) { "loop" -> "shuffle"; "shuffle" -> "one"; else -> "loop" }
                    vm.player.setPlayMode(next)
                },
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    when (playMode) {
                        "one" -> Icons.Filled.RepeatOne
                        "shuffle" -> Icons.Filled.Shuffle
                        else -> Icons.Filled.Repeat
                    },
                    when (playMode) {
                        "one" -> "单曲循环"
                        "shuffle" -> "随机播放"
                        else -> "列表循环"
                    },
                    modifier = Modifier.size(28.dp),
                    tint = if (playMode == "loop") Text1 else MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = { vm.player.prev() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipPrevious, "上一曲", modifier = Modifier.size(36.dp))
            }
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(36.dp))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { vm.player.toggle() },
                contentAlignment = Alignment.Center
            ) {
                if (buffering) CircularProgressIndicator(Modifier.size(30.dp), color = MaterialTheme.colorScheme.background, strokeWidth = 3.dp)
                else Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "播放/暂停",
                    tint = MaterialTheme.colorScheme.background,
                    modifier = Modifier.size(38.dp)
                )
            }
            IconButton(onClick = { vm.player.next() }, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Filled.SkipNext, "下一曲", modifier = Modifier.size(36.dp))
            }
        }
        }
    }

    if (showSleepDialog) {
        SleepTimerDialog(onDismiss = { showSleepDialog = false }, onPick = { vm.player.setSleepTimer(it) })
    }

    if (showAddDialog) song?.let { target ->
        com.glass.lisn.ui.views.dialogs.AddToPlaylistDialog(
            playlists = vm.playlists,
            songName = target.name,
            onDismiss = { showAddDialog = false },
            onCreateNew = { name ->
                val pl = vm.createPlaylistGet(name)
                vm.addSongToPlaylist(pl.id, target)
                showAddDialog = false
            },
            onPick = { pl ->
                vm.addSongToPlaylist(pl.id, target)
                showAddDialog = false
            }
        )
    }
}

private fun formatMs(ms: Long): String {
    if (ms <= 0) return "0:00"
    val total = ms / 1000
    return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
}

@Composable
private fun SleepTimerDialog(onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("睡眠定时", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Text("播放将在选定时间后自动暂停", style = MaterialTheme.typography.bodySmall, color = Text2)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15 to "15 分钟", 30 to "30 分钟", 60 to "60 分钟").forEach { (min, label) ->
                        androidx.compose.material3.OutlinedButton(
                            onClick = { onPick(min); onDismiss() },
                            shape = RoundedCornerShape(10.dp)
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.TextButton(onClick = { onPick(0); onDismiss() }) {
                    Text("取消定时", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("关闭", color = Text2) }
        }
    )
}

/** 进度条子组件:position/duration 高频状态只重组此区块 */
@Composable
private fun ProgressSection(vm: AppViewModel) {
    val positionMs by vm.player.positionMs.collectAsState()
    val durationMs by vm.player.durationMs.collectAsState()
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Slider(
            value = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = { if (durationMs > 0) vm.player.seekTo((it * durationMs).toLong()) },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMs(positionMs), style = MaterialTheme.typography.labelSmall, color = Text3)
            Text(formatMs(durationMs), style = MaterialTheme.typography.labelSmall, color = Text3)
        }
    }
}

/** 歌词子组件:滚动同步的高频状态只重组此区块 */
@Composable
private fun LyricSection(vm: AppViewModel, lrc: List<LrcLine>) {
    val positionMs by vm.player.positionMs.collectAsState()
    val currentLine = remember(lrc, positionMs) {
        if (lrc.isEmpty()) -1 else (lrc.indexOfLast { it.timeMs <= positionMs + 300 }.takeIf { it >= 0 } ?: -1)
    }
    val listState = rememberLazyListState()
    LaunchedEffect(currentLine) {
        if (currentLine >= 0) runCatching { listState.animateScrollToItem(maxOf(0, currentLine - 4)) }
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (lrc.isEmpty()) {
            item {
                Text("暂无歌词", style = MaterialTheme.typography.bodyMedium, color = Text3,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp), textAlign = TextAlign.Center)
            }
        } else {
            items(lrc.size) { i ->
                val line = lrc[i]
                val active = i == currentLine
                Text(
                    line.text.ifEmpty { "···" },
                    fontSize = if (active) 17.sp else 14.sp,
                    color = if (active) MaterialTheme.colorScheme.primary else Text2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { vm.player.seekTo(line.timeMs) }
                        .padding(vertical = 8.dp, horizontal = 8.dp)
                )
            }
        }
    }
}
