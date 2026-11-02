package com.blek.blexsy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import com.blek.blexsy.eq.*
import kotlin.math.*

private const val GraphRangeDb = 12.0
private const val MinLog = 1.30103 // log10(20)
private const val MaxLog = 4.30103 // log10(20000)
private val GraphFrequencies = ResponseCurve.logSpaced(180)

private val NodeColors = listOf(
    Color(0xFF6B9BF2),
    Color(0xFF68D391),
    Color(0xFFF6AD55),
    Color(0xFFFC8181),
    Color(0xFFB794F4),
    Color(0xFF4FD1C5),
    Color(0xFFF6E05E),
    Color(0xFFED64A6),
    Color(0xFF63B3ED),
    Color(0xFF9AE6B4)
)

private val GridFrequencies = listOf(20.0, 50.0, 100.0, 200.0, 500.0, 1000.0, 2000.0, 5000.0, 10_000.0, 20_000.0)
private val GridLabels = listOf("20", "50", "100", "200", "500", "1k", "2k", "5k", "10k", "20k")

internal class EqPlot(val left: Float, val top: Float, val right: Float, val bottom: Float, val rangeDb: Double = GraphRangeDb) {
    fun x(f: Double): Float = left + ((log10(f.coerceIn(20.0, 20_000.0)) - MinLog) / (MaxLog - MinLog)).toFloat().coerceIn(0f, 1f) * (right - left)
    fun frequencyAt(x: Float): Double = 10.0.pow(MinLog + ((x - left) / (right - left)).coerceIn(0f, 1f) * (MaxLog - MinLog))
    fun y(db: Double): Float = top + ((rangeDb - db) / (2 * rangeDb)).toFloat().coerceIn(0f, 1f) * (bottom - top)
    fun gainAt(y: Float): Double = rangeDb - ((y - top) / (bottom - top)).coerceIn(0f, 1f) * 2 * rangeDb
}

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val eqState by BlexsyEqualizer.state.collectAsState()

    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.eq_tab_graph), stringResource(R.string.eq_tab_bands))

    var selectedNode by rememberSaveable { mutableIntStateOf(0) }

    val offText = stringResource(R.string.eq_off)
    val customText = stringResource(R.string.eq_preset_custom)
    val subtitle = if (!eqState.enabled) offText else {
        val presetName = BlexsyEqualizer.Presets.firstOrNull { it.id == eqState.presetId }?.name ?: customText
        stringResource(R.string.settings_equalizer_tuned, presetName)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BlexsyColors.BackgroundDeep)
            .statusBarsPadding()
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                    tint = BlexsyColors.TextPrimary
                )
            }
            Column(modifier = Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    text = stringResource(R.string.player_equalizer),
                    style = BlexsyTypography.SectionTitle.copy(fontSize = 19.sp)
                )
                Text(
                    text = subtitle,
                    style = BlexsyTypography.Caption.copy(
                        color = if (eqState.enabled) BlexsyColors.Primary else BlexsyColors.TextMuted,
                        fontSize = 12.sp
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Switch(
                checked = eqState.enabled,
                onCheckedChange = { BlexsyEqualizer.setEnabled(it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = BlexsyColors.Primary,
                    uncheckedThumbColor = BlexsyColors.TextMuted,
                    uncheckedTrackColor = BlexsyColors.SurfaceContainerHigh
                )
            )
        }

        // 3 Segmented Tabs: Graph, Bands, AutoEQ
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BlexsyTabs(
                tabs = tabs,
                selectedIndex = selectedTab,
                onSelect = { selectedTab = it },
                fillMaxWidth = false
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Presets row on Graph & Bands
            if (selectedTab == 0 || selectedTab == 1) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.eq_presets_header),
                            style = BlexsyTypography.Caption.copy(
                                color = BlexsyColors.TextMuted,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )
                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { BlexsyEqualizer.reset() }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = stringResource(R.string.eq_reset),
                                tint = BlexsyColors.Primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.eq_reset),
                                style = BlexsyTypography.Caption.copy(
                                    color = BlexsyColors.Primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    val scrollState = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(scrollState),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BlexsyEqualizer.Presets.forEach { preset ->
                            val isSelected = eqState.presetId == preset.id
                            Box(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(
                                        if (isSelected) BlexsyColors.Primary else BlexsyColors.SurfaceContainerHigh
                                    )
                                    .clickable { BlexsyEqualizer.applyPreset(preset.id) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = preset.name,
                                    style = BlexsyTypography.Caption.copy(
                                        color = if (isSelected) Color.Black else BlexsyColors.TextPrimary,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                            }
                        }
                    }
                }
            }

            // Tab 0: GRAPH EDITOR
            if (selectedTab == 0) {
                item {
                    val node = eqState.bands.getOrNull(selectedNode.coerceIn(0, eqState.bands.size - 1))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.eq_freq_response_header),
                            style = BlexsyTypography.Caption.copy(
                                color = BlexsyColors.TextMuted,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp
                            )
                        )
                        if (node != null) {
                            val color = NodeColors.getOrElse(selectedNode % NodeColors.size) { BlexsyColors.Primary }
                            Text(
                                text = "${formatFreq(node.frequency)} • ${if (node.gain > 0) "+" else ""}${String.format("%.1f dB", node.gain)}",
                                style = BlexsyTypography.Caption.copy(
                                    color = color,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    EqGraphCanvas(
                        bands = eqState.bands,
                        selected = selectedNode,
                        enabled = eqState.enabled,
                        onSelect = { selectedNode = it },
                        onMove = { idx, f, g ->
                            BlexsyEqualizer.updateNode(idx) { it.copy(frequency = f, gain = g) }
                        }
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Node selector chips
                    val nodeScroll = rememberScrollState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(nodeScroll),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        eqState.bands.forEachIndexed { idx, b ->
                            val isSel = idx == selectedNode
                            val dotColor = NodeColors.getOrElse(idx % NodeColors.size) { BlexsyColors.Primary }
                            Row(
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(if (isSel) BlexsyColors.SurfaceContainerHighest else BlexsyColors.SurfaceContainer)
                                    .border(1.dp, if (isSel) dotColor else Color.Transparent, CircleShape)
                                    .clickable { selectedNode = idx }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = formatFreq(b.frequency),
                                    style = BlexsyTypography.Caption.copy(
                                        color = if (isSel) Color.White else BlexsyColors.TextSecondary,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium
                                    )
                                )
                            }
                        }
                    }
                }

                // Node Details
                item {
                    val safeIdx = selectedNode.coerceIn(0, (eqState.bands.size - 1).coerceAtLeast(0))
                    val node = eqState.bands.getOrNull(safeIdx)
                    if (node != null) {
                        val color = NodeColors.getOrElse(safeIdx % NodeColors.size) { BlexsyColors.Primary }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .background(BlexsyColors.SurfaceContainer)
                                .border(1.dp, BlexsyColors.Border, RoundedCornerShape(16.dp))
                                .padding(16.dp)
                        ) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${formatFreq(node.frequency)} Node",
                                            style = BlexsyTypography.Body.copy(fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                        )
                                        Text(
                                            text = "${node.filterType} • Q ${String.format("%.2f", node.q)}",
                                            style = BlexsyTypography.Caption.copy(color = BlexsyColors.TextMuted)
                                        )
                                    }
                                    Switch(
                                        checked = node.enabled,
                                        onCheckedChange = { on ->
                                            BlexsyEqualizer.updateNode(safeIdx) { it.copy(enabled = on) }
                                        },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = Color.White,
                                            checkedTrackColor = color
                                        )
                                    )
                                }

                                Spacer(modifier = Modifier.height(14.dp))

                                // Filter Type Row
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(
                                        FilterType.LSC to stringResource(R.string.eq_filter_low_shelf),
                                        FilterType.PK to stringResource(R.string.eq_filter_peak),
                                        FilterType.HSC to stringResource(R.string.eq_filter_high_shelf)
                                    ).forEach { (ft, label) ->
                                        val picked = node.filterType == ft
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (picked) BlexsyColors.Primary else BlexsyColors.SurfaceContainerHigh)
                                                .clickable {
                                                    BlexsyEqualizer.updateNode(safeIdx) {
                                                        it.copy(filterType = ft, q = if (ft == FilterType.PK) it.q else 0.7)
                                                    }
                                                }
                                                .padding(vertical = 10.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = label,
                                                style = BlexsyTypography.Caption.copy(
                                                    color = if (picked) Color.Black else BlexsyColors.TextPrimary,
                                                    fontWeight = if (picked) FontWeight.Bold else FontWeight.Medium
                                                )
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                // Gain Slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.eq_gain), style = BlexsyTypography.Caption.copy(color = BlexsyColors.TextSecondary))
                                    Text(
                                        (if (node.gain > 0) "+" else "") + String.format("%.1f dB", node.gain),
                                        style = BlexsyTypography.Caption.copy(color = color, fontWeight = FontWeight.Bold)
                                    )
                                }
                                Slider(
                                    value = node.gain.toFloat(),
                                    onValueChange = { v ->
                                        val snapped = ((v * 10).roundToInt() / 10.0).let { if (abs(it) < 0.25) 0.0 else it }
                                        BlexsyEqualizer.updateNode(safeIdx) { it.copy(gain = snapped) }
                                    },
                                    valueRange = -12f..12f,
                                    enabled = eqState.enabled && node.enabled,
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = color)
                                )

                                Spacer(modifier = Modifier.height(6.dp))

                                // Q Factor Slider
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(stringResource(R.string.eq_q_factor), style = BlexsyTypography.Caption.copy(color = BlexsyColors.TextSecondary))
                                    Text(String.format("%.2f", node.q), style = BlexsyTypography.Caption.copy(color = BlexsyColors.TextPrimary))
                                }
                                Slider(
                                    value = node.q.toFloat(),
                                    onValueChange = { v ->
                                        val roundedQ = (v * 100).roundToInt() / 100.0
                                        BlexsyEqualizer.updateNode(safeIdx) { it.copy(q = roundedQ) }
                                    },
                                    valueRange = 0.3f..5.0f,
                                    enabled = eqState.enabled && node.enabled && node.filterType == FilterType.PK,
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = color)
                                )
                            }
                        }
                    }
                }
            }

            // Tab 1: 10-BAND SLIDERS
            if (selectedTab == 1) {
                item {
                    Text(
                        text = stringResource(R.string.eq_bands_header),
                        style = BlexsyTypography.Caption.copy(
                            color = BlexsyColors.TextMuted,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    InteractiveEqCanvas(
                        bands = eqState.bands,
                        enabled = eqState.enabled,
                        onBandGain = { freq, gain -> BlexsyEqualizer.setBandGain(freq, gain) }
                    )
                }

                item {
                    Text(
                        text = stringResource(R.string.eq_fine_tuning_header),
                        style = BlexsyTypography.Caption.copy(
                            color = BlexsyColors.TextMuted,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                    )
                }

                items(eqState.bands.size) { index ->
                    val band = eqState.bands[index]
                    BandRow(
                        band = band,
                        enabled = eqState.enabled,
                        onGainChange = { gain -> BlexsyEqualizer.setBandGain(band.frequency, gain) }
                    )
                }
            }

            // Preamp Slider (Common to all tabs)
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(BlexsyColors.SurfaceContainer)
                        .padding(16.dp)
                ) {
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.eq_preamp),
                                style = BlexsyTypography.Body.copy(fontWeight = FontWeight.SemiBold)
                            )
                            val preampText = (if (eqState.preamp > 0) "+" else "") + String.format("%.1f dB", eqState.preamp)
                            Text(
                                text = preampText,
                                style = BlexsyTypography.Caption.copy(
                                    color = if (eqState.preamp != 0.0) BlexsyColors.Primary else BlexsyColors.TextMuted,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }
                        Slider(
                            value = eqState.preamp.toFloat(),
                            onValueChange = { BlexsyEqualizer.setPreamp(it.toDouble()) },
                            valueRange = -12f..12f,
                            enabled = eqState.enabled,
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = BlexsyColors.Primary,
                                inactiveTrackColor = Color.White.copy(alpha = 0.12f)
                            )
                        )
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(36.dp))
            }
        }
    }
}

