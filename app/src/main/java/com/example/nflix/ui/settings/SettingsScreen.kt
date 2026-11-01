package com.example.nflix.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import coil.imageLoader
import com.example.nflix.NflixApp
import com.example.nflix.R
import com.example.nflix.data.AppSettings
import com.example.nflix.data.BackupStore
import com.example.nflix.data.ErrorReport
import com.example.nflix.data.ReportStore
import com.example.nflix.data.SettingsStore
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixRed
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet
import com.example.nflix.ui.theme.NflixVioletSoft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private val QUALITY_OPTIONS = listOf(
    "CAO_NHAT" to "Cao nhất",
    "P1080" to "1080p",
    "P720" to "720p",
    "P480" to "480p",
    "AUTO" to "Tự động"
)
private val VERSION_OPTIONS = listOf(
    "TM" to "Thuyết minh",
    "VS" to "Vietsub",
    "LT" to "Lồng tiếng"
)
private val CHECK_OPTIONS = listOf(
    "CONTINUOUS" to "Liên tục",
    "HOURLY" to "Mỗi giờ",
    "SIX_HOURS" to "Mỗi 6 giờ",
    "ON_OPEN" to "Chỉ khi mở app"
)
private val READER_MODE_OPTIONS = listOf(
    "SCROLL" to "Cuộn dọc",
    "PAGE" to "Lật trang"
)

val SHELF_DEFS = listOf(
    "continue" to "Xem tiếp",
    "today" to "Hôm nay có gì",
    "suggested" to "Gợi ý cho bạn",
    "foryou" to "Dành cho bạn",
    "latest" to "Mới nhất"
)

private fun labelOf(options: List<Pair<String, String>>, key: String): String =
    options.firstOrNull { it.first == key }?.second ?: key

/** Ask for the notification permission right where the user turns it on. */
private fun requestNotificationPermission(context: android.content.Context) {
    if (android.os.Build.VERSION.SDK_INT < 33) return
    val granted = androidx.core.content.ContextCompat.checkSelfPermission(
        context, android.Manifest.permission.POST_NOTIFICATIONS
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!granted) {
        (context as? android.app.Activity)?.requestPermissions(
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7002
        )
    }
}

private fun dirSize(file: File): Long =
    if (file.isDirectory) file.listFiles()?.sumOf { dirSize(it) } ?: 0L else file.length()

private fun formatSize(bytes: Long): String = when {
    bytes <= 0 -> "0 MB"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    bytes < 1024 * 1024 * 1024 -> {
        val mb = bytes.toDouble() / (1024 * 1024)
        if (mb >= 100) "${mb.toInt()} MB" else "%.1f MB".format(mb).replace('.', ',')
    }
    else -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024)).replace('.', ',')
}

