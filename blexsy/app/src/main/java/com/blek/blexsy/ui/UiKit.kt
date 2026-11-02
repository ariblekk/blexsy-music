package com.blek.blexsy.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// =========================================================================================
// BUTTON COMPONENT (shadcn-style Button with Variants and Sizes)
// =========================================================================================

enum class ButtonVariant {
    Primary,   // Blexsy brand green with dark text
    Secondary, // Neutral dark container with white text
    Outline,   // Muted border with white text
    Ghost,     // Completely transparent background
    Danger     // Red warning style
}

enum class ButtonSize(
    val height: Dp,
    val horizontalPadding: Dp,
    val fontSize: Int
) {
    Sm(height = 36.dp, horizontalPadding = 14.dp, fontSize = 13),
    Md(height = 44.dp, horizontalPadding = 18.dp, fontSize = 14),
    Lg(height = 50.dp, horizontalPadding = 24.dp, fontSize = 15)
}

@Composable
fun BlexsyButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: ButtonVariant = ButtonVariant.Primary,
    size: ButtonSize = ButtonSize.Md,
    shape: Shape = CircleShape,
    enabled: Boolean = true,
    isLoading: Boolean = false,
    leadingIcon: ImageVector? = null
) {
    val poppins = LocalBlexsyFontFamily.current

    val containerColor = when (variant) {
        ButtonVariant.Primary -> BlexsyColors.Primary
        ButtonVariant.Secondary -> Color(0xFF282828)
        ButtonVariant.Outline -> Color.Transparent
        ButtonVariant.Ghost -> Color.Transparent
        ButtonVariant.Danger -> Color(0xFFE53935)
    }

    val contentColor = when (variant) {
        ButtonVariant.Primary -> Color.Black
        ButtonVariant.Secondary -> Color.White
        ButtonVariant.Outline -> Color.White
        ButtonVariant.Ghost -> Color(0xFFB3B3B3)
        ButtonVariant.Danger -> Color.White
    }

    val borderModifier = if (variant == ButtonVariant.Outline) {
        Modifier.border(1.dp, Color(0xFF383838), shape)
    } else Modifier

    Button(
        onClick = onClick,
        enabled = enabled && !isLoading,
        shape = shape,
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor,
            disabledContainerColor = containerColor.copy(alpha = 0.35f),
            disabledContentColor = contentColor.copy(alpha = 0.35f)
        ),
        contentPadding = PaddingValues(horizontal = size.horizontalPadding),
        modifier = modifier
            .then(borderModifier)
            .height(size.height)
    ) {
        if (isLoading) {
            ExpressiveLoadingIndicator(color = contentColor, modifier = Modifier.size(20.dp))
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                if (leadingIcon != null) {
                    Icon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = contentColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = text,
                    fontFamily = poppins,
                    fontSize = size.fontSize.sp,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
            }
        }
    }
}

// =========================================================================================
// INPUT COMPONENT (shadcn-style Input with unified Poppins typography and Dark Surfaces)
// =========================================================================================

enum class InputShape {
    Pill,    // CircleShape
    Rounded  // RoundedCornerShape(14.dp)
}

@Composable
fun BlexsyInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    label: String? = null,
    shape: InputShape = InputShape.Pill,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    textStyle: TextStyle? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingContent: (@Composable () -> Unit)? = null,
    focusRequester: FocusRequester? = null
) {
    val poppins = LocalBlexsyFontFamily.current
    val actualShape: Shape = if (shape == InputShape.Pill) CircleShape else RoundedCornerShape(14.dp)
    val textFieldModifier = if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier

    Column(modifier = modifier) {
        if (!label.isNullOrBlank()) {
            Text(
                text = label,
                fontFamily = poppins,
                color = Color(0xFF8C8C8C),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = singleLine,
            textStyle = textStyle ?: TextStyle(
                fontFamily = poppins,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            ),
            cursorBrush = SolidColor(Color.White),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = textFieldModifier,
            decorationBox = { innerTextField ->
                val borderMod = if (isError) {
                    Modifier.border(1.dp, Color(0xFFFF5252), actualShape)
                } else Modifier

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(actualShape)
                        .background(Color(0xFF282828))
                        .then(borderMod)
                        .padding(horizontal = 20.dp, vertical = 15.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (leadingIcon != null) {
                            leadingIcon()
                            Spacer(modifier = Modifier.width(10.dp))
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            if (value.isEmpty() && placeholder.isNotEmpty()) {
                                Text(
                                    text = placeholder,
                                    fontFamily = poppins,
                                    color = Color(0xFF666666),
                                    fontSize = textStyle?.fontSize ?: 16.sp,
                                    fontWeight = textStyle?.fontWeight ?: FontWeight.Normal
                                )
                            }
                            innerTextField()
                        }
                        if (trailingContent != null) {
                            Spacer(modifier = Modifier.width(8.dp))
                            trailingContent()
                        }
                    }
                }
            }
        )
    }
}

// =========================================================================================
// CHIP & BADGE COMPONENT (For filter pills, suggestions, and tags)
// =========================================================================================