@Composable
private fun EqGraphCanvas(
    bands: List<ParametricEQBand>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    onMove: (index: Int, frequency: Double, gain: Double) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val responseCurve = remember { ResponseCurve(GraphFrequencies) }

    val total = remember(bands) {
        DoubleArray(GraphFrequencies.size).also { responseCurve.response(bands, it) }
    }

    val currentBands by rememberUpdatedState(bands)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentOnSelect by rememberUpdatedState(onSelect)
    var draggingIndex by remember { mutableIntStateOf(-1) }

    val labelStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 9.sp, color = BlexsyColors.TextMuted)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BlexsyColors.SurfaceContainer)
            .border(1.dp, BlexsyColors.Border, RoundedCornerShape(16.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { pos ->
                        val plot = EqPlot(28.dp.toPx(), 16.dp.toPx(), size.width - 12.dp.toPx(), size.height - 24.dp.toPx())
                        var bestIdx = -1
                        var bestD = 36.dp.toPx()
                        currentBands.forEachIndexed { i, b ->
                            val d = hypot(pos.x - plot.x(b.frequency), pos.y - plot.y(b.gain))
                            if (d < bestD) {
                                bestD = d
                                bestIdx = i
                            }
                        }
                        if (bestIdx >= 0) currentOnSelect(bestIdx)
                    }
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val plot = EqPlot(28.dp.toPx(), 16.dp.toPx(), size.width - 12.dp.toPx(), size.height - 24.dp.toPx())
                    var hit = -1
                    var minD = 36.dp.toPx()
                    currentBands.forEachIndexed { i, b ->
                        val d = hypot(down.position.x - plot.x(b.frequency), down.position.y - plot.y(b.gain))
                        if (d < minD) {
                            minD = d
                            hit = i
                        }
                    }
                    if (hit < 0) return@awaitEachGesture
                    down.consume()
                    currentOnSelect(hit)
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    draggingIndex = hit

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val newFreq = plot.frequencyAt(change.position.x).roundToInt().toDouble()
                        val newGain = ((plot.gainAt(change.position.y) * 10).roundToInt() / 10.0).let {
                            if (abs(it) < 0.25) 0.0 else it
                        }
                        currentOnMove(hit, newFreq, newGain)
                    }
                    draggingIndex = -1
                }
            }
    ) {
        val plot = EqPlot(28.dp.toPx(), 16.dp.toPx(), size.width - 12.dp.toPx(), size.height - 24.dp.toPx())

        // Draw frequency and decibel grid
        drawEqGrid(plot, measurer, labelStyle)

        // Draw response curve
        val path = Path()
        val fillPath = Path()
        for (i in GraphFrequencies.indices) {
            val x = plot.x(GraphFrequencies[i])
            val y = plot.y(total[i])
            if (i == 0) {
                path.moveTo(x, y)
                fillPath.moveTo(x, plot.y(0.0))
                fillPath.lineTo(x, y)
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(plot.right, plot.y(0.0))
        fillPath.close()

        drawPath(fillPath, BlexsyColors.Primary.copy(alpha = 0.12f))
        drawPath(path, BlexsyColors.Primary, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round))

        // Draw nodes
        bands.forEachIndexed { i, b ->
            val center = Offset(plot.x(b.frequency), plot.y(b.gain))
            val isSelected = i == selected
            val nodeColor = NodeColors.getOrElse(i % NodeColors.size) { BlexsyColors.Primary }
            val r = (if (isSelected) 8.dp else 6.dp).toPx()

            if (isSelected) {
                drawCircle(nodeColor.copy(alpha = 0.25f), r * 2.2f, center)
                drawCircle(Color.White, r + 2.dp.toPx(), center, style = Stroke(1.5.dp.toPx()))
            }
            drawCircle(Color.Black, r + 1.dp.toPx(), center)
            drawCircle(if (b.enabled) nodeColor else BlexsyColors.TextMuted, r, center)
        }
    }
}

