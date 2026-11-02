package com.blek.blexsy.eq

import kotlinx.serialization.Serializable

@Serializable
enum class HeadphoneForm { OverEar, InEar, Earbud }

data class AutoEqEntry(
    val name: String,
    val source: String,
    val rig: String?,
    val path: String,
    val form: HeadphoneForm
) {
    val sourceLabel: String get() = if (rig != null) "$source · $rig" else source
}

data class AutoEqModel(
    val name: String,
    val entries: List<AutoEqEntry>
) {
    internal val searchKey: String = normalize(name)
    internal val compactKey: String = searchKey.replace(" ", "")
    val form: HeadphoneForm get() = entries.first().form
}

class AutoEqMeasurement(
    val entry: AutoEqEntry,
    val frequencies: DoubleArray,
    val raw: DoubleArray,
    val target: DoubleArray,
    val preset: ParametricEQ
)

@Serializable
data class AutoEqTuning(
    val bands: Int = 10,
    val maxHz: Int = 16_000,
    val bassDb: Double = 0.0,
    val tiltDbPerOctave: Double = 0.0
) {
    val isDefault: Boolean get() = bands == 10 && maxHz == 16_000 && bassDb == 0.0 && tiltDbPerOctave == 0.0

    companion object {
        val BandChoices = listOf(5, 8, 10, 15)
        val MaxHzChoices = listOf(10_000, 16_000, 20_000)
    }
}

@Serializable
data class AutoEqCorrection(
    val model: String,
    val source: String,
    val rig: String? = null,
    val path: String,
    val form: HeadphoneForm = HeadphoneForm.OverEar,
    val tuning: AutoEqTuning = AutoEqTuning(),
    val filters: List<ParametricEQBand> = emptyList(),
    val preamp: Double = 0.0
)
