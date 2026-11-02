package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.model.Track
import com.blek.blexsy.model.TrackCredits

/** Song credits bottom sheet: writers, producers and lyric sources. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerCreditsSheet(
    track: Track,
    credits: TrackCredits?,
    isLoading: Boolean,
    onDismiss: () -> Unit
) {
    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded)
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = BlexsyColors.Background,
        contentColor = BlexsyColors.TextPrimary,
        shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = BlexsyColors.TextMuted) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 28.dp)
        ) {
            PlayerSheetHeader(track = track)

            Spacer(modifier = Modifier.height(14.dp))

            if (isLoading && credits == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ExpressiveLoadingIndicator(
                        modifier = Modifier.size(32.dp),
                        color = BlexsyColors.Primary
                    )
                }
            } else if (credits != null) {
                val roles = credits.roles
                if (roles.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(BlexsyColors.SurfaceContainerHigh)
                    ) {
                        roles.forEachIndexed { index, role ->
                            val shape = groupedCardShape(index, roles.size, radius = 14.dp)
                            Surface(
                                shape = shape,
                                color = Color.Transparent,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp)
                                ) {
                                    Text(
                                        text = role.people.joinToString(", ") { it.name }.ifBlank { "—" },
                                        style = BlexsyTypography.Body.copy(
                                            fontSize = 15.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = BlexsyColors.TextPrimary
                                        )
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = role.title,
                                        style = BlexsyTypography.Caption.copy(
                                            fontSize = 12.sp,
                                            color = BlexsyColors.TextSecondary
                                        )
                                    )
                                }
                            }
                            if (index < roles.size - 1) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 16.dp),
                                    color = Color.White.copy(alpha = 0.08f),
                                    thickness = 0.5.dp
                                )
                            }
                        }
                    }

                    val sources = credits.sources.takeIf { it.isNotEmpty() } ?: listOf("Spotify", "YouTube Music")
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = BlexsyColors.SurfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Text(
                                text = sources.joinToString(", "),
                                style = BlexsyTypography.Body.copy(
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = BlexsyColors.TextPrimary
                                )
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.player_credits_source),
                                style = BlexsyTypography.Caption.copy(
                                    fontSize = 12.sp,
                                    color = BlexsyColors.TextSecondary
                                )
                            )
                        }
                    }
                } else {
                    PlayerSheetMessage(text = stringResource(R.string.player_credits_not_found))
                }
            } else {
                PlayerSheetMessage(text = stringResource(R.string.player_credits_unavailable))
            }
        }
    }
}

@Composable
private fun PlayerSheetMessage(text: String) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = BlexsyColors.SurfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            text = text,
            style = BlexsyTypography.BodyMuted,
            modifier = Modifier.padding(16.dp)
        )
    }
}