private fun DrawScope.drawEqGrid(
    plot: EqPlot,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: TextStyle
) {
    val lineCol = Color.White.copy(alpha = 0.07f)
    val zeroCol = Color.White.copy(alpha = 0.16f)

    // Decibel horizontal grid
    listOf(12.0 to "+12", 6.0 to "+6", 0.0 to "0", -6.0 to "-6", -12.0 to "-12").forEach { (db, label) ->
        val y = plot.y(db)
        drawLine(
            if (db == 0.0) zeroCol else lineCol,
            Offset(plot.left, y),
            Offset(plot.right, y),
            strokeWidth = 1.dp.toPx(),
            pathEffect = if (db == 0.0) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null
        )
        val textRes = measurer.measure(label, style)
        drawText(textRes, topLeft = Offset(plot.left - textRes.size.width - 4.dp.toPx(), y - textRes.size.height / 2f))
    }

    // Frequency vertical grid
    GridFrequencies.forEachIndexed { i, f ->
        val x = plot.x(f)
        drawLine(lineCol, Offset(x, plot.top), Offset(x, plot.bottom), 1.dp.toPx())
        val textRes = measurer.measure(GridLabels[i], style)
        drawText(textRes, topLeft = Offset(x - textRes.size.width / 2f, plot.bottom + 4.dp.toPx()))
    }
}

