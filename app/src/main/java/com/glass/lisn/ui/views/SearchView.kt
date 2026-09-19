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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.glass.lisn.model.Song
import com.glass.lisn.ui.AppViewModel
import com.glass.lisn.ui.components.SongRow
import com.glass.lisn.ui.theme.Text2
import com.glass.lisn.ui.views.dialogs.AddToPlaylistDialog
import com.glass.lisn.ui.views.dialogs.TextInputDialog

@Composable
fun SearchView(vm: AppViewModel) {
    var kw by remember { mutableStateOf(vm.keyword) }
    var addSong by remember { mutableStateOf<Song?>(null) }
    var saveDialog by remember { mutableStateOf(false) }
    var srcFilter by remember { mutableStateOf("all") }
    val platforms = remember(vm.results) {
        vm.results.flatMap { r -> r.origins.map { o -> o.platform } }.distinct()
    }
    val filtered = if (srcFilter == "all") vm.results
    else vm.results.filter { r -> r.origins.any { o -> o.platform == srcFilter } }
    val playback by vm.player.playback.collectAsState()
    val playingKey = playback.queue.getOrNull(playback.queueIdx)?.key

    Column(Modifier.fillMaxSize()) {
        // 搜索头部
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LaunchedEffect(vm.searched, vm.keyword) { srcFilter = "all" }
            IconButton(onClick = { vm.view = com.glass.lisn.ui.View.DISCOVER }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Text2)
            }
            OutlinedTextField(
                value = kw,
                onValueChange = { kw = it },
                placeholder = { Text("搜索歌曲 / 歌手", color = Text2) },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { vm.doSearch(kw) },
                enabled = kw.isNotBlank() && !vm.searching,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
            ) {
                if (vm.searching) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
            }
        }

        if (vm.searched && !vm.searching) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "「${vm.keyword}」共 ${vm.results.size} 首" + if (srcFilter == "all") "" else " · 筛选后 ${filtered.size} 首",
                    style = MaterialTheme.typography.bodySmall,
                    color = Text2,
                    modifier = Modifier.weight(1f)
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { if (filtered.isNotEmpty()) vm.play(filtered.first(), filtered) }
                        .padding(6.dp)
                ) {
                    Icon(Icons.Filled.PlayCircle, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("播放全部", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.width(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable { saveDialog = true }
                        .padding(6.dp)
                ) {
                    Icon(Icons.Filled.Save, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("保存为歌单", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 平台筛选 chips
        if (platforms.size > 1 && !vm.searching) {
            LazyRow(
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val PLAT_LABEL = mapOf("all" to "全部", "wy" to "网易", "kw" to "酷我", "kg" to "酷狗", "tx" to "Q音", "mg" to "咪咕")
                val tabs = listOf("all") + platforms
                items(tabs.size) { i ->
                    val pf = tabs[i]
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (srcFilter == pf) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { srcFilter = pf }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) {
                        Text(
                            PLAT_LABEL[pf] ?: pf,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (srcFilter == pf) MaterialTheme.colorScheme.onPrimary else Text2
                        )
                    }
                }
            }
        }

        // 结果列表
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (vm.searching && vm.results.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            if (!vm.searching && vm.searchError != null) {
                item {
                    Column(Modifier.fillMaxWidth().padding(20.dp)) {
                        Text(
                            "搜索失败:${vm.searchError}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.size(8.dp))
                        Button(
                            onClick = { vm.doSearch(vm.keyword) },
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Text("重试")
                        }
                    }
                }
            }
            if (!vm.searching && vm.searched && vm.results.isEmpty() && vm.searchError == null) {
                item {
                    Text(
                        "没有找到相关歌曲",
                        style = MaterialTheme.typography.bodyMedium, color = Text2,
                        modifier = Modifier.padding(20.dp)
                    )
                }
            }
            items(filtered.size) { i ->
                val song = filtered[i]
                SongRow(
                    song = song,
                    playing = song.key == playingKey,
                    onPlay = { vm.play(song, vm.results) },
                    onAddToPlaylist = { addSong = it },
                    onDownload = { vm.enqueueDownload(it) }
                )
            }
            if (vm.hasMore) {
                item {
                    Box(Modifier.fillMaxWidth().padding(14.dp), contentAlignment = Alignment.Center) {
                        if (vm.loadingMore) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Text(
                                "加载更多",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clickable { vm.loadMore() }
                                    .padding(horizontal = 20.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // 加歌单对话框
    addSong?.let { song ->
        AddToPlaylistDialog(
            playlists = vm.playlists,
            songName = song.name,
            onDismiss = { addSong = null },
            onCreateNew = { name ->
                val pl = vm.createPlaylistGet(name)
                vm.addSongToPlaylist(pl.id, song)
                addSong = null
            },
            onPick = { pl ->
                vm.addSongToPlaylist(pl.id, song)
                addSong = null
            }
        )
    }

    if (saveDialog) {
        TextInputDialog(
            title = "保存搜索结果为歌单",
            hint = "歌单名称",
            initial = "「${vm.keyword}」",
            onDismiss = { saveDialog = false },
            onConfirm = { name ->
                vm.saveSearchToPlaylist(name)
                saveDialog = false
            }
        )
    }
}
