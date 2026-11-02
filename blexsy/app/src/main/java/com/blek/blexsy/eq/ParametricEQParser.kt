package com.blek.blexsy.eq

object ParametricEQParser {

    fun parseText(content: String): ParametricEQ {
        val lines = content.lines()
        var preamp = 0.0
        val bands = mutableListOf<ParametricEQBand>()

        for (line in lines) {
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) continue

            when {
                trimmedLine.startsWith("Preamp:", ignoreCase = true) -> {
                    preamp = parsePreamp(trimmedLine)
                }
                trimmedLine.startsWith("Filter", ignoreCase = true) -> {
                    val band = parseFilterLine(trimmedLine)
                    if (band != null) {
                        bands.add(band)
                    }
                }
            }
        }

        return ParametricEQ(
            preamp = preamp,
            bands = bands
        )
    }

    private fun parsePreamp(line: String): Double {
        val regex = Regex("""Preamp:\s*([-+]?\d+\.?\d*)\s*dB""", RegexOption.IGNORE_CASE)
        val match = regex.find(line)
        return match?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
    }

    private fun parseFilterLine(line: String): ParametricEQBand? {
        try {
            if (!line.contains("ON", ignoreCase = true)) {
                return null
            }
            val filterType = parseFilterType(line) ?: return null
            val frequency = parseValue(line, "Fc", "Hz") ?: return null
            val gain = parseValue(line, "Gain", "dB") ?: return null
            val q = parseValue(line, "Q", null) ?: return null

            return ParametricEQBand(
                filterType = filterType,
                frequency = frequency,
                gain = gain,
                q = q
            )
        } catch (_: Exception) {
            return null
        }
    }

    private fun parseFilterType(line: String): FilterType? {
        return when {
            line.contains("LSC", ignoreCase = true) -> FilterType.LSC
            line.contains("HSC", ignoreCase = true) -> FilterType.HSC
            line.contains("PK", ignoreCase = true) -> FilterType.PK
            line.contains("LPQ", ignoreCase = true) -> FilterType.LPQ
            line.contains("HPQ", ignoreCase = true) -> FilterType.HPQ
            else -> null
        }
    }

    private fun parseValue(line: String, keyword: String, unit: String?): Double? {
        val unitPattern = if (unit != null) "\\s*$unit" else ""
        val regex = Regex("""$keyword\s+([-+]?\d+\.?\d*)$unitPattern""", RegexOption.IGNORE_CASE)
        val match = regex.find(line)
        return match?.groupValues?.get(1)?.toDoubleOrNull()
    }
}
