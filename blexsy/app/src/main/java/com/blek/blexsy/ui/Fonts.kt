package com.blek.blexsy.ui

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.blek.blexsy.R

/**
 * Font tokens for the whole app.
 *
 * Each role is declared on exactly one line below. To swap a typeface app-wide, change that
 * line only — no call site needs to know which concrete font is in use.
 *
 * - [HeadingFont]  hero / section / display titles
 * - [SansFont]     every other piece of UI text
 * - [MonoFont]     numeric text (durations, ranks, counters, equalizer values)
 */
val HeadingFont: FontFamily = headingFamily()
val SansFont: FontFamily = variableFamily(R.font.manrope_variable)
val MonoFont: FontFamily = variableFamily(R.font.jetbrainsmono_variable)

/** Weights exposed by every variable font used here (wght axis 400–800). */
private fun variableWeights(): List<Pair<Int, FontWeight>> = listOf(
    400 to FontWeight.Normal,
    500 to FontWeight.Medium,
    600 to FontWeight.SemiBold,
    700 to FontWeight.Bold,
    800 to FontWeight.ExtraBold
)

/** Heading face: Deezer's display cut. Swap the two ids here to change the heading typeface. */
private fun headingFamily(): FontFamily = FontFamily(
    Font(R.font.deezer_bold, FontWeight.Bold),
    Font(R.font.deezer_extrabold, FontWeight.ExtraBold)
)

/** Builds a family from one variable font file, wiring each weight to its `wght` axis value. */
private fun variableFamily(resId: Int): FontFamily = FontFamily(
    *variableWeights().map { (axis, weight) ->
        Font(
            resId,
            weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(axis))
        )
    }.toTypedArray()
)