@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val settings by app.settings.flow.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    var showShelves by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    var optionSheet by remember { mutableStateOf<String?>(null) }
    var cacheBytes by remember { mutableStateOf(-1L) }
    var confirmClearCache by remember { mutableStateOf(false) }
    var backupNote by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        cacheBytes = withContext(Dispatchers.IO) { dirSize(context.cacheDir) }
    }

    // B3: export writes one JSON file via the system document picker
    // (Storage Access Framework — no storage permission needed).
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val json = pendingExport
        pendingExport = null
        if (uri != null && json != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use {
                            it.write(json.toByteArray(Charsets.UTF_8))
                        } ?: throw IllegalStateException("no stream")
                        true
                    }.getOrDefault(false)
                }
                backupNote = if (ok) "Đã xuất file sao lưu tủ phim."
                else "Không ghi được file — hãy thử lại."
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                backupNote = try {
                    val text = withContext(Dispatchers.IO) {
                        context.contentResolver.openInputStream(uri)?.use {
                            it.readBytes().toString(Charsets.UTF_8)
                        } ?: throw IllegalArgumentException("Không đọc được file đã chọn.")
                    }
                    val count = BackupStore.importJson(app, text)
                    "Đã nhập $count mục vào tủ phim."
                } catch (e: IllegalArgumentException) {
                    e.message ?: "File không hợp lệ."
                } catch (e: Exception) {
                    "Nhập dữ liệu thất bại — hãy thử lại."
                }
            }
        }
    }

    if (showShelves) {
        ShelvesScreen(
            settings = settings,
            onBack = { showShelves = false },
            modifier = modifier
        )
        return
    }

    if (showReports) {
        ReportsScreen(
            onBack = { showReports = false },
            modifier = modifier
        )
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(36.dp)
                        .background(Color(0xFF1B1B24), CircleShape)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_back),
                        contentDescription = "Quay lại",
                        tint = Color.White,
                        modifier = Modifier.size(19.dp)
                    )
                }
                Text(
                    "Cài đặt",
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
        }

        item { GroupHeader("PHÁT LẠI") }
        item {
            ValueRow(
                label = "Chất lượng mặc định",
                sub = "Luôn lấy bản nét nhất nguồn có",
                value = labelOf(QUALITY_OPTIONS, settings.qualityDefault)
            ) { optionSheet = "quality" }
        }
        item {
            ValueRow(
                label = "Bản ưu tiên khi mở bộ mới",
                sub = "Tự chọn theo thứ tự này nếu có",
                value = labelOf(VERSION_OPTIONS, settings.versionPriority)
            ) { optionSheet = "version" }
        }
        item {
            ToggleRow(
                label = "Tự chọn server sống nhanh nhất",
                sub = "Đo mạng server trước khi phát",
                checked = settings.autoFastestServer
            ) { on -> scope.launch { app.settings.set(SettingsStore.AUTO_SERVER, on) } }
        }
        item {
            ToggleRow(
                label = "Tự phát tập tiếp theo",
                sub = "",
                checked = settings.autoNextEpisode
            ) { on -> scope.launch { app.settings.set(SettingsStore.AUTO_NEXT, on) } }
        }
        item {
            ToggleRow(
                label = "Bỏ qua mở đầu / kết thúc",
                sub = "Tự nhảy qua đoạn intro, outro",
                checked = settings.skipIntroOutro
            ) { on -> scope.launch { app.settings.set(SettingsStore.SKIP_INTRO, on) } }
        }
        item {
            ToggleRow(
                label = "Hình trong hình (PiP)",
                sub = "Thu nhỏ video khi thoát ra ngoài",
                checked = settings.pipEnabled
            ) { on -> scope.launch { app.settings.set(SettingsStore.PIP, on) } }
        }

        item { GroupHeader("THÔNG BÁO") }
        item {
            ToggleRow(
                label = "Báo khi có tập / chap mới",
                sub = "Cho các bộ đang theo dõi",
                checked = settings.notifyNewEpisode
            ) { on ->
                if (on) requestNotificationPermission(context)
                scope.launch { app.settings.set(SettingsStore.NOTIFY_NEW, on) }
            }
        }
        item {
            ToggleRow(
                label = "Hiện thông báo trên màn hình khóa",
                sub = "",
                checked = settings.lockScreenNotif
            ) { on ->
                if (on) requestNotificationPermission(context)
                scope.launch { app.settings.set(SettingsStore.LOCK_NOTIF, on) }
            }
        }
        item {
            ValueRow(
                label = "Kiểm tra tập mới",
                sub = "Chạy nền liên tục — có tập mới là báo ngay",
                value = labelOf(CHECK_OPTIONS, settings.checkInterval)
            ) { optionSheet = "check" }
        }
        item {
            // V2 (0.6, sheet frame 5c): the worker's REAL last-run stamp
            // and the REAL tracked count — both were recorded all along
            // (workerStamps / DAO tracked()) but never shown anywhere.
            val stamps by app.settings.workerStamps.collectAsState(initial = emptyMap())
            val trackedCount by androidx.compose.runtime.remember {
                app.database.userStateDao().trackedCount()
            }.collectAsState(initial = 0)
            val lastMs = stamps.values.maxOrNull()
            val lastText = if (lastMs != null && lastMs > 0) {
                java.text.SimpleDateFormat("HH:mm dd/MM", java.util.Locale.US)
                    .format(java.util.Date(lastMs))
            } else {
                "Chưa có lần nào"
            }
            InfoRow(
                label = "Lần kiểm tra cuối: $lastText",
                sub = "Đang theo dõi $trackedCount tựa"
            )
        }

        item { GroupHeader("TRANG CHỦ & NGUỒN") }
        item {
            ValueRow(
                label = "Các kệ ở Trang chủ",
                sub = settings.homeShelves.mapNotNull { key ->
                    SHELF_DEFS.firstOrNull { it.first == key }?.second
                }.joinToString(" · "),
                value = "${settings.homeShelves.size} kệ"
            ) { showShelves = true }
        }
        item {
            ToggleRow(
                label = "Tự chuyển nguồn dự phòng",
                sub = "Nguồn chính lỗi thì tự qua nguồn phụ",
                checked = settings.autoFallbackSource
            ) { on -> scope.launch { app.settings.set(SettingsStore.AUTO_FALLBACK, on) } }
        }
        item {
            // V2 (0.1): the toggle is REALLY wired now (WebView embed
            // interception, AdBlock.kt) and the copy says exactly what
            // it does — the old "Đã chặn 100%" claim had no code behind
            // it and is gone.
            ToggleRow(
                label = "Chặn quảng cáo trong trang phát nhúng",
                sub = "Chặn các tên miền quảng cáo/cờ bạc đã biết khi phát qua trang nhúng. Không cam kết chặn 100%.",
                checked = settings.adBlock
            ) { on -> scope.launch { app.settings.set(SettingsStore.AD_BLOCK, on) } }
        }

        item { GroupHeader("ĐỌC TRUYỆN") }
        item {
            ValueRow(
                label = "Chế độ đọc",
                sub = "Áp dụng cho Manhwa và Truyện",
                value = labelOf(READER_MODE_OPTIONS, settings.readerMode)
            ) { optionSheet = "readerMode" }
        }
        item {
            ToggleRow(
                label = "Tự ẩn thanh công cụ khi đọc",
                sub = "Đọc cho thoáng — chạm vào màn hình để hiện lại",
                checked = settings.readerAutoHideChrome
            ) { on -> scope.launch { app.settings.set(SettingsStore.READER_AUTOHIDE, on) } }
        }
        item {
            ToggleRow(
                label = "Giữ màn hình sáng khi đọc",
                sub = "Không tắt màn hình trong lúc đọc",
                checked = settings.readerKeepScreenOn
            ) { on -> scope.launch { app.settings.set(SettingsStore.READER_KEEP_ON, on) } }
        }

        item { GroupHeader("DỮ LIỆU") }
        item {
            ValueRow(
                label = "Xuất tủ phim",
                sub = "Yêu thích, theo dõi, tiến độ — gói vào 1 file JSON",
                value = ""
            ) {
                scope.launch {
                    backupNote = "Đang tạo file…"
                    pendingExport = BackupStore.exportJson(app)
                    backupNote = ""
                    exportLauncher.launch("nflix-tu-phim.json")
                }
            }
        }
        item {
            ValueRow(
                label = "Nhập tủ phim",
                sub = "Khôi phục từ file đã xuất (ghi đè theo từng mục)",
                value = ""
            ) {
                importLauncher.launch(arrayOf("application/json", "text/plain"))
            }
        }
        if (backupNote.isNotBlank()) {
            item {
                Text(
                    backupNote,
                    color = NflixViolet,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }
        item {
            ValueRow(
                label = "Báo lỗi đã ghi",
                sub = "Lỗi bạn đã báo trong trình phát / trình đọc",
                value = ""
            ) { showReports = true }
        }
        item {
            ValueRow(
                label = "Xoá bộ nhớ đệm",
                sub = "Ảnh bìa và dữ liệu tạm",
                value = if (cacheBytes < 0) "…" else formatSize(cacheBytes)
            ) { confirmClearCache = true }
        }
        item { Spacer(Modifier.height(28.dp)) }
    }

    when (optionSheet) {
        "quality" -> OptionSheet(
            title = "Chất lượng mặc định",
            options = QUALITY_OPTIONS,
            current = settings.qualityDefault,
            onSelect = { key ->
                scope.launch { app.settings.setString(SettingsStore.QUALITY, key) }
                optionSheet = null
            },
            onDismiss = { optionSheet = null }
        )
        "version" -> OptionSheet(
            title = "Bản ưu tiên khi mở bộ mới",
            options = VERSION_OPTIONS,
            current = settings.versionPriority,
            onSelect = { key ->
                scope.launch { app.settings.setString(SettingsStore.VERSION_PRIORITY, key) }
                optionSheet = null
            },
            onDismiss = { optionSheet = null }
        )
        "check" -> OptionSheet(
            title = "Kiểm tra tập mới",
            options = CHECK_OPTIONS,
            current = settings.checkInterval,
            onSelect = { key ->
                scope.launch { app.settings.setString(SettingsStore.CHECK_INTERVAL, key) }
                optionSheet = null
            },
            onDismiss = { optionSheet = null }
        )
        "readerMode" -> OptionSheet(
            title = "Chế độ đọc",
            options = READER_MODE_OPTIONS,
            current = settings.readerMode,
            onSelect = { key ->
                scope.launch { app.settings.setString(SettingsStore.READER_MODE, key) }
                optionSheet = null
            },
            onDismiss = { optionSheet = null }
        )
    }

    if (confirmClearCache) {
        AlertDialog(
            onDismissRequest = { confirmClearCache = false },
            containerColor = NflixSurface,
            title = { Text("Xoá bộ nhớ đệm?", color = Color.White) },
            text = {
                Text(
                    "Ảnh bìa và dữ liệu tạm sẽ bị xoá. Lịch sử và danh sách của bạn vẫn giữ nguyên.",
                    color = NflixTextMuted
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearCache = false
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            context.cacheDir.deleteRecursively()
                            context.cacheDir.mkdirs()
                        }
                        context.imageLoader.memoryCache?.clear()
                        context.imageLoader.diskCache?.clear()
                        cacheBytes = withContext(Dispatchers.IO) { dirSize(context.cacheDir) }
                    }
                }) { Text("Xoá", color = NflixRed) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearCache = false }) {
                    Text("Huỷ", color = Color.White)
                }
            }
        )
    }
}

