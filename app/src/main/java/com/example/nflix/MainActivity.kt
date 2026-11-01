package com.example.nflix

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nflix.data.local.UserStateEntity
import com.example.nflix.data.local.watchedSet
import com.example.nflix.data.model.ChapterItem
import com.example.nflix.data.model.EpisodeItem
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.ServerGroup
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.activity.ActivityScreen
import com.example.nflix.ui.detail.DetailScreen
import com.example.nflix.ui.family.FamilyScreen
import com.example.nflix.ui.home.HomeScreen
import com.example.nflix.ui.home.HomeViewModel
import com.example.nflix.ui.mypage.MyPageScreen
import com.example.nflix.ui.reader.ReaderScreen
import com.example.nflix.ui.search.SearchScreen
import com.example.nflix.ui.settings.SettingsScreen
import com.example.nflix.ui.top.TopScreen
import com.example.nflix.work.NewEpisodeWorker
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixText
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixTheme
import com.example.nflix.ui.theme.NflixViolet
import com.example.nflix.ui.theme.NflixVioletSoft

private data class TabSpec(val label: String, val iconRes: Int)

// 4 tabs (user orders 2026-11-01): Khám phá removed (5 tabs squeezed the
// bar until labels wrapped), then Tìm kiếm removed — the header search icon
// opens the Search screen as an overlay; its slot became "Top" (BXH).
private val TABS = listOf(
    TabSpec("Trang chủ", R.drawable.ic_home),
    TabSpec("Top", R.drawable.ic_chart),
    TabSpec("Hoạt động", R.drawable.ic_history),
    TabSpec("Của tôi", R.drawable.ic_smiley)
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7001
            )
        }
        // "Chỉ khi mở app" check mode: run one check per app start.
        lifecycleScope.launch {
            val app = application as NflixApp
            if (app.settings.flow.first().checkInterval == "ON_OPEN") {
                androidx.work.WorkManager.getInstance(this@MainActivity).enqueueUniqueWork(
                    "nflix_check_on_open",
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    androidx.work.OneTimeWorkRequestBuilder<NewEpisodeWorker>().build()
                )
            }
        }
        setContent {
            NflixTheme {
                NflixShell()
            }
        }
    }
}

