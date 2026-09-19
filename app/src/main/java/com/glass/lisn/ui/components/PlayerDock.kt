package com.glass.lisn.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.glass.lisn.ui.AppViewModel
import com.glass.lisn.ui.theme.CardGlass
import com.glass.lisn.ui.theme.Text2

/** 底部迷你播放条:封面 + 曲名 + 播放/暂停 + 下一曲,点击展开全屏播放页 */
@Composable
fun PlayerDock(vm: AppViewModel) {
    val playback by vm.player.playback.collectAsState()
    val isPlaying by vm.player.isPlaying.collectAsState()
    val buffering by vm.player.isBuffering.collectAsState()
    val localTitle by vm.player.currentLocalTitle.collectAsState()

    val song = playback.queue.getOrNull(playback.queueIdx)
    if (song == null && localTitle == null) return

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardGlass)
            .clickable { vm.nowPlayingOpen = true }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (!song?.picUrl.isNullOrEmpty()) {
                    AsyncImage(song.picUrl, null, Modifier.size(40.dp), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                } else {
                    Icon(Icons.Outlined.MusicNote, null, tint = Text2, modifier = Modifier.size(18.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                val title = song?.name ?: (localTitle ?: "")
                val sub = if (song != null) song.artist else "本地音乐"
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Text2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (playback.error != null) {
                Icon(Icons.Filled.Error, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = {
                // 进程重启后播放器为空:有队列记忆时点播放 = 重播当前曲
                if (!isPlaying && !buffering && song != null &&
                    (vm.player.durationMs.value <= 0L) && playback.queue.isNotEmpty()
                ) {
                    vm.player.play(song, playback.queue, playback.queueIdx)
                } else {
                    vm.player.toggle()
                }
            }, enabled = !buffering) {
                if (buffering) {
                    androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (isPlaying) {
                    Icon(Icons.Filled.Pause, "暂停")
                } else {
                    Icon(Icons.Filled.PlayArrow, "播放")
                }
            }
            IconButton(onClick = { vm.player.next() }) {
                Icon(Icons.Filled.SkipNext, "下一曲", tint = Text2)
            }
        }
    }
}

/** 轻提示 */
@Composable
fun ToastHost(vm: AppViewModel) {
    val msg = vm.toast
    AnimatedVisibility(visible = msg != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
        if (msg != null) {
            Box(Modifier.fillMaxSize().padding(bottom = 140.dp), contentAlignment = Alignment.BottomCenter) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xCC1D2030))
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }
    }
}
