package com.glass.lisn.ui.views

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.glass.lisn.engine.dailyKeywords
import com.glass.lisn.model.Song
import com.glass.lisn.ui.AppViewModel
import com.glass.lisn.ui.View
import com.glass.lisn.ui.components.SongRow
import com.glass.lisn.ui.theme.Aurora1
import com.glass.lisn.ui.theme.Aurora2
import com.glass.lisn.ui.theme.Aurora3
import com.glass.lisn.ui.theme.Text2
import kotlinx.coroutines.launch

private val HOT_CHIPS = listOf("晴天", "海阔天空", "突然好想你", "漠河舞厅", "孤勇者", "起风了")
private val GRADS = listOf(
    listOf(Aurora1, Aurora3), listOf(Aurora2, Aurora3), listOf(Color(0xFF32D074), Aurora1),
    listOf(Color(0xFFFFB340), Aurora2), listOf(Color(0xFF39C5BB), Aurora1), listOf(Color(0xFF7D7BFF), Aurora2)
)

private fun greeting(): String {
    val h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
    return when {
        h < 5 -> "夜深了"; h < 11 -> "早上好"; h < 14 -> "中午好"; h < 18 -> "下午好"; else -> "晚上好"
    }
}

@Composable
fun DiscoverView(vm: AppViewModel) {
    val playlists = vm.playlists
    var kw by remember { mutableStateOf("") }
    var dailyLoading by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val daily = remember { dailyKeywords() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 20.dp)) {
        // 顶部问候 + 设置入口
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(greeting(), style = MaterialTheme.typography.titleLarge)
                    Text("你的音乐,由此展开", style = MaterialTheme.typography.bodySmall, color = Text2)
                }
                IconButton(onClick = { vm.view = View.SETTINGS }) {
                    Icon(Icons.Filled.Settings, "设置", tint = Text2)
                }
            }
        }
        // 搜索框
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                    onClick = { vm.view = View.SEARCH; vm.doSearch(kw) },
                    enabled = kw.isNotBlank(),
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Icon(Icons.Filled.Search, null, Modifier.size(18.dp))
                }
            }
        }
        // 热搜 chips
        item {
            LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(HOT_CHIPS.size) { i ->
                    val chip = HOT_CHIPS[i]
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { vm.view = View.SEARCH; vm.doSearch(chip) }
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(chip, style = MaterialTheme.typography.bodyMedium, color = Text2)
                    }
                }
            }
        }
        // 每日推荐
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.CalendarMonth, null, tint = Aurora3, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("每日推荐", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (dailyLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for ((i, k) in daily.withIndex()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .height(84.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(Brush.linearGradient(GRADS[i % GRADS.size]))
                            .clickable {
                                dailyLoading = true
                                scope.launch {
                                    runCatching { vm.doSearch(k) }
                                    vm.view = View.SEARCH
                                    dailyLoading = false
                                }
                            }
                            .padding(12.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        Column {
                            Icon(Icons.Filled.Shuffle, null, tint = Color.White.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
                            Text(k, style = MaterialTheme.typography.titleMedium, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
        // 下载入口
        item {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 18.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { vm.view = View.DOWNLOADS }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Download, null, tint = Aurora3)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("下载与本地音乐", style = MaterialTheme.typography.bodyMedium)
                    Text("管理下载队列和已保存的歌曲", style = MaterialTheme.typography.bodySmall, color = Text2)
                }
            }
        }
        // 歌单快捷
        item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("我的歌单", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    "查看全部",
                    style = MaterialTheme.typography.bodySmall,
                    color = Aurora3,
                    modifier = Modifier.clickable { vm.view = View.PLAYLISTS }
                )
            }
        }
        if (playlists.isEmpty()) {
            item {
                Text(
                    "还没有歌单,去搜索页保存一批吧",
                    style = MaterialTheme.typography.bodySmall, color = Text2,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }
        } else {
            items(playlists.take(3).size) { i ->
                val pl = playlists[i]
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { vm.view = View.PLAYLISTS }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🎵", modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(pl.name, style = MaterialTheme.typography.bodyMedium)
                        Text("${pl.songs.size} 首", style = MaterialTheme.typography.bodySmall, color = Text2)
                    }
                }
            }
        }
    }
}
