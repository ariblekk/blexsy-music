package com.blek.blexsy.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class AlbumDetail(
    val id: String = "",
    val name: String = "Album",
    val artist: String = "Unknown artist",
    val image: String? = null,
    val year: String? = null,
    val type: String = "ALBUM",
    val tracks: List<Track> = emptyList()
)

@Serializable
data class AlbumSummary(
    val id: String = "",
    val name: String = "Album",
    val artist: String? = null,
    val image: String? = null,
    val year: String? = null,
    val type: String = "ALBUM"
)

@Serializable
data class ArtistSummary(
    val id: String = "",
    val name: String = "Artist",
    val image: String? = null,
    val subscribers: String? = null
)

@Serializable
data class ArtistDetail(
    val id: String = "",
    val name: String = "Artist",
    val image: String? = null,
    val biography: String? = null,
    val followers: Int? = null,
    @SerialName("monthly_listeners") val monthlyListeners: Int? = null,
    @SerialName("top_tracks") val topTracks: List<Track> = emptyList(),
    val albums: List<AlbumSummary> = emptyList(),
    val singlesAndEps: List<AlbumSummary> = emptyList(),
    val playlists: List<AlbumSummary> = emptyList(),
    val similarArtists: List<ArtistSummary> = emptyList(),
    val verified: Boolean = false,
    val subscribers: String? = null
)

@Serializable
data class Genre(
    val id: String = "",
    val name: String = "Genre",
    val image: String? = null
)

@Serializable
data class GenrePlaylist(
    val id: String = "",
    val name: String = "Playlist",
    val image: String? = null,
    val description: String = "",
    val followers: Int = 0
)

@Serializable
data class CreditPerson(
    val name: String = "",
    val url: String? = null
)

@Serializable
data class CreditRole(
    val title: String = "Credits",
    val people: List<CreditPerson> = emptyList()
)

@Serializable
data class TrackCredits(
    @SerialName("track_id") val trackId: String = "",
    val title: String = "",
    val roles: List<CreditRole> = emptyList(),
    val sources: List<String> = emptyList()
)

enum class LyricAlignment {
    START, END
}

data class LyricWord(
    val timeMs: Long,
    val text: String,
    val endMs: Long? = null
)

data class LyricLine(
    val timeMs: Long,
    val text: String,
    val words: List<LyricWord> = emptyList(),
    val sungUntilMs: Long? = null,
    val background: LyricLine? = null,
    val alignment: LyricAlignment = LyricAlignment.START,
    val isGap: Boolean = false
) {
    val hasWordSync: Boolean get() = words.isNotEmpty()
    val hasKnownEnd: Boolean get() = words.isNotEmpty() || sungUntilMs != null

    val endMs: Long
        get() {
            val lead = words.lastOrNull()?.endMs ?: sungUntilMs ?: words.lastOrNull()?.timeMs ?: timeMs
            return maxOf(lead, background?.endMs ?: lead)
        }
}

const val MIN_GAP_MS = 4_000L

fun List<LyricLine>.withInstrumentalGaps(): List<LyricLine> {
    if (isEmpty()) return this
    val out = ArrayList<LyricLine>(size + 4)
    if (!first().isGap && first().timeMs >= MIN_GAP_MS) {
        out.add(LyricLine(timeMs = 0L, text = "", isGap = true))
    }
    forEachIndexed { index, line ->
        out.add(line)
        val next = getOrNull(index + 1) ?: return@forEachIndexed
        if (!line.hasKnownEnd) return@forEachIndexed
        val end = line.endMs
        val gap = next.timeMs - end
        if (gap >= MIN_GAP_MS && end > line.timeMs) {
            out.add(LyricLine(timeMs = end, text = "", isGap = true))
        }
    }
    return out
}

fun List<LyricLine>.withBackgroundVocals(): List<LyricLine> = map { it.splitTrailingBracket() }

private fun LyricLine.splitTrailingBracket(): LyricLine {
    if (background != null || isGap) return this

    val open = bracketStart(text) ?: return this
    val lead = text.substring(0, open).trimEnd()
    val backing = text.substring(open).trim()
    if (lead.isEmpty() || !backing.any { it.isLetterOrDigit() }) return this

    if (words.isEmpty()) {
        return copy(
            text = lead,
            background = LyricLine(timeMs = timeMs, text = backing, sungUntilMs = sungUntilMs)
        )
    }

    val split = words.indexOfFirstStartingAt(open) ?: return this
    if (split <= 0) return this

    val backingWords = words.drop(split)
    return copy(
        text = lead,
        words = words.take(split),
        background = LyricLine(
            timeMs = backingWords.firstOrNull()?.timeMs ?: timeMs,
            text = backing,
            words = backingWords
        )
    )
}

private fun bracketStart(text: String): Int? {
    if (!text.endsWith(')')) return null
    var depth = 0
    for (index in text.indices.reversed()) {
        when (text[index]) {
            ')' -> depth++
            '(' -> {
                depth--
                if (depth == 0) return index.takeIf { it > 0 }
            }
        }
    }
    return null
}