@Composable
private fun InteractiveEqCanvas(
    bands: List<ParametricEQBand>,
    enabled: Boolean,
    onBandGain: (Double, Double) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val currentBands by rememberUpdatedState(bands)
    val currentOnBandGain by rememberUpdatedState(onBandGain)
    var activeColumn by remember { mutableIntStateOf(-1) }

    val valueStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 9.sp, color = BlexsyColors.TextSecondary)
    val freqStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, color = BlexsyColors.TextMuted)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(BlexsyColors.SurfaceContainer)
            .border(1.dp, BlexsyColors.Border, RoundedCornerShape(16.dp))
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    val top = 28.dp.toPx()
                    val bottom = size.height - 28.dp.toPx()
                    val cols = currentBands
                    if (cols.isEmpty()) return@awaitEachGesture
                    val colWidth = size.width / cols.size

                    fun colAt(x: Float) = (x / colWidth).toInt().coerceIn(0, cols.size - 1)
                    fun gainAt(y: Float): Double {
                        val g = Biquad.MaxGainDb - ((y - top) / (bottom - top)).coerceIn(0f, 1f) * 2 * Biquad.MaxGainDb
                        val rounded = (g * 2).roundToInt() / 2.0
                        return if (abs(rounded) < 0.4) 0.0 else rounded
                    }

                    val firstCol = colAt(down.position.x)
                    activeColumn = firstCol
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    currentOnBandGain(cols[firstCol].frequency, gainAt(down.position.y))

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val nextCol = colAt(change.position.x)
                        val nextGain = gainAt(change.position.y)
                        if (nextCol != activeColumn) {
                            activeColumn = nextCol
                            haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        }
                        currentOnBandGain(cols[nextCol].frequency, nextGain)
                        change.consume()
                    }
                    activeColumn = -1
                }
            }
    ) {
        val top = 28.dp.toPx()
        val bottom = size.height - 28.dp.toPx()
        val zero = (top + bottom) / 2f
        val colWidth = size.width / bands.size

        fun yForGain(gain: Double): Float =
            top + ((Biquad.MaxGainDb - gain) / (2 * Biquad.MaxGainDb)).toFloat().coerceIn(0f, 1f) * (bottom - top)

        // Center 0 dB dashed line
        drawLine(
            color = Color.White.copy(alpha = 0.12f),
            start = Offset(8.dp.toPx(), zero),
            end = Offset(size.width - 8.dp.toPx(), zero),
            strokeWidth = 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))
        )

        val points = bands.mapIndexed { idx, band ->
            Offset(colWidth * (idx + 0.5f), yForGain(band.gain))
        }
        if (points.size > 1) {
            val path = Path().apply {
                moveTo(points.first().x, points.first().y)
                for (i in 0 until points.size - 1) {
                    val p0 = points[maxOf(0, i - 1)]
                    val p1 = points[i]
                    val p2 = points[i + 1]
                    val p3 = points[minOf(points.size - 1, i + 2)]
                    val c1 = Offset(p1.x + (p2.x - p0.x) / 6f, p1.y + (p2.y - p0.y) / 6f)
                    val c2 = Offset(p2.x - (p3.x - p1.x) / 6f, p2.y - (p3.y - p1.y) / 6f)
                    cubicTo(c1.x, c1.y, c2.x, c2.y, p2.x, p2.y)
                }
            }
            drawPath(
                path = path,
                color = if (enabled) BlexsyColors.Primary else BlexsyColors.TextMuted,
                style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            )
        }

        // Columns, thumbs, labels
        bands.forEachIndexed { idx, band ->
            val cx = colWidth * (idx + 0.5f)
            val cy = yForGain(band.gain)
            val isActive = idx == activeColumn

            // Stem
            drawLine(
                color = Color.White.copy(alpha = if (isActive) 0.25f else 0.08f),
                start = Offset(cx, top),
                end = Offset(cx, bottom),
                strokeWidth = if (isActive) 2.dp.toPx() else 1.dp.toPx()
            )

            // Thumb pill
            val thumbW = 20.dp.toPx()
            val thumbH = 12.dp.toPx()
            drawRoundRect(
                color = if (enabled) BlexsyColors.Primary else BlexsyColors.TextMuted,
                topLeft = Offset(cx - thumbW / 2, cy - thumbH / 2),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )
            drawRoundRect(
                color = Color.White,
                topLeft = Offset(cx - thumbW / 2, cy - thumbH / 2),
                size = Size(thumbW, thumbH),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(1.dp.toPx())
            )

            // Gain text above
            val gainStr = (if (band.gain > 0) "+" else "") + String.format("%.1f", band.gain)
            val gainLayout = measurer.measure(gainStr, valueStyle)
            drawText(
                gainLayout,
                topLeft = Offset(cx - gainLayout.size.width / 2f, 8.dp.toPx())
            )

            // Frequency label below
            val freqStr = formatFreq(band.frequency)
            val freqLayout = measurer.measure(freqStr, freqStyle)
            drawText(
                freqLayout,
                topLeft = Offset(cx - freqLayout.size.width / 2f, size.height - 22.dp.toPx())
            )
        }
    }
}

