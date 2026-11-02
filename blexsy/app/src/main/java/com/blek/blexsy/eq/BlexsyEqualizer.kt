package com.blek.blexsy.eq

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class EqPreset(
    val id: String,
    val name: String,
    val gains: DoubleArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as EqPreset
        return id == other.id && name == other.name && gains.contentEquals(other.gains)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + gains.contentHashCode()
        return result
    }
}

data class EqualizerState(
    val enabled: Boolean = false,
    val presetId: String? = "flat",
    val preamp: Double = 0.0,
    val bands: List<ParametricEQBand> = emptyList(),
    val correction: AutoEqCorrection? = null,
    val correctionEnabled: Boolean = true
)

@OptIn(UnstableApi::class)
object BlexsyEqualizer {

    val Frequencies = doubleArrayOf(31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    private const val OctaveQ = 1.41
    private val json = Json { ignoreUnknownKeys = true }

    val Presets = listOf(
        EqPreset("flat", "Flat", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        EqPreset("bass_boost", "Bass Booster", doubleArrayOf(5.5, 4.5, 3.5, 2.5, 1.2, 0.0, 0.0, 0.0, 0.0, 0.0)),
        EqPreset("bass_cut", "Bass Reducer", doubleArrayOf(-5.5, -4.5, -3.5, -2.5, -1.2, 0.0, 0.0, 0.0, 0.0, 0.0)),
        EqPreset("acoustic", "Acoustic", doubleArrayOf(4.5, 4.5, 3.5, 1.0, 1.5, 1.5, 3.0, 3.5, 3.0, 1.5)),
        EqPreset("rock", "Rock", doubleArrayOf(5.0, 4.0, 3.0, 1.5, -0.5, -1.0, 0.5, 2.5, 3.5, 4.5)),
        EqPreset("pop", "Pop", doubleArrayOf(-1.5, -1.0, 0.0, 2.0, 4.0, 4.0, 2.0, 0.0, -1.0, -1.5)),
        EqPreset("hiphop", "Hip-Hop", doubleArrayOf(5.0, 4.0, 1.5, 3.0, -1.0, -1.0, 1.5, -0.5, 2.0, 3.0)),
        EqPreset("classical", "Classical", doubleArrayOf(4.5, 3.5, 3.0, 2.5, -1.5, -1.5, 0.0, 2.0, 3.0, 3.5)),
        EqPreset("dance", "Dance", doubleArrayOf(3.5, 6.5, 5.0, 0.0, 2.0, 3.5, 5.0, 4.5, 3.5, 0.0)),
        EqPreset("electronic", "Electronic", doubleArrayOf(4.5, 4.0, 1.5, 0.0, -2.0, 2.0, 1.0, 1.5, 4.5, 5.0)),
        EqPreset("jazz", "Jazz", doubleArrayOf(4.0, 3.0, 1.5, 2.0, -1.5, -1.5, 0.0, 1.5, 3.0, 3.5)),
        EqPreset("vocal", "Vocal Booster", doubleArrayOf(-1.5, -3.0, -3.0, 1.5, 3.5, 3.5, 3.0, 1.5, 0.0, -1.5)),
        EqPreset("treble_boost", "Treble Booster", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 2.5, 3.5, 4.5, 5.5)),
        EqPreset("treble_cut", "Treble Reducer", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, -1.0, -2.5, -3.5, -4.5, -5.5)),
    )

    val audioProcessor = CustomEqualizerAudioProcessor()

    private var prefs: SharedPreferences? = null

    private val _state = MutableStateFlow(EqualizerState(bands = defaultBands()))
    val state: StateFlow<EqualizerState> = _state.asStateFlow()

    private fun defaultBands(gains: DoubleArray? = null): List<ParametricEQBand> {
        return Frequencies.mapIndexed { index, freq ->
            ParametricEQBand(
                frequency = freq,
                gain = gains?.getOrNull(index) ?: 0.0,
                q = OctaveQ,
                filterType = FilterType.PK
            )
        }
    }

