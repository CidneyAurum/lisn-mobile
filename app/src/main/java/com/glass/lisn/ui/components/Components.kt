package com.glass.lisn.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.glass.lisn.model.Song
import com.glass.lisn.ui.theme.Text2
import com.glass.lisn.ui.theme.Text3

/** 平台徽标:wy/kw/kg/tx/mg → 中文 */
@Composable
fun SourceBadge(platform: String, modifier: Modifier = Modifier) {
    val label = when (platform) {
        "wy" -> "网易"; "kw" -> "酷我"; "kg" -> "酷狗"; "tx" -> "Q音"; "mg" -> "咪咕"
        else -> platform
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Text2)
    }
}

@Composable
fun CoverThumb(url: String?, size: Int = 48) {
    Box(
        Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNullOrEmpty()) {
            Icon(Icons.Outlined.MusicNote, null, tint = Text3, modifier = Modifier.size(20.dp))
        } else {
            AsyncImage(
                model = url, contentDescription = null,
                modifier = Modifier.size(size.dp),
                contentScale = ContentScale.Crop
            )
        }
    }
}

/** 歌曲行:封面 + 名称/歌手 + 平台徽标 + 播放/加歌单/下载菜单 */
@Composable
fun SongRow(
    song: Song,
    playing: Boolean,
    onPlay: () -> Unit,
    onAddToPlaylist: (Song) -> Unit,
    onDownload: (Song) -> Unit,
    onRemoveFromPlaylist: ((Song) -> Unit)? = null
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CoverThumb(song.picUrl)
        Column(Modifier.weight(1f)) {
            Text(
                song.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (playing) FontWeight.Bold else FontWeight.Normal,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    listOf(song.artist, song.album).filterNotNull().filter { it.isNotEmpty() }
                        .joinToString(" · ").ifEmpty { "未知歌手" },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                song.origins.firstOrNull()?.let { SourceBadge(it.platform) }
            }
        }
        IconButton(onClick = { menuOpen = true }) {
            Icon(Icons.Filled.MoreVert, "更多", tint = Text2)
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("立即播放") },
                leadingIcon = { Icon(Icons.Filled.PlayArrow, null) },
                onClick = { menuOpen = false; onPlay() }
            )
            DropdownMenuItem(
                text = { Text("加入歌单") },
                leadingIcon = { Icon(Icons.Filled.PlaylistAdd, null) },
                onClick = { menuOpen = false; onAddToPlaylist(song) }
            )
            DropdownMenuItem(
                text = { Text("下载当前音质") },
                leadingIcon = { Icon(Icons.Filled.Download, null) },
                onClick = { menuOpen = false; onDownload(song) }
            )
            if (onRemoveFromPlaylist != null) {
                DropdownMenuItem(
                    text = { Text("从歌单移除", color = MaterialTheme.colorScheme.error) },
                    onClick = { menuOpen = false; onRemoveFromPlaylist(song) }
                )
            }
        }
    }
}