private fun List<LyricWord>.indexOfFirstStartingAt(offset: Int): Int? {
    var at = 0
    forEachIndexed { index, word ->
        val wLen = word.text.length
        val hasSpace = word.text.endsWith(" ")
        if (at >= offset || at + wLen > offset) return index
        at += if (hasSpace) wLen else (wLen + 1)
    }
    return null
}

private val lrcTimestampRegex = Regex("""\[\s*(?:(\d{1,2}):)?(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\s*]""")
private val inlineTimeRegex = Regex("""<[^>]*>|\([0-9:.]+\)|\[[0-9:.]+]""")
private val wordTagRegex = Regex("""[<(]\s*(?:(\d{1,2}):)?(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\s*[>)]([^<(]*)""")
private val metaHeaderRegex = Regex("""\[[a-zA-Z]+:[^\]]*\]""", RegexOption.IGNORE_CASE)

private fun parseLrcTimestamp(
    hStr: String?,
    mStr: String?,
    sStr: String?,
    fracStr: String?
): Long {
    val hours = hStr?.toLongOrNull() ?: 0L
    val minutes = mStr?.toLongOrNull() ?: 0L
    val seconds = sStr?.toLongOrNull() ?: 0L
    val millis = when (fracStr?.length) {
        1 -> (fracStr.toLongOrNull() ?: 0L) * 100L
        2 -> (fracStr.toLongOrNull() ?: 0L) * 10L
        3 -> fracStr.toLongOrNull() ?: 0L
        else -> 0L
    }
    return hours * 3600000L + minutes * 60000L + seconds * 1000L + millis
}

fun parseLrc(raw: String): List<LyricLine> {
    if (raw.isBlank()) return emptyList()
    val cleanRaw = raw.replace("﻿", "")

    // ponytail: delegates TTML XML to TtmlLyrics. Ceiling: standard XML DOM.
    if (cleanRaw.contains(Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)) ||
        cleanRaw.contains("http://www.w3.org/ns/ttml", ignoreCase = true)) {
        val ttml = TtmlLyrics.parse(cleanRaw)
        if (ttml.isNotEmpty()) return ttml
    }

    val lines = mutableListOf<LyricLine>()

    for (rawLine in cleanRaw.lines()) {
        val trimmed = rawLine.trim()
        if (trimmed.isEmpty()) continue

        val matches = lrcTimestampRegex.findAll(trimmed).toList()
        if (matches.isEmpty()) continue

        val linePayload = trimmed.replace(lrcTimestampRegex, "").trim()
        val isRtl = linePayload.startsWith("<R>")
        val cleanPayload = if (isRtl) linePayload.removePrefix("<R>").trim() else linePayload
        val cleanText = cleanPayload.replace(metaHeaderRegex, "").replace(inlineTimeRegex, "").trim()
        if (cleanText.isEmpty()) continue

        for (match in matches) {
            val totalMs = parseLrcTimestamp(
                match.groupValues.getOrNull(1),
                match.groupValues.getOrNull(2),
                match.groupValues.getOrNull(3),
                match.groupValues.getOrNull(4)
            )

            // Ekstrak timing per kata/suku kata (<mm:ss.xx>kata atau (mm:ss.xx)kata)
            val wordMatches = wordTagRegex.findAll(cleanPayload).toList()
            val words = mutableListOf<LyricWord>()
            if (wordMatches.isNotEmpty()) {
                val firstStart = wordMatches.first().range.first
                if (firstStart > 0) {
                    val leading = cleanPayload.substring(0, firstStart).trim()
                    if (leading.isNotEmpty()) {
                        val firstWordTime = parseLrcTimestamp(
                            wordMatches[0].groupValues.getOrNull(1),
                            wordMatches[0].groupValues.getOrNull(2),
                            wordMatches[0].groupValues.getOrNull(3),
                            wordMatches[0].groupValues.getOrNull(4)
                        )
                        words.add(LyricWord(timeMs = totalMs, text = "$leading ", endMs = firstWordTime))
                    }
                }
            }
            for (i in wordMatches.indices) {
                val wm = wordMatches[i]
                val wTime = parseLrcTimestamp(
                    wm.groupValues.getOrNull(1),
                    wm.groupValues.getOrNull(2),
                    wm.groupValues.getOrNull(3),
                    wm.groupValues.getOrNull(4)
                )
                val wText = wm.groupValues.getOrNull(5) ?: ""
                val nextWTime = if (i + 1 < wordMatches.size) {
                    parseLrcTimestamp(
                        wordMatches[i + 1].groupValues.getOrNull(1),
                        wordMatches[i + 1].groupValues.getOrNull(2),
                        wordMatches[i + 1].groupValues.getOrNull(3),
                        wordMatches[i + 1].groupValues.getOrNull(4)
                    )
                } else {
                    wTime + 800L
                }
                if (wText.isNotEmpty()) {
                    words.add(LyricWord(timeMs = wTime, text = wText, endMs = nextWTime))
                }
            }

            lines.add(
                LyricLine(
                    timeMs = totalMs,
                    text = cleanText,
                    words = words,
                    alignment = if (isRtl) LyricAlignment.END else LyricAlignment.START
                )
            )
        }
    }

    return lines.sortedBy { it.timeMs }.withBackgroundVocals().withInstrumentalGaps()
}

