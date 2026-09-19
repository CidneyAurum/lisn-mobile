package com.glass.lisn

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.glass.lisn.ui.AppViewModel
import com.glass.lisn.ui.View
import com.glass.lisn.ui.components.PlayerDock
import com.glass.lisn.ui.components.ToastHost
import com.glass.lisn.ui.player.NowPlayingSheet
import com.glass.lisn.ui.theme.BgDeep
import com.glass.lisn.ui.theme.LisnTheme
import com.glass.lisn.ui.views.DiscoverView
import com.glass.lisn.ui.views.DownloadsView
import com.glass.lisn.ui.views.PlaylistsView
import com.glass.lisn.ui.views.SearchView
import com.glass.lisn.ui.views.SettingsView
import com.glass.lisn.ui.views.SourcesView

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        setContent {
            LisnTheme {
                LisnRoot()
            }
        }
    }

    private fun requestNeededPermissions() {
        val wanted = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33) {
            wanted += Manifest.permission.POST_NOTIFICATIONS
            wanted += Manifest.permission.READ_MEDIA_AUDIO
        }
        val toAsk = wanted.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toAsk.isNotEmpty()) permissionLauncher.launch(toAsk.toTypedArray())
    }
}

@Composable
fun LisnRoot() {
    val vm: AppViewModel = viewModel()
    val snackbar = remember { SnackbarHostState() }
    val nowPlayingOpen = vm.nowPlayingOpen

    // 移动端返回键:全屏播放页 > 次级视图 > 发现页(不退出应用)
    BackHandler(enabled = nowPlayingOpen) { vm.nowPlayingOpen = false }
    BackHandler(enabled = !nowPlayingOpen && vm.view != View.DISCOVER) { vm.view = View.DISCOVER }

    LaunchedEffect(Unit) {
        vm.player.connect()
    }

    Box(Modifier.fillMaxSize().background(BgDeep)) {
        // 初音未来主题背景(夜色化+径向渐隐处理)
        Image(
            painter = painterResource(com.glass.lisn.R.drawable.miku_bg),
            contentDescription = null,
            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                Column {
                    PlayerDock(vm)
                    BottomNav(vm)
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when (vm.view) {
                    View.DISCOVER -> DiscoverView(vm)
                    View.SEARCH -> SearchView(vm)
                    View.PLAYLISTS -> PlaylistsView(vm)
                    View.DOWNLOADS -> DownloadsView(vm)
                    View.SOURCES -> SourcesView(vm)
                    View.SETTINGS -> SettingsView(vm)
                    View.LIBRARY -> DownloadsView(vm)
                }
            }
        }

        // Toast(仿桌面端轻提示)
        ToastHost(vm)

        // 正在播放全屏页
        AnimatedVisibility(
            visible = nowPlayingOpen,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            NowPlayingSheet(vm)
        }
    }
}

private val NAV_ORDER = listOf(View.DISCOVER, View.SEARCH, View.PLAYLISTS, View.DOWNLOADS, View.SOURCES)

@Composable
private fun BottomNav(vm: AppViewModel) {
    NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
        for (v in NAV_ORDER) {
            val selected = vm.view == v
            NavigationBarItem(
                selected = selected,
                onClick = { vm.view = v },
                icon = {
                    Icon(
                        when (v) {
                            View.DISCOVER -> Icons.Filled.Explore
                            View.SEARCH -> Icons.Filled.Search
                            View.PLAYLISTS -> Icons.Filled.QueueMusic
                            View.LIBRARY -> Icons.Filled.LibraryMusic
                            else -> Icons.Filled.Tune
                        }, null
                    )
                },
                label = { Text(v.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    unselectedIconColor = Color(0x61FFFFFF),
                    unselectedTextColor = Color(0x61FFFFFF),
                    indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    }
}
