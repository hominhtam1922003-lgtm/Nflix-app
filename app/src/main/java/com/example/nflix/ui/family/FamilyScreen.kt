package com.example.nflix.ui.family

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nflix.NflixApp
import com.example.nflix.R
import com.example.nflix.data.model.Family
import com.example.nflix.data.model.TitleItem
import com.example.nflix.ui.components.ContextChips
import com.example.nflix.ui.components.FilterChips
import com.example.nflix.ui.components.PosterShelf
import com.example.nflix.ui.components.SectionHeader
import com.example.nflix.ui.components.UpdatedRow
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixViolet

private val FAMILY_CHIP_LABELS = listOf("Tất cả", "Phim", "Donghua", "Manhwa", "Truyện")
private val FAMILY_CHIP_VALUES = listOf(null, Family.PHIM, Family.DONGHUA, Family.MANHWA, Family.TRUYEN)

@Composable
fun FamilyScreen(
    family: Family,
    onFamilyChange: (Family?) -> Unit,
    onOpenTitle: (TitleItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    val viewModel = remember(family) { FamilyViewModel(app, family) }
    val state by viewModel.state.collectAsState()
    val contextLabels = remember(family) { FamilyViewModel.contextLabels(family) }

    LazyColumn(modifier = modifier.fillMaxSize().background(NflixBackground)) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = NflixViolet)) { append("N") }
                        append("flix")
                    },
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    painter = painterResource(R.drawable.ic_search),
                    contentDescription = "Tìm kiếm",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(18.dp))
                Icon(
                    painter = painterResource(R.drawable.ic_bell_dot),
                    contentDescription = "Thông báo",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        item {
            FilterChips(
                labels = FAMILY_CHIP_LABELS,
                selectedIndex = FAMILY_CHIP_VALUES.indexOf(family),
                onSelect = { onFamilyChange(FAMILY_CHIP_VALUES[it]) }
            )
            Spacer(Modifier.height(10.dp))
            ContextChips(
                labels = contextLabels,
                selectedIndex = state.contextIndex,
                onSelect = { viewModel.selectContext(it) }
            )
        }

        if (state.loading && state.posters.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = NflixViolet)
                }
            }
        }

        // Shelf 1 — posters
        if (state.posters.isNotEmpty()) {
            item {
                val title = when (family) {
                    Family.PHIM -> "Đang thịnh hành"
                    Family.DONGHUA ->
                        if (state.contextIndex == 0) "Ra tập mới hôm nay"
                        else "Ra tập mới ${FamilyViewModel.DAY_LABELS[state.contextIndex]}"
                    Family.MANHWA ->
                        if (state.contextIndex == 0) "Đang hot"
                        else FamilyViewModel.MANHWA_GENRES[state.contextIndex].first
                    Family.TRUYEN ->
                        if (state.contextIndex == 0) "Đang hot"
                        else FamilyViewModel.TRUYEN_GENRES[state.contextIndex].first
                }
                PosterShelf(
                    title = title,
                    items = state.posters,
                    onItemClick = onOpenTitle,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // Donghua shelf 2 — trending posters
        if (family == Family.DONGHUA && state.secondPosters.isNotEmpty()) {
            item {
                PosterShelf(
                    title = "Đang thịnh hành",
                    items = state.secondPosters,
                    onItemClick = onOpenTitle
                )
            }
        }

        // Rows shelf — "Mới cập nhật" (not used by Donghua)
        if (state.rows.isNotEmpty() && family != Family.DONGHUA) {
            item { SectionHeader("Mới cập nhật") }
            items(state.rows, key = { it.id }) { item ->
                UpdatedRow(item = item, onClick = { onOpenTitle(item) })
            }
        }

        item { Spacer(Modifier.height(24.dp)) }
    }
}