fun stripLrcTimestamps(raw: String): String {
    val cleanRaw = raw.replace("﻿", "")
    if (cleanRaw.contains(Regex("""<tt(?:\s|>)""", RegexOption.IGNORE_CASE)) ||
        cleanRaw.contains("http://www.w3.org/ns/ttml", ignoreCase = true)) {
        val ttml = TtmlLyrics.parse(cleanRaw)
        if (ttml.isNotEmpty()) return ttml.joinToString("\n") { it.text }
    }
    return cleanRaw.lines()
        .map { it.trim() }
        .map { it.replace(metaHeaderRegex, "").replace(lrcTimestampRegex, "").replace(inlineTimeRegex, "").removePrefix("<R>").trim() }
        .filter { it.isNotEmpty() }
        .joinToString("\n")
}

@Serializable
data class LyricsResult(
    val lyrics: String = "",
    val source: String = "Blexsy",
    val cached: Boolean = false
) {
    val parsedLines: List<LyricLine>
        get() = parseLrc(lyrics)

    val isSynced: Boolean
        get() = parsedLines.isNotEmpty()

    val hasWordSync: Boolean
        get() = parsedLines.any { it.hasWordSync }

    val displaySource: String
        get() {
            val s = source.lowercase()
            return when {
                "lyricsplus" in s -> "LyricsPlus"
                "bini" in s -> "BiniLyrics"
                "better" in s -> "BetterLyrics"
                "unison" in s -> "Unison"
                "simp" in s -> "SimpMusic"
                "kugou" in s -> "KuGou"
                "lrc" in s -> "lrc.red"
                "lrclib" in s -> "LRCLIB"
                "musixmatch" in s -> "Musixmatch"
                "genius" in s -> "Genius"
                "ovh" in s -> "lyrics.ovh"
                else -> source.ifBlank { "Blexsy" }
            }
        }
}

data class LyricTranslation(
    val lines: Map<Int, String> = emptyMap(),
    val isOriginalTarget: Boolean = false
)

/**
 * Menerjemahkan lirik lagu ke bahasa target (default: Bahasa Indonesia) via endpoint Google Translate.
 * Otomatis mendeteksi bahasa asli; jika lirik aslinya sudah Bahasa Indonesia, tidak akan diterjemahkan.
 */
suspend fun translateLyrics(
    lines: List<LyricLine>,
    targetLang: String = "id"
): LyricTranslation = withContext(Dispatchers.IO) {
    if (lines.isEmpty()) return@withContext LyricTranslation()

    val indexedLines = lines.mapIndexed { idx, line -> idx to line.text.trim() }
        .filter { it.second.isNotBlank() }

    if (indexedLines.isEmpty()) return@withContext LyricTranslation()

    val resultMap = mutableMapOf<Int, String>()
    var isOriginalTargetLang = false

    // Chunk 35 baris per request agar tidak melebihi batas panjang URL GET
    for (chunk in indexedLines.chunked(35)) {
        try {
            val payload = chunk.joinToString("\n") { it.second }
            val encoded = java.net.URLEncoder.encode(payload, "UTF-8")
            val urlString = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=$targetLang&dt=t&q=$encoded"

            val conn = (java.net.URL(urlString).openConnection() as java.net.HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("User-Agent", "Mozilla/5.0")
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            val json = Json.parseToJsonElement(response).jsonArray

            // Deteksi bahasa asal dari Google Translate (element index 2)
            val detectedLang = try {
                json.getOrNull(2)?.jsonPrimitive?.content?.lowercase()
            } catch (_: Exception) { null }

            val targetLower = targetLang.lowercase()
            if (detectedLang == "id" || detectedLang == "in" || detectedLang == targetLower) {
                isOriginalTargetLang = true
                return@withContext LyricTranslation(lines = emptyMap(), isOriginalTarget = true)
            }

            val sentences = json[0].jsonArray
            val fullText = StringBuilder()
            for (el in sentences) {
                fullText.append(el.jsonArray[0].jsonPrimitive.content)
            }

            val translatedRows = fullText.toString().lines()
            var identicalCount = 0
            chunk.forEachIndexed { i, (origIdx, origText) ->
                val trans = translatedRows.getOrNull(i)?.trim()
                if (!trans.isNullOrBlank()) {
                    if (trans.equals(origText, ignoreCase = true)) {
                        identicalCount++
                    }
                    resultMap[origIdx] = trans
                }
            }

            // Jika >75% baris sama persis, berarti lirik aslinya sudah dalam bahasa target
            if (chunk.isNotEmpty() && (identicalCount.toFloat() / chunk.size) >= 0.75f) {
                return@withContext LyricTranslation(lines = emptyMap(), isOriginalTarget = true)
            }
        } catch (_: Exception) {}
    }

    LyricTranslation(lines = resultMap, isOriginalTarget = isOriginalTargetLang)
}
