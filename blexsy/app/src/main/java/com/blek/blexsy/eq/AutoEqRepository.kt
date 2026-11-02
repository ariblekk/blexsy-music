package com.blek.blexsy.eq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object AutoEqRepository {
    private const val ResultsBase = "https://raw.githubusercontent.com/jaakkopasanen/AutoEq/master/results"
    private const val IndexMaxAgeMs = 7L * 24 * 60 * 60 * 1000

    private val indexMutex = Mutex()
    @Volatile private var cachedModels: List<AutoEqModel>? = null

    private fun getDir(context: Context): File = File(context.filesDir, "autoeq").apply { mkdirs() }

    suspend fun models(context: Context, refresh: Boolean = false): List<AutoEqModel> = indexMutex.withLock {
        if (!refresh) cachedModels?.let { return it }
        withContext(Dispatchers.IO) {
            val dir = getDir(context)
            val file = File(dir, "INDEX.md")
            val stale = !file.exists() || System.currentTimeMillis() - file.lastModified() > IndexMaxAgeMs
            val text = if (refresh || stale) {
                runCatching { download("$ResultsBase/INDEX.md") }
                    .onSuccess { file.writeText(it) }
                    .getOrNull() ?: file.takeIf { it.exists() }?.readText()
                    ?: throw IOException("Couldn't reach the AutoEq database")
            } else {
                file.readText()
            }
            parseIndex(text).also { cachedModels = it }
        }
    }

    suspend fun measurement(context: Context, entry: AutoEqEntry): AutoEqMeasurement = withContext(Dispatchers.IO) {
        val dir = getDir(context)
        val folder = entry.path.substringAfterLast('/')
        val encodedPath = entry.path.split('/').joinToString("/") { urlEncode(it) }
        val base = "$ResultsBase/$encodedPath"
        val cacheKey = entry.path.hashCode().toUInt().toString(16)
        val csv = cached(dir, "$cacheKey.csv") { download("$base/${urlEncode("$folder.csv")}") }
        val txt = cached(dir, "$cacheKey.txt") { download("$base/${urlEncode("$folder ParametricEQ.txt")}") }
        parseMeasurement(entry, csv, txt)
    }

    private inline fun cached(dir: File, name: String, fetch: () -> String): String {
        val file = File(dir, name)
        if (file.exists() && file.length() > 0) return file.readText()
        return fetch().also { file.writeText(it) }
    }

    private fun download(urlString: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "Blexsy")
        }
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IOException("AutoEq $code for $urlString")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun urlEncode(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun search(all: List<AutoEqModel>, query: String): List<AutoEqModel> {
        val q = normalize(query)
        if (q.isBlank()) return all
        val words = q.split(' ').filter { it.isNotEmpty() }
        val compact = q.replace(" ", "")
        return all.filter { model ->
            words.all { model.searchKey.contains(it) } || model.compactKey.contains(compact)
        }.sortedBy { if (it.searchKey.startsWith(q) || it.compactKey.startsWith(compact)) 0 else 1 }
    }

    internal fun parseIndex(text: String): List<AutoEqModel> {
        val entries = ArrayList<AutoEqEntry>(9000)
        text.lineSequence().forEach { line ->
            if (!line.startsWith("- [")) return@forEach
            val nameEnd = line.indexOf("](./")
            if (nameEnd < 0) return@forEach
            val pathEnd = line.indexOf(") by ", nameEnd)
            if (pathEnd < 0) return@forEach
            val name = line.substring(3, nameEnd)
            val path = percentDecode(line.substring(nameEnd + 4, pathEnd))
            val by = line.substring(pathEnd + 5).trim()
            val on = by.indexOf(" on ")
            val source = if (on >= 0) by.substring(0, on) else by
            val rig = if (on >= 0) by.substring(on + 4) else null
            val rigFolder = path.split('/').getOrNull(1).orEmpty().lowercase()
            val form = when {
                "earbud" in rigFolder -> HeadphoneForm.Earbud
                "in-ear" in rigFolder -> HeadphoneForm.InEar
                else -> HeadphoneForm.OverEar
            }
            entries += AutoEqEntry(name, source, rig, path, form)
        }
        return entries
            .groupBy { it.name }
            .map { (name, list) -> AutoEqModel(name, list) }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }

    internal fun parseMeasurement(entry: AutoEqEntry, csv: String, txt: String): AutoEqMeasurement {
        val lines = csv.lineSequence().filter { it.isNotBlank() }.toList()
        val header = lines.first().split(',').map { it.trim().lowercase() }
        val fi = header.indexOf("frequency")
        val ri = header.indexOf("raw")
        val ti = header.indexOf("target")
        if (fi < 0 || ri < 0) throw IOException("Unreadable AutoEq measurement")
        val rows = lines.drop(1).mapNotNull { row ->
            val cells = row.split(',')
            val f = cells.getOrNull(fi)?.toDoubleOrNull() ?: return@mapNotNull null
            val r = cells.getOrNull(ri)?.toDoubleOrNull() ?: return@mapNotNull null
            val t = if (ti >= 0) cells.getOrNull(ti)?.toDoubleOrNull() ?: 0.0 else 0.0
            Triple(f, r, t)
        }
        val frequencies = DoubleArray(rows.size) { rows[it].first }
        val raw = DoubleArray(rows.size) { rows[it].second }
        val target = DoubleArray(rows.size) { rows[it].third }
        val preset = runCatching { ParametricEQParser.parseText(txt) }.getOrDefault(ParametricEQ(0.0, emptyList()))
        return AutoEqMeasurement(entry, frequencies, raw, target, preset)
    }
}

internal fun percentDecode(text: String): String {
    if ('%' !in text) return text
    val bytes = java.io.ByteArrayOutputStream(text.length)
    var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (ch == '%' && i + 2 <= text.lastIndex) {
            val hex = text.substring(i + 1, i + 3).toIntOrNull(16)
            if (hex != null) {
                bytes.write(hex)
                i += 3
                continue
            }
        }
        bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
        i++
    }
    return bytes.toString(Charsets.UTF_8.name())
}

internal fun normalize(text: String): String {
    val out = StringBuilder(text.length)
    var space = true
    for (ch in text) {
        if (ch.isLetterOrDigit()) {
            out.append(ch.lowercaseChar())
            space = false
        } else if (!space) {
            out.append(' ')
            space = true
        }
    }
    return out.trimEnd().toString()
}
