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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glass.lisn.model.DownloadStatus
import com.glass.lisn.ui.AppViewModel
import com.glass.lisn.ui.theme.Ok
import com.glass.lisn.ui.theme.Text2
import com.glass.lisn.ui.theme.Text3

/** 下载页:分段切换 下载队列 / 本地音乐 */
@Composable
fun DownloadsView(vm: AppViewModel) {
    var tab by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    label = { Text("下载队列") }
                )
                SegmentedButton(
                    selected = tab == 1,
                    onClick = { tab = 1; vm.refreshLibrary() },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    label = { Text("本地音乐") }
                )
            }
        }
        if (tab == 0) DownloadQueue(vm) else LocalLibrary(vm)
    }
}

@Composable
private fun DownloadQueue(vm: AppViewModel) {
    val downloads by vm.downloads.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("共 ${downloads.size} 项", style = MaterialTheme.typography.bodySmall, color = Text2, modifier = Modifier.weight(1f))
            Text(
                "清空已完成",
                style = MaterialTheme.typography.bodySmall,
                color = if (downloads.isEmpty()) Text3 else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(enabled = downloads.isNotEmpty()) { vm.clearFinishedDownloads() }.padding(6.dp)
            )
        }
        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.MusicNote, null, tint = Text2, modifier = Modifier.size(48.dp))
                    Text("队列为空", style = MaterialTheme.typography.bodyMedium, color = Text2, modifier = Modifier.padding(top = 8.dp))
                    Text("在歌曲的「更多」菜单里选择下载", style = MaterialTheme.typography.bodySmall, color = Text3)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(downloads.size) { i ->
                    val d = downloads[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("${d.artist} - ${d.name}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                listOfNotNull(
                                    d.quality,
                                    when (d.status) {
                                        DownloadStatus.WAITING -> "等待中"
                                        DownloadStatus.RESOLVING -> "解析中…"
                                        DownloadStatus.DOWNLOADING -> if (d.total > 0) "下载中 ${(d.progress * 100).toInt()}%" else "下载中 ${d.received / 1024}KB"
                                        DownloadStatus.COMPLETED -> "已完成"
                                        DownloadStatus.FAILED -> "失败:${d.error?.take(40)}"
                                        DownloadStatus.CANCELLED -> "已取消"
                                    }
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (d.status == DownloadStatus.FAILED) MaterialTheme.colorScheme.error else Text2,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (d.status == DownloadStatus.DOWNLOADING && d.total > 0) {
                                LinearProgressIndicator(
                                    progress = { d.progress },
                                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                                    trackColor = MaterialTheme.colorScheme.surface
                                )
                            }
                        }
                        when (d.status) {
                            DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING, DownloadStatus.WAITING ->
                                IconButton(onClick = { vm.cancelDownload(d.id) }) {
                                    Icon(Icons.Filled.Cancel, "取消", tint = Text2)
                                }
                            DownloadStatus.FAILED, DownloadStatus.CANCELLED ->
                                IconButton(onClick = { vm.retryDownload(d.id) }) {
                                    Icon(Icons.Filled.Refresh, "重试", tint = MaterialTheme.colorScheme.primary)
                                }
                            DownloadStatus.COMPLETED ->
                                Icon(Icons.Filled.CheckCircle, null, tint = Ok, modifier = Modifier.padding(12.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalLibrary(vm: AppViewModel) {
    if (vm.library.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.MusicNote, null, tint = Text2, modifier = Modifier.size(48.dp))
                Text("本地还没有已下载的音乐", style = MaterialTheme.typography.bodyMedium, color = Text2, modifier = Modifier.padding(top = 8.dp))
                Text("下载完成后会出现在这里(Music/LISN)", style = MaterialTheme.typography.bodySmall, color = Text3)
            }
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
        items(vm.library.size) { i ->
            val f = vm.library[i]
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { vm.playLocal(f) }
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Outlined.MusicNote, null, tint = if (f.isFlac) MaterialTheme.colorScheme.primary else Text2)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${f.artist} · ${f.size / 1024 / 1024}MB", style = MaterialTheme.typography.bodySmall, color = Text2)
                }
                Text(if (f.isFlac) "FLAC" else "MP3", style = MaterialTheme.typography.labelSmall, color = Text3)
            }
        }
    }
}
