package com.glass.lisn.ui.views.dialogs

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.glass.lisn.model.UserPlaylist
import com.glass.lisn.ui.theme.Text2

/** 通用文本输入对话框 */
@Composable
fun TextInputDialog(
    title: String,
    hint: String,
    initial: String = "",
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(hint, color = Text2) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (text.isNotBlank()) onConfirm(text.trim()) }, enabled = text.isNotBlank()) {
                Text("确定", color = MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = Text2) }
        }
    )
}

/** 选择歌单(可新建) */
@Composable
fun AddToPlaylistDialog(
    playlists: List<UserPlaylist>,
    songName: String,
    onDismiss: () -> Unit,
    onCreateNew: (String) -> Unit,
    onPick: (UserPlaylist) -> Unit
) {
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog(
            title = "新建歌单并加入",
            hint = "歌单名称",
            onDismiss = { creating = false },
            onConfirm = { name -> creating = false; onCreateNew(name) }
        )
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("把「${songName.take(16)}」加入歌单", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { creating = true }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Add, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text("新建歌单", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                }
                Spacer(Modifier.height(8.dp))
                if (playlists.isEmpty()) {
                    Text("还没有歌单", style = MaterialTheme.typography.bodySmall, color = Text2, modifier = Modifier.padding(8.dp))
                } else {
                    LazyColumn(Modifier.height(240.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(playlists.size) { i ->
                            val pl = playlists[i]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .clickable { onPick(pl) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Filled.QueueMusic, null, tint = Text2)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(pl.name, style = MaterialTheme.typography.bodyMedium)
                                    Text("${pl.songs.size} 首", style = MaterialTheme.typography.bodySmall, color = Text2)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = Text2) }
        }
    )
}