@Composable
fun BlexsyChip(
    text: String,
    onClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    leadingText: String? = null,
    icon: ImageVector? = null,
    active: Boolean = false
) {
    val poppins = LocalBlexsyFontFamily.current

    val bg = if (active) BlexsyColors.Primary else Color(0xFF242426)
    val textCol = if (active) Color.Black else BlexsyColors.Primary
    val labelCol = if (active) Color.Black.copy(alpha = 0.7f) else Color(0xFF8C8C8C)

    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .then(
                if (onClick != null) {
                    Modifier.clickable(onClick = onClick)
                } else Modifier
            )
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textCol,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
        }
        if (!leadingText.isNullOrBlank()) {
            Text(
                text = leadingText,
                fontFamily = poppins,
                color = labelCol,
                fontSize = 12.sp
            )
        }
        Text(
            text = text,
            fontFamily = poppins,
            color = textCol,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

// =========================================================================================
// TABS COMPONENT (shadcn-style Segmented Control / Dynamic Variable-Width Sliding Pill Tabs)
// =========================================================================================

fun calculateTabIndicatorBounds(tabWidths: List<Int>, selectedIndex: Int): Pair<Int, Int> {
    if (tabWidths.isEmpty() || selectedIndex !in tabWidths.indices) return 0 to 0
    val offset = tabWidths.take(selectedIndex).sum()
    val width = tabWidths[selectedIndex]
    return offset to width
}

fun findTabIndexAtOffset(tabWidths: List<Int>, x: Float): Int {
    if (tabWidths.isEmpty() || tabWidths.all { it <= 0 }) return 0
    if (x <= 0f) return 0
    var cumulative = 0f
    for (i in tabWidths.indices) {
        cumulative += tabWidths[i]
        if (x < cumulative) return i
    }
    return tabWidths.lastIndex
}

@Composable
fun <T> BlexsyTabs(
    items: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false
) {
    if (items.isEmpty()) return
    val poppins = LocalBlexsyFontFamily.current
    val density = LocalDensity.current
    val selectedIndex = items.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val currentSelectedIndex by rememberUpdatedState(selectedIndex)
    val currentOnSelect by rememberUpdatedState(onSelect)
    val currentItems by rememberUpdatedState(items)

    var tabWidths by remember(items) { mutableStateOf(List(items.size) { 0 }) }
    val currentTabWidths by rememberUpdatedState(tabWidths)

    val (targetOffsetPx, targetWidthPx) = remember(tabWidths, selectedIndex) {
        calculateTabIndicatorBounds(tabWidths, selectedIndex)
    }

    val animOffset = remember { Animatable(0f) }
    val animWidth = remember { Animatable(0f) }
    var initialized by remember(items) { mutableStateOf(false) }

    // ponytail: dynamic variable-width spring sliding pill; upgrade to full HorizontalPager if screen content supports horizontal swipe.
    LaunchedEffect(targetOffsetPx, targetWidthPx) {
        if (targetWidthPx > 0) {
            if (!initialized) {
                animOffset.snapTo(targetOffsetPx.toFloat())
                animWidth.snapTo(targetWidthPx.toFloat())
                initialized = true
            } else {
                launch {
                    animOffset.animateTo(
                        targetOffsetPx.toFloat(),
                        spring(dampingRatio = 0.78f, stiffness = 450f)
                    )
                }
                launch {
                    animWidth.animateTo(
                        targetWidthPx.toFloat(),
                        spring(dampingRatio = 0.78f, stiffness = 450f)
                    )
                }
            }
        }
    }

    val containerModifier = if (fillMaxWidth) {
        modifier.fillMaxWidth()
    } else {
        modifier.wrapContentWidth(Alignment.Start)
    }

    Box(
        modifier = containerModifier
            .clip(CircleShape)
            .background(Color(0xFF242426))
            .padding(4.dp)
            .pointerInput(items) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var activeIdx = findTabIndexAtOffset(currentTabWidths, down.position.x)
                    if (activeIdx != currentSelectedIndex) {
                        currentOnSelect(currentItems[activeIdx].first)
                    }
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume()
                        val nextIdx = findTabIndexAtOffset(currentTabWidths, change.position.x)
                        if (nextIdx != activeIdx) {
                            activeIdx = nextIdx
                            currentOnSelect(currentItems[nextIdx].first)
                        }
                    }
                }
            }
    ) {
        Box(
            modifier = (if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier.wrapContentSize())
                .height(36.dp)
        ) {
            // Dynamic variable-width sliding pill indicator
            if (animWidth.value > 0f) {
                Box(
                    modifier = Modifier
                        .offset { IntOffset(animOffset.value.roundToInt(), 0) }
                        .width(with(density) { animWidth.value.toDp() })
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(BlexsyColors.Primary)
                )
            }

            // Tab labels
            Row(
                modifier = (if (fillMaxWidth) Modifier.fillMaxWidth() else Modifier.wrapContentWidth())
                    .fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEachIndexed { index, (_, label) ->
                    val isSelected = index == selectedIndex
                    val animatedTextColor by animateColorAsState(
                        targetValue = if (isSelected) Color.Black else Color(0xFF8C8C8C),
                        label = "tab_text_color"
                    )
                    val tabModifier = if (fillMaxWidth) {
                        Modifier.weight(1f)
                    } else {
                        Modifier.wrapContentWidth()
                    }
                    Box(
                        modifier = tabModifier
                            .fillMaxHeight()
                            .onSizeChanged { size ->
                                if (index < tabWidths.size && tabWidths[index] != size.width) {
                                    val updated = tabWidths.toMutableList()
                                    updated[index] = size.width
                                    tabWidths = updated
                                }
                            }
                            .padding(horizontal = 20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontFamily = poppins,
                            color = animatedTextColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BlexsyTabs(
    tabs: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    fillMaxWidth: Boolean = false
) {
    BlexsyTabs(
        items = tabs.mapIndexed { idx, label -> idx to label },
        selected = selectedIndex,
        onSelect = onSelect,
        modifier = modifier,
        fillMaxWidth = fillMaxWidth
    )
}

