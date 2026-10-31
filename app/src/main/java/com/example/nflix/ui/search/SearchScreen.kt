package com.example.nflix.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nflix.NflixApp
import com.example.nflix.R
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.components.FamilyTag
import com.example.nflix.ui.components.FilterChips
import com.example.nflix.ui.components.PosterCard
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixOrange
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet
import kotlinx.coroutines.launch
import java.text.Normalizer

/** Highlight the query match inside a name, matching diacritic-insensitively. */
private fun highlightedName(name: String, query: String): androidx.compose.ui.text.AnnotatedString {
    val nq = normalizeText(query)
    if (nq.isBlank()) return androidx.compose.ui.text.AnnotatedString(name)
    // Build normalized name + map from normalized char index to original index.
    val sb = StringBuilder()
    val indexMap = mutableListOf<Int>()
    name.forEachIndexed { i, ch ->
        val stripped = Normalizer.normalize(ch.toString(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace('đ', 'd').replace('Đ', 'D')
            .lowercase()
        for (c in stripped) {
            sb.append(c)
            indexMap.add(i)
        }
    }
    val start = sb.indexOf(nq)
    if (start < 0) return androidx.compose.ui.text.AnnotatedString(name)
    val origStart = indexMap[start]
    val origEnd = indexMap[start + nq.length - 1] + 1
    return buildAnnotatedString {
        append(name.substring(0, origStart))
        withStyle(SpanStyle(color = NflixViolet, fontWeight = FontWeight.Bold)) {
            append(name.substring(origStart, origEnd))
        }
        append(name.substring(origEnd))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    onOpenTitle: (TitleItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val viewModel = remember { SearchViewModel(app) }
    val state by viewModel.state.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    var focused by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<TitleItem>?>(null) }
    var resultsQuery by remember { mutableStateOf("") }
    var resultsFilter by remember { mutableIntStateOf(0) }

    fun submit(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        viewModel.recordHistory(q)
        keyboard?.hide()
        scope.launch {
            results = viewModel.searchNow(q)
            resultsQuery = q
            resultsFilter = 0
        }
    }

    // Results page (poster grid + family filter chips)
    results?.let { resultList ->
        androidx.activity.compose.BackHandler { results = null }
        val filterValues = listOf(null, Family.PHIM, Family.ANIME, Family.DONGHUA, Family.MANHWA, Family.TRUYEN)
        val shown = resultList.filter {
            filterValues[resultsFilter] == null || it.family == filterValues[resultsFilter]
        }
        Column(modifier = modifier.fillMaxSize().background(NflixBackground)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(36.dp)
                        .background(Color(0xFF1B1B24), androidx.compose.foundation.shape.CircleShape)
                        .clickable { results = null },
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
                    "Kết quả: $resultsQuery",
                    color = Color.White,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 10.dp)
                )
            }
            FilterChips(
                labels = listOf("Tất cả", "Phim", "Anime", "Donghua", "Manhwa", "Truyện"),
                selectedIndex = resultsFilter,
                onSelect = { resultsFilter = it }
            )
            Spacer(Modifier.height(10.dp))
            if (shown.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Không tìm thấy kết quả", color = NflixTextMuted, fontSize = 14.sp)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(shown, key = { it.id }) { item ->
                        PosterCard(item = item, onClick = { onOpenTitle(item) })
                    }
                }
            }
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(NflixSurface)
                        .then(
                            if (focused) Modifier.border(1.5.dp, NflixViolet, RoundedCornerShape(12.dp))
                            else Modifier
                        )
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = NflixTextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    BasicTextField(
                        value = state.query,
                        onValueChange = { viewModel.setQuery(it) },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                        cursorBrush = SolidColor(NflixViolet),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { submit(state.query) }),
                        modifier = Modifier.weight(1f).onFocusChanged { focused = it.isFocused },
                        decorationBox = { inner ->
                            if (state.query.isEmpty()) {
                                Text("Tìm phim, donghua, truyện…", color = NflixTextMuted, fontSize = 14.sp)
                            }
                            inner()
                        }
                    )
                    if (state.query.isNotEmpty()) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = "Xoá",
                            tint = NflixTextMuted,
                            modifier = Modifier.size(16.dp)
                                .clickable { viewModel.setQuery("") }
                        )
                    }
                }
                Text(
                    "Huỷ",
                    color = NflixViolet,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clickable {
                            viewModel.setQuery("")
                            keyboard?.hide()
                        }
                        .padding(start = 12.dp)
                )
            }
        }

        if (state.query.isBlank()) {
            if (state.history.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Lịch sử tìm kiếm",
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "Xoá tất cả",
                            color = NflixTextMuted,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { viewModel.clearHistory() }
                        )
                    }
                    FlowRow(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.history.forEach { h ->
                            Row(
                                modifier = Modifier
                                    .background(NflixSurface, RoundedCornerShape(50))
                                    .padding(start = 12.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    h,
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    modifier = Modifier.clickable {
                                        viewModel.setQuery(h)
                                        submit(h)
                                    }
                                )
                                Icon(
                                    painter = painterResource(R.drawable.ic_close),
                                    contentDescription = "Xoá mục này",
                                    tint = NflixTextMuted,
                                    modifier = Modifier.size(18.dp)
                                        .clickable { viewModel.removeHistory(h) }
                                        .padding(3.dp)
                                )
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Đang được tìm nhiều",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)
                )
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SearchViewModel.TRENDING.forEach { (keyword, hot) ->
                        Row(
                            modifier = Modifier
                                .background(NflixSurface, RoundedCornerShape(50))
                                .clickable {
                                    viewModel.setQuery(keyword)
                                    submit(keyword)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (hot) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_flame),
                                    contentDescription = null,
                                    tint = NflixOrange,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                            }
                            Text(keyword, color = Color.White, fontSize = 13.sp)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        } else {
            if (state.searching && state.suggestions.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = NflixViolet)
                    }
                }
            }
            if (!state.searching && state.suggestions.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                        Text("Chưa có gợi ý cho từ khoá này", color = NflixTextMuted, fontSize = 13.sp)
                    }
                }
            }
            items(state.suggestions, key = { it.id }) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable {
                            viewModel.recordHistory(item.name)
                            onOpenTitle(item)
                        }
                        .padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        tint = NflixTextMuted,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        highlightedName(item.name, state.query),
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f)
                    )
                    FamilyTag(item.family.label)
                }
            }
            item { Spacer(Modifier.height(20.dp)) }
        }
    }
}