    fun init(context: Context) {
        if (prefs != null) return
        val sp = context.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        prefs = sp

        val enabled = sp.getBoolean("eq_enabled", false)
        val presetId = sp.getString("eq_preset_id", "flat")
        val preamp = sp.getFloat("eq_preamp", 0.0f).toDouble()
        val savedGains = sp.getString("eq_gains", null)?.split(",")?.mapNotNull { it.toDoubleOrNull() }
        val correctionJson = sp.getString("eq_correction_json", null)
        val correctionEnabled = sp.getBoolean("eq_correction_enabled", true)
        val correction = correctionJson?.let {
            runCatching { json.decodeFromString<AutoEqCorrection>(it) }.getOrNull()
        }

        val bands = if (savedGains != null && savedGains.size == Frequencies.size) {
            defaultBands(savedGains.toDoubleArray())
        } else {
            val preset = Presets.firstOrNull { it.id == presetId } ?: Presets.first()
            defaultBands(preset.gains)
        }

        val restored = EqualizerState(
            enabled = enabled,
            presetId = presetId,
            preamp = preamp,
            bands = bands,
            correction = correction,
            correctionEnabled = correctionEnabled
        )
        _state.value = restored
        syncProcessor(restored)
    }

    fun setEnabled(enabled: Boolean) {
        val updated = _state.value.copy(enabled = enabled)
        _state.value = updated
        syncProcessor(updated)
        prefs?.edit()?.putBoolean("eq_enabled", enabled)?.apply()
    }

    fun applyPreset(presetId: String) {
        val preset = Presets.firstOrNull { it.id == presetId } ?: return
        val newBands = defaultBands(preset.gains)
        val updated = _state.value.copy(
            presetId = presetId,
            bands = newBands
        )
        _state.value = updated
        syncProcessor(updated)
        saveSettings(updated)
    }

    fun setBandGain(frequency: Double, gain: Double) {
        val clampedGain = gain.coerceIn(-Biquad.MaxGainDb, Biquad.MaxGainDb)
        val currentBands = _state.value.bands.toMutableList()
        val index = currentBands.indexOfFirst { it.frequency == frequency }
        if (index != -1) {
            currentBands[index] = currentBands[index].copy(gain = clampedGain)
        }
        val matchingPreset = Presets.firstOrNull { p ->
            p.gains.indices.all { i ->
                val b = currentBands.getOrNull(i)
                b != null && kotlin.math.abs(b.gain - p.gains[i]) < 0.01
            }
        }
        val updated = _state.value.copy(
            presetId = matchingPreset?.id,
            bands = currentBands
        )
        _state.value = updated
        syncProcessor(updated)
        saveSettings(updated)
    }

    fun updateNode(index: Int, transform: (ParametricEQBand) -> ParametricEQBand) {
        val currentBands = _state.value.bands.toMutableList()
        if (index in currentBands.indices) {
            currentBands[index] = transform(currentBands[index])
            val updated = _state.value.copy(presetId = null, bands = currentBands)
            _state.value = updated
            syncProcessor(updated)
            saveSettings(updated)
        }
    }

    fun setPreamp(preamp: Double) {
        val clamped = preamp.coerceIn(-Biquad.MaxGainDb, Biquad.MaxGainDb)
        val updated = _state.value.copy(preamp = clamped)
        _state.value = updated
        syncProcessor(updated)
        saveSettings(updated)
    }

    fun setCorrection(correction: AutoEqCorrection?) {
        val updated = _state.value.copy(
            correction = correction,
            correctionEnabled = correction != null
        )
        _state.value = updated
        syncProcessor(updated)
        saveSettings(updated)
    }

    fun setCorrectionEnabled(enabled: Boolean) {
        val updated = _state.value.copy(correctionEnabled = enabled)
        _state.value = updated
        syncProcessor(updated)
        saveSettings(updated)
    }

    fun reset() {
        applyPreset("flat")
        setPreamp(0.0)
    }

    private fun syncProcessor(st: EqualizerState) {
        if (st.enabled) {
            val userBands = st.bands.filter { it.enabled && kotlin.math.abs(it.gain) >= 0.05 }
            val corr = st.correction
            val correctionOn = st.correctionEnabled && corr != null
            val correctionBands = if (corr != null && st.correctionEnabled) corr.filters else emptyList()
            val totalPreamp = (st.preamp + (if (corr != null && st.correctionEnabled) corr.preamp else 0.0))
                .coerceIn(-Biquad.MaxGainDb, Biquad.MaxGainDb)
            audioProcessor.applyProfile(ParametricEQ(preamp = totalPreamp, bands = userBands + correctionBands))
        } else {
            audioProcessor.disable()
        }
    }

    private fun saveSettings(st: EqualizerState) {
        prefs?.edit()?.apply {
            putString("eq_preset_id", st.presetId)
            putFloat("eq_preamp", st.preamp.toFloat())
            putString("eq_gains", st.bands.joinToString(",") { it.gain.toString() })
            val correctionJson = st.correction?.let { runCatching { json.encodeToString(it) }.getOrNull() }
            putString("eq_correction_json", correctionJson)
            putBoolean("eq_correction_enabled", st.correctionEnabled)
            apply()
        }
    }
}
