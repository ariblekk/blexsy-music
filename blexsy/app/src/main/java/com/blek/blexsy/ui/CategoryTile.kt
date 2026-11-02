package com.blek.blexsy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.blek.blexsy.BuildConfig
import com.blek.blexsy.data.BlexsyApiClient
import com.blek.blexsy.data.LibraryRepository
import com.blek.blexsy.model.Genre
import com.blek.blexsy.model.Playlist
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.toHighResArtwork
import com.blek.blexsy.player.BlexsyAudioPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.roundToInt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote

private val CATEGORY_GRADIENTS = listOf(
    listOf(Color(0xFFE13300), Color(0xFF8B1A00)),
    listOf(Color(0xFF1E3264), Color(0xFF0A1A3D)),
    listOf(Color(0xFFE8115B), Color(0xFF8A0A36)),
    listOf(Color(0xFF148A08), Color(0xFF0A4B04)),
    listOf(Color(0xFFBC5900), Color(0xFF6B3200)),
    listOf(Color(0xFF503750), Color(0xFF2B1D2B)),
    listOf(Color(0xFF27856A), Color(0xFF164A3B)),
    listOf(Color(0xFF7358FF), Color(0xFF3F2D99)),
    listOf(Color(0xFFE1118C), Color(0xFF800E52)),
    listOf(Color(0xFF0D73EC), Color(0xFF08408A))
)

@Composable
internal fun CategoryTile(
    name: String,
    index: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val colors = CATEGORY_GRADIENTS[index % CATEGORY_GRADIENTS.size]
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.linearGradient(colors))
            .clickable(onClick = onClick)
            .padding(14.dp)
    ) {
        Text(
            text = name,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.72f)
        )
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = Color.Black.copy(alpha = 0.28f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(52.dp)
                .rotate(25f)
        )
    }
}
