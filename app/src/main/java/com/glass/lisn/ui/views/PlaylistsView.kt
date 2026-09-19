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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.glass.lisn.ui.AppViewModel
import com.glass.lisn.ui.components.SongRow
import com.glass.lisn.ui.theme.Text2
import com.glass.lisn.ui.views.dialogs.TextInputDialog

@Composable
fun PlaylistsView(vm: AppViewModel) {
    var openId by remember { mutableStateOf<String?>(null) }
    var createDialog by remember { mutableStateOf(false) }

    val open = openId?.let { id -> vm.playlists.find { it.id == id } }
    if (open != null) {
        PlaylistDetailView(vm, playlist = open, onBack = { openId = null })
        return
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("我的歌单", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = { createDialog = true }) {
                Icon(Icons.Filled.Add, "新建歌单", tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (vm.playlists.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.QueueMusic, null, tint = Text2, modifier = Modifier.size(48.dp))
                    Text("还没有歌单", style = MaterialTheme.typography.bodyMedium, color = Text2, modifier = Modifier.padding(top = 8.dp))
                    Text("搜索页可一键保存结果,或手动添加", style = MaterialTheme.typography.bodySmall, color = Text2)
                    androidx.compose.material3.Button(
                        onClick = { vm.view = com.glass.lisn.ui.View.SEARCH },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.padding(top = 12.dp)
                    ) { Text("去搜索音乐") }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                items(vm.playlists.size) { i ->
                    val pl = vm.playlists[i]
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { openId = pl.id }
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.QueueMusic, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(pl.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                listOfNotNull("${pl.songs.size} 首", pl.keyword?.let { "来自「$it」" }).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall, color = Text2
                            )
                        }
                        IconButton(onClick = { vm.deletePlaylist(pl.id) }) {
                            Icon(Icons.Filled.Delete, "删除", tint = Text2)
                        }
                    }
                }
            }
        }
    }

    if (createDialog) {
        TextInputDialog(
            title = "新建歌单",
            hint = "歌单名称",
            onDismiss = { createDialog = false },
            onConfirm = { name -> vm.createPlaylist(name) {}; createDialog = false }
        )
    }
}

@Composable
private fun PlaylistDetailView(vm: AppViewModel, playlist: com.glass.lisn.model.UserPlaylist, onBack: () -> Unit) {
    val playback by vm.player.playback.collectAsState()
    val playingKey = playback.queue.getOrNull(playback.queueIdx)?.key

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = 16.dp, top = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Text2) }
            Column(Modifier.weight(1f)) {
                Text(playlist.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${playlist.songs.size} 首", style = MaterialTheme.typography.bodySmall, color = Text2)
            }
            IconButton(
                onClick = {
                    if (playlist.songs.isNotEmpty()) vm.play(playlist.songs.first(), playlist.songs)
                },
                enabled = playlist.songs.isNotEmpty()
            ) {
                Icon(Icons.Filled.PlayArrow, "播放全部", tint = MaterialTheme.colorScheme.primary)
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
            if (playlist.songs.isEmpty()) {
                item {
                    Text("歌单为空", style = MaterialTheme.typography.bodySmall, color = Text2, modifier = Modifier.padding(20.dp))
                }
            }
            items(playlist.songs.size) { i ->
                val song = playlist.songs[i]
                SongRow(
                    song = song,
                    playing = song.key == playingKey,
                    onPlay = { vm.play(song, playlist.songs) },
                    onAddToPlaylist = { vm.addSongToPlaylist(playlist.id, it) },
                    onDownload = { vm.enqueueDownload(it) },
                    onRemoveFromPlaylist = { vm.removeSongFromPlaylist(playlist.id, it.key) }
                )
            }
        }
    }
}
