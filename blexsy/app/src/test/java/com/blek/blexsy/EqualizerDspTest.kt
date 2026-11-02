package com.blek.blexsy

import com.blek.blexsy.eq.AutoEqEntry
import com.blek.blexsy.eq.AutoEqFitter
import com.blek.blexsy.eq.AutoEqMeasurement
import com.blek.blexsy.eq.AutoEqModel
import com.blek.blexsy.eq.AutoEqRepository
import com.blek.blexsy.eq.AutoEqTuning
import com.blek.blexsy.eq.Biquad
import com.blek.blexsy.eq.FilterType
import com.blek.blexsy.eq.HeadphoneForm
import com.blek.blexsy.eq.ParametricEQBand
import com.blek.blexsy.eq.ParametricEQParser
import com.blek.blexsy.eq.PeakLimiter
import com.blek.blexsy.eq.ResponseCurve
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EqualizerDspTest {

    @Test
    fun testBiquadFlatBandMagnitude() {
        val band = ParametricEQBand(frequency = 1000.0, gain = 0.0, q = 1.41, filterType = FilterType.PK)
        val sampleRate = 48000
        val coeffs = DoubleArray(Biquad.Stride)
        Biquad.coefficients(band, sampleRate, coeffs)

        // Flat gain (0 dB) should produce near 0.0 dB magnitude response across frequencies
        val magAt1k = Biquad.magnitudeDb(coeffs, 0, 1000.0, sampleRate)
        assertTrue(kotlin.math.abs(magAt1k) < 0.01, "Expected flat magnitude near 0, got $magAt1k")
    }

    @Test
    fun testBiquadBoostBandMagnitude() {
        val boostDb = 6.0
        val band = ParametricEQBand(frequency = 1000.0, gain = boostDb, q = 1.41, filterType = FilterType.PK)
        val sampleRate = 48000
        val coeffs = DoubleArray(Biquad.Stride)
        Biquad.coefficients(band, sampleRate, coeffs)

        val magAtPeak = Biquad.magnitudeDb(coeffs, 0, 1000.0, sampleRate)
        assertTrue(kotlin.math.abs(magAtPeak - boostDb) < 0.05, "Expected peak magnitude near $boostDb dB, got $magAtPeak")
    }

    @Test
    fun testPeakLimiterCeiling() {
        val limiter = PeakLimiter()
        val sampleRate = 48000
        val channels = 2
        limiter.configure(sampleRate, channels)

        // Generate buffer with loud peaks exceeding 1.0 (e.g. 2.0 amplitude)
        val frames = 1024
        val buffer = DoubleArray(frames * channels) { 2.0 }
        limiter.process(buffer, frames)

        // Ensure processed buffer peaks are reduced towards or below ceiling (1.0)
        var maxPeak = 0.0
        for (sample in buffer) {
            maxPeak = kotlin.math.max(maxPeak, kotlin.math.abs(sample))
        }
        assertTrue(maxPeak <= 1.01, "Expected limiter to clamp peaks <= 1.0, got $maxPeak")
    }

    @Test
    fun testAutoEqIndexParsingAndSearch() {
        val indexMarkdown = """
            # Index
            - [Sony WH-1000XM5](./oratory1990/over-ear/Sony%20WH-1000XM5) by oratory1990
            - [Sony WH-1000XM5](./rtings/over-ear/Sony%20WH-1000XM5) by rtings on GRAS 45CA
            - [Apple AirPods Pro 2](./crinacle/711%20in-ear/Apple%20AirPods%20Pro%202) by crinacle on 711
        """.trimIndent()

        val models = AutoEqRepository.parseIndex(indexMarkdown)
        assertEquals(2, models.size)

        val sony = models.first { it.name.startsWith("Sony") }
        assertEquals(2, sony.entries.size)
        assertEquals(HeadphoneForm.OverEar, sony.form)

        val searchSony = AutoEqRepository.search(models, "xm5")
        assertEquals(1, searchSony.size)
        assertEquals("Sony WH-1000XM5", searchSony.first().name)

        val searchAirpods = AutoEqRepository.search(models, "airpods")
        assertEquals(1, searchAirpods.size)
        assertEquals("Apple AirPods Pro 2", searchAirpods.first().name)
    }

    @Test
    fun testParametricEQParser() {
        val content = """
            Preamp: -4.5 dB
            Filter 1: ON PK Fc 32 Hz Gain 4.5 dB Q 1.41
            Filter 2: ON LSC Fc 105 Hz Gain -3.0 dB Q 0.70
            Filter 3: OFF PK Fc 1000 Hz Gain 2.0 dB Q 2.0
            Filter 4: ON HSC Fc 10000 Hz Gain 1.5 dB Q 0.70
        """.trimIndent()

        val parsed = ParametricEQParser.parseText(content)
        assertEquals(-4.5, parsed.preamp, 0.01)
        assertEquals(3, parsed.bands.size)
        assertEquals(FilterType.PK, parsed.bands[0].filterType)
        assertEquals(32.0, parsed.bands[0].frequency, 0.1)
        assertEquals(4.5, parsed.bands[0].gain, 0.1)
        assertEquals(FilterType.LSC, parsed.bands[1].filterType)
        assertEquals(FilterType.HSC, parsed.bands[2].filterType)
    }

    @Test
    fun testResponseCurveAccumulation() {
        val freqs = ResponseCurve.logSpaced(10, 20.0, 20000.0)
        val curve = ResponseCurve(freqs)
        val band = ParametricEQBand(frequency = 1000.0, gain = 6.0, q = 1.41, filterType = FilterType.PK)
        val response = curve.response(listOf(band))

        assertEquals(freqs.size, response.size)
        // Magnitude at 1000 Hz should be positive boost
        val centerIdx = freqs.indexOfMinBy { kotlin.math.abs(it - 1000.0) }
        assertTrue(response[centerIdx] > 4.0, "Expected boost at center freq")
    }

    private inline fun DoubleArray.indexOfMinBy(selector: (Double) -> Double): Int {
        var minVal = Double.MAX_VALUE
        var minIdx = -1
        for (i in indices) {
            val v = selector(this[i])
            if (v < minVal) {
                minVal = v
                minIdx = i
            }
        }
        return minIdx
    }
}