@Composable
private fun NflixShell() {
    val context = LocalContext.current
    var selected by remember { mutableIntStateOf(0) }
    var familyRoute by remember { mutableStateOf<Family?>(null) }
    var detailItem by remember { mutableStateOf<TitleItem?>(null) }
    var readerRequest by remember { mutableStateOf<Pair<TitleItem, ChapterItem>?>(null) }
    var settingsOpen by remember { mutableStateOf(false) }
    var searchOpen by remember { mutableStateOf(false) }

    val openTitle: (TitleItem) -> Unit = { detailItem = it }

    val playVideo: (TitleItem, EpisodeItem, ServerGroup) -> Unit = { item, episode, _ ->
        val intent = Intent(context, PlayerActivity::class.java).apply {
            putExtra(PlayerActivity.EXTRA_TITLE_ID, item.id)
            putExtra(PlayerActivity.EXTRA_EPISODE_SLUG, episode.slug)
            putExtra(PlayerActivity.EXTRA_EPISODE_LABEL, episode.label)
            putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, episode.number)
        }
        context.startActivity(intent)
    }
    val readChapter: (TitleItem, ChapterItem) -> Unit = { item, chapter ->
        readerRequest = item to chapter
    }
    val shellScope = rememberCoroutineScope()
    val app = context.applicationContext as NflixApp

    val resumeFromState: (TitleItem, UserStateEntity) -> Unit = { item, st ->
        when (item.family) {
            Family.TRUYEN, Family.MANHWA -> readerRequest = item to ChapterItem(
                label = st.lastUnitLabel,
                number = st.lastUnitNumber,
                id = ""
            )
            else -> {
                val intent = Intent(context, PlayerActivity::class.java).apply {
                    putExtra(PlayerActivity.EXTRA_TITLE_ID, item.id)
                    putExtra(PlayerActivity.EXTRA_EPISODE_NUMBER, st.lastUnitNumber)
                    putExtra(PlayerActivity.EXTRA_EPISODE_LABEL, st.lastUnitLabel)
                }
                context.startActivity(intent)
            }
        }
    }

    // Resume straight into Player/Reader at the saved position (Xem tiếp cards).
    val resumeByItem: (TitleItem) -> Unit = { item ->
        shellScope.launch {
            val st = app.database.userStateDao().get(item.id)
            if (st != null && st.lastUnitNumber > 0f) resumeFromState(item, st)
            else openTitle(item)
        }
    }

    // Hero "Xem ngay": resume if started, otherwise play the newest episode/chapter directly.
    val playDirect: (TitleItem) -> Unit = { item ->
        shellScope.launch {
            val st = app.database.userStateDao().get(item.id)
            if (st != null && st.lastUnitNumber > 0f) {
                resumeFromState(item, st)
                return@launch
            }
            try {
                val detail = when (item.family) {
                    Family.PHIM, Family.ANIME -> app.mergedPhim.detailFor(item)
                    Family.DONGHUA -> app.hhKungfu.detail(item.slug)
                    Family.MANHWA -> app.netTruyen.detail(item.slug)
                    Family.TRUYEN -> app.truyenQq.detail(item.slug)
                }
                when (item.family) {
                    Family.TRUYEN, Family.MANHWA -> {
                        val chapter = detail.chapters.maxByOrNull { it.number }
                        if (chapter != null) readChapter(item, chapter) else openTitle(item)
                    }
                    else -> {
                        val server = detail.servers.maxByOrNull { g ->
                            g.episodes.maxOfOrNull { it.number } ?: 0f
                        } ?: detail.servers.firstOrNull()
                        val episode = server?.episodes?.maxByOrNull { it.number }
                        if (server != null && episode != null) playVideo(item, episode, server)
                        else openTitle(item)
                    }
                }
            } catch (e: Exception) {
                openTitle(item)
            }
        }
    }

    // C1 (K8): "Có tập mới" jump — open the FIRST unseen new unit directly.
    // The target is positional in the app's own model: units are consumed in
    // ascending order up to seenCount, so the first unit past that mark that
    // is not in watchedUnits is the oldest new one. Display/navigation only:
    // opening it consumes through the normal player/reader save path, and if
    // the detail cannot be loaded we fall back to the Detail screen.
    val openFirstNew: (TitleItem) -> Unit = { item ->
        shellScope.launch {
            val st = app.database.userStateDao().get(item.id)
            val watched = st?.watchedSet() ?: emptySet()
            val seen = st?.seenCount ?: 0
            try {
                val detail = when (item.family) {
                    Family.PHIM, Family.ANIME -> app.mergedPhim.detailFor(item)
                    Family.DONGHUA -> app.hhKungfu.detail(item.slug)
                    Family.MANHWA -> app.netTruyen.detail(item.slug)
                    Family.TRUYEN -> app.truyenQq.detail(item.slug)
                }
                when (item.family) {
                    Family.TRUYEN, Family.MANHWA -> {
                        val ascending = detail.chapters.sortedBy { it.number }
                        val chapter = ascending.drop(seen).firstOrNull { it.number !in watched }
                            ?: ascending.firstOrNull { it.number !in watched }
                        if (chapter != null) readChapter(item, chapter) else openTitle(item)
                    }
                    else -> {
                        var played = false
                        val servers = detail.servers.sortedByDescending { it.episodes.size }
                        for (server in servers) {
                            val ascending = server.episodes.sortedBy { it.number }
                            val episode = ascending.drop(seen).firstOrNull { it.number !in watched }
                                ?: ascending.firstOrNull { it.number !in watched }
                            if (episode != null) {
                                playVideo(item, episode, server)
                                played = true
                                break
                            }
                        }
                        if (!played) openTitle(item)
                    }
                }
            } catch (e: Exception) {
                openTitle(item)
            }
        }
    }

    // Full-screen overlays first: reader sits above detail, detail above settings/tabs.
    // System back steps down ONE layer at a time — it must never exit the app
    // from inside a title/reader/settings (user report 2026-11-01).
    when {
        readerRequest != null -> {
            BackHandler { readerRequest = null }
            val (item, chapter) = readerRequest!!
            ReaderScreen(
                item = item,
                chapter = chapter,
                onBack = { readerRequest = null },
                onOpenChapter = { readChapter(item, it) }
            )
            return
        }
        detailItem != null -> {
            BackHandler { detailItem = null }
            DetailScreen(
                item = detailItem!!,
                onBack = { detailItem = null },
                onPlay = playVideo,
                onRead = readChapter,
                onOpenTitle = openTitle
            )
            return
        }
        settingsOpen -> {
            BackHandler { settingsOpen = false }
            SettingsScreen(onBack = { settingsOpen = false })
            return
        }
        searchOpen -> {
            // Search lives UNDER detail/reader: opening a result stacks the
            // detail above it, and closing the detail returns here.
            BackHandler { searchOpen = false }
            SearchScreen(
                onOpenTitle = openTitle,
                onClose = { searchOpen = false }
            )
            return
        }
    }

    // Back inside the shell: family screen → Home, other tabs → Home tab.
    BackHandler(enabled = familyRoute != null) { familyRoute = null }
    BackHandler(enabled = familyRoute == null && selected != 0) { selected = 0 }

    Scaffold(
        containerColor = NflixBackground,
        bottomBar = {
            NavigationBar(containerColor = NflixSurface) {
                TABS.forEachIndexed { index, tab ->
                    val isSelected = index == selected
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = {
                            selected = index
                            if (index != 0) familyRoute = null
                        },
                        icon = {
                            Icon(
                                painter = painterResource(tab.iconRes),
                                contentDescription = tab.label,
                                tint = if (isSelected) NflixViolet else NflixTextMuted
                            )
                        },
                        label = {
                            Text(
                                text = tab.label,
                                color = if (isSelected) NflixViolet else NflixTextMuted,
                                maxLines = 1
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            indicatorColor = NflixVioletSoft
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(NflixBackground)
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            when {
                selected == 0 && familyRoute != null -> FamilyScreen(
                    family = familyRoute!!,
                    onFamilyChange = { familyRoute = it },
                    onOpenTitle = openTitle,
                    onGoSearch = { searchOpen = true },
                    onOpenFirstNew = openFirstNew
                )
                selected == 0 -> {
                    val homeVm: HomeViewModel = viewModel()
                    HomeScreen(
                        viewModel = homeVm,
                        onOpenTitle = openTitle,
                        onFamilyChip = { familyRoute = it },
                        onPlayDirect = playDirect,
                        onResume = resumeByItem,
                        onGoSearch = { searchOpen = true },
                        onOpenFirstNew = openFirstNew
                    )
                }
                selected == 1 -> TopScreen(onOpenTitle = openTitle)
                selected == 2 -> ActivityScreen(
                    onOpenTitle = openTitle,
                    onResume = resumeFromState,
                    onOpenSearch = { searchOpen = true }
                )
                else -> MyPageScreen(
                    onOpenTitle = openTitle,
                    onOpenSettings = { settingsOpen = true },
                    onOpenFirstNew = openFirstNew
                )
            }
        }
    }
}
