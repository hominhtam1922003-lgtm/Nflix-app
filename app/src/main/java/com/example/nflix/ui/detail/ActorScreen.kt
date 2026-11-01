package com.example.nflix.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.nflix.NflixApp
import com.example.nflix.R
import com.example.nflix.data.model.ActorRef
import com.example.nflix.data.model.TitleItem
import com.example.nflix.data.source.CobeActorPage
import com.example.nflix.ui.components.PosterCard
import com.example.nflix.ui.theme.NflixBackground
import com.example.nflix.ui.theme.NflixSurface
import com.example.nflix.ui.theme.NflixTextMuted
import com.example.nflix.ui.theme.NflixViolet

/**
 * V2 (A5): an actor's page — photo (the TMDB thumb the Cobe record
 * carries, when it has one), name, and the 3-column grid of their
 * titles as published on the Cobe web actor page (/dien-vien/<slug>).
 * Only actors whose record carries a Cobe slug can open this screen;
 * everyone else stays plain text in Detail (no dead links).
 */
@Composable
fun ActorScreen(
    actor: ActorRef,
    onBack: () -> Unit,
    onOpenTitle: (TitleItem) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as NflixApp
    var page by remember(actor.slug) { mutableStateOf<CobeActorPage?>(null) }
    var failed by remember(actor.slug) { mutableStateOf(false) }
    var attempt by remember(actor.slug) { mutableStateOf(0) }

    LaunchedEffect(actor.slug, attempt) {
        failed = false
        try {
            page = app.cobe.actorPage(actor.slug)
        } catch (e: Exception) {
            failed = true
        }
    }

    val displayName = page?.name?.takeIf { it.isNotBlank() } ?: actor.name
    val displayThumb = page?.thumb?.takeIf { it.isNotBlank() } ?: actor.thumb
    val items = page?.items ?: emptyList()

    Column(
        modifier = modifier.fillMaxSize().background(NflixBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth()
                .padding(start = 4.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(40.dp).clickable(onClick = onBack),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = "Quay lại",
                    tint = Color.White,
                    modifier = Modifier.size(21.dp)
                )
            }
            Text(
                "Diễn viên",
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (displayThumb.isNotBlank()) {
                AsyncImage(
                    model = displayThumb,
                    contentDescription = displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(NflixSurface)
                )
            } else {
                Box(
                    modifier = Modifier.size(64.dp).clip(CircleShape)
                        .background(NflixSurface),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        displayName.trim().firstOrNull()?.uppercase() ?: "?",
                        color = NflixViolet,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    displayName,
                    color = Color.White,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold
                )
                if (page != null) {
                    Text(
                        "${items.size} tựa trên Cobe",
                        color = NflixTextMuted,
                        fontSize = 13.sp
                    )
                }
            }
        }

        when {
            failed -> {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Không tải được trang diễn viên",
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Kiểm tra kết nối mạng rồi thử lại.",
                        color = NflixTextMuted,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        onClick = { attempt++ },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NflixViolet,
                            contentColor = Color.White
                        )
                    ) { Text("Thử lại") }
                }
            }
            page == null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = NflixViolet)
                }
            }
            items.isEmpty() -> {
                Text(
                    "Chưa có dữ liệu phim của diễn viên này trên Cobe.",
                    color = NflixTextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
            else -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 6.dp, bottom = 28.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(items, key = { it.id }) { title ->
                        PosterCard(
                            item = title,
                            onClick = { onOpenTitle(title) },
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.72f)
                        )
                    }
                }
            }
        }
    }
}