@Composable
private fun BandRow(
    band: ParametricEQBand,
    enabled: Boolean,
    onGainChange: (Double) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var lastVibratedGain by remember(band.frequency) { mutableStateOf(band.gain) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(BlexsyColors.SurfaceContainer)
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = formatFreq(band.frequency),
            style = BlexsyTypography.Caption.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (band.gain != 0.0 && enabled) BlexsyColors.Primary else BlexsyColors.TextPrimary
            ),
            modifier = Modifier.width(52.dp)
        )

        Slider(
            value = band.gain.toFloat(),
            onValueChange = { raw ->
                val rounded = (raw * 2).roundToInt() / 2.0
                val finalGain = if (abs(rounded) < 0.4) 0.0 else rounded
                if (finalGain == 0.0 && lastVibratedGain != 0.0) {
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
                lastVibratedGain = finalGain
                onGainChange(finalGain)
            },
            valueRange = -Biquad.MaxGainDb.toFloat()..Biquad.MaxGainDb.toFloat(),
            enabled = enabled,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = BlexsyColors.Primary,
                inactiveTrackColor = Color.White.copy(alpha = 0.12f)
            )
        )

        val gainText = (if (band.gain > 0) "+" else "") + String.format("%.1f dB", band.gain)
        Text(
            text = gainText,
            style = BlexsyTypography.Caption.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                color = if (band.gain != 0.0 && enabled) BlexsyColors.Primary else BlexsyColors.TextMuted
            ),
            modifier = Modifier.width(54.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End
        )
    }
}

private fun formatFreq(freq: Double): String {
    return if (freq >= 1000.0) {
        val k = freq / 1000.0
        if (k == k.toInt().toDouble()) "${k.toInt()}k" else String.format("%.1fk", k)
    } else {
        "${freq.toInt()}"
    }
}