@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        color = NflixTextMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 6.dp)
    )
}

@Composable
private fun ValueRow(label: String, sub: String, value: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp)
            if (sub.isNotBlank()) {
                Text(sub, color = NflixTextMuted, fontSize = 12.sp)
            }
        }
        Text(value, color = NflixTextMuted, fontSize = 13.sp)
        Icon(
            painter = painterResource(R.drawable.ic_chev_r),
            contentDescription = null,
            tint = NflixTextMuted,
            modifier = Modifier.size(16.dp).padding(start = 4.dp)
        )
    }
}

/** V2 (0.6): a plain display-only row (label + sub, no action). */
@Composable
private fun InfoRow(label: String, sub: String) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp)
            if (sub.isNotBlank()) {
                Text(sub, color = NflixTextMuted, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun ToggleRow(label: String, sub: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, color = Color.White, fontSize = 14.sp)
            if (sub.isNotBlank()) {
                Text(sub, color = NflixTextMuted, fontSize = 12.sp)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = NflixViolet,
                uncheckedTrackColor = Color(0xFF3A3A46),
                checkedThumbColor = Color.White,
                uncheckedThumbColor = Color.White
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OptionSheet(
    title: String,
    options: List<Pair<String, String>>,
    current: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = NflixSurface) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            options.forEach { (key, label) ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (key == current) NflixVioletSoft else Color.Transparent)
                        .clickable { onSelect(key) }
                        .padding(horizontal = 12.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    if (key == current) {
                        Icon(
                            painter = painterResource(R.drawable.ic_check),
                            contentDescription = "Đang chọn",
                            tint = NflixViolet,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

/**
 * 0.4: the reports the user filed from the Player / Reader, stored locally
 * by ReportStore. View, share out as text, or clear — nothing is sent
 * anywhere automatically.
 */
@Composable
private fun ReportsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var reports by remember { mutableStateOf<List<ErrorReport>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        reports = withContext(Dispatchers.IO) { ReportStore.load(context) }
        loaded = true
    }

    Column(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Color(0xFF1B1B24), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Quay lại",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            Text(
                "Báo lỗi đã ghi",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        if (loaded && reports.isEmpty()) {
            Text(
                "Chưa có báo lỗi nào. Khi trình phát hoặc trình đọc gặp lỗi, " +
                    "bấm \"Báo lỗi\" ở màn hình lỗi để ghi lại tại đây.",
                color = NflixTextMuted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        if (reports.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Chia sẻ danh sách",
                    color = NflixViolet,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(
                                    android.content.Intent.EXTRA_TEXT,
                                    ReportStore.formatText(reports)
                                )
                            }
                            context.startActivity(
                                android.content.Intent.createChooser(send, "Chia sẻ báo lỗi")
                            )
                        }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Xoá hết",
                    color = NflixRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable {
                            scope.launch {
                                withContext(Dispatchers.IO) { ReportStore.clear(context) }
                                reports = emptyList()
                            }
                        }
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                )
            }
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(reports.size) { i ->
                val r = reports[i]
                val timeText = remember(r.timeEpochSec) {
                    java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.US)
                        .format(java.util.Date(r.timeEpochSec * 1000))
                }
                Column(
                    modifier = Modifier.fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NflixSurface)
                        .padding(horizontal = 14.dp, vertical = 11.dp)
                ) {
                    Text(
                        r.titleName.ifBlank { r.titleId },
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1
                    )
                    val meta = listOf(timeText, r.unitLabel, r.server)
                        .filter { it.isNotBlank() }.joinToString(" · ")
                    if (meta.isNotBlank()) {
                        Text(meta, color = NflixTextMuted, fontSize = 12.sp)
                    }
                    Text(r.message, color = NflixTextMuted, fontSize = 12.sp)
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
private fun ShelvesScreen(
    settings: AppSettings,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val scope = rememberCoroutineScope()
    // Local ordered model: all shelves in display order with enabled flags.
    var rows by remember(settings.homeShelves) {
        // Keys with no def (e.g. the retired "new" shelf in older saved
        // lists) drop out of the editor and out of the next saved list.
        val enabled = settings.homeShelves.filter { key -> SHELF_DEFS.any { it.first == key } }
        val ordered = enabled + SHELF_DEFS.map { it.first }.filter { it !in enabled }
        mutableStateOf(ordered.map { key -> key to (key in enabled) })
    }

    fun persist(next: List<Pair<String, Boolean>>) {
        rows = next
        scope.launch {
            app.settings.setHomeShelves(next.filter { it.second }.map { it.first })
        }
    }

    Column(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(36.dp)
                    .background(Color(0xFF1B1B24), CircleShape)
                    .clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Quay lại",
                    tint = Color.White,
                    modifier = Modifier.size(19.dp)
                )
            }
            Text(
                "Các kệ ở Trang chủ",
                color = Color.White,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 10.dp)
            )
        }
        Text(
            "Bật/tắt và đổi thứ tự các kệ. Thay đổi áp dụng ngay ở Trang chủ.",
            color = NflixTextMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
        )
        rows.forEachIndexed { index, (key, enabled) ->
            val label = SHELF_DEFS.firstOrNull { it.first == key }?.second ?: key
            Row(
                modifier = Modifier.fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Text(
                    "↑",
                    color = if (index > 0) Color.White else NflixTextMuted,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .then(
                            if (index > 0) Modifier.clickable {
                                val next = rows.toMutableList()
                                val tmp = next[index - 1]
                                next[index - 1] = next[index]
                                next[index] = tmp
                                persist(next)
                            } else Modifier
                        )
                        .padding(8.dp)
                )
                Text(
                    "↓",
                    color = if (index < rows.lastIndex) Color.White else NflixTextMuted,
                    fontSize = 18.sp,
                    modifier = Modifier
                        .clip(CircleShape)
                        .then(
                            if (index < rows.lastIndex) Modifier.clickable {
                                val next = rows.toMutableList()
                                val tmp = next[index + 1]
                                next[index + 1] = next[index]
                                next[index] = tmp
                                persist(next)
                            } else Modifier
                        )
                        .padding(8.dp)
                )
                Switch(
                    checked = enabled,
                    onCheckedChange = { on ->
                        persist(rows.mapIndexed { i, row ->
                            if (i == index) row.first to on else row
                        })
                    },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = NflixViolet,
                        uncheckedTrackColor = Color(0xFF3A3A46),
                        checkedThumbColor = Color.White,
                        uncheckedThumbColor = Color.White
                    )
                )
            }
        }
    }
}
