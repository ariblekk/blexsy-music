package com.blek.blexsy.data

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File

object AudioCacheManager {
    @Volatile
    private var simpleCache: SimpleCache? = null

    private const val PREFS_NAME = "blexsy_prefs"
    private const val KEY_CACHE_LIMIT_GB = "audio_cache_limit_gb"
    private const val KEY_AUDIO_QUALITY = "audio_quality"
    const val DEFAULT_CACHE_LIMIT_GB = 2

    fun getCacheLimitGb(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_CACHE_LIMIT_GB, DEFAULT_CACHE_LIMIT_GB)
    }

    fun setCacheLimitGb(context: Context, gb: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_CACHE_LIMIT_GB, gb).apply()
    }

    fun getAudioQuality(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_AUDIO_QUALITY, "high") ?: "high"
    }

    fun setAudioQuality(context: Context, quality: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_AUDIO_QUALITY, quality).apply()
    }

    fun getMaxBitrateForQuality(quality: String): Int {
        return when (quality.lowercase()) {
            "low" -> 96_000
            "normal" -> 160_000
            else -> Int.MAX_VALUE
        }
    }

    fun getCache(context: Context): SimpleCache {
        return simpleCache ?: synchronized(this) {
            simpleCache ?: run {
                val limitGb = getCacheLimitGb(context)
                val maxBytes = limitGb.toLong() * 1024L * 1024L * 1024L
                val cacheDir = File(context.cacheDir, "audio_cache")
                val databaseProvider = StandaloneDatabaseProvider(context)
                val evictor = LeastRecentlyUsedCacheEvictor(maxBytes)
                SimpleCache(cacheDir, evictor, databaseProvider).also {
                    simpleCache = it
                }
            }
        }
    }

    fun getCurrentCacheSizeBytes(context: Context): Long {
        val cache = simpleCache
        return if (cache != null) {
            cache.cacheSpace
        } else {
            val cacheDir = File(context.cacheDir, "audio_cache")
            if (cacheDir.exists()) cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
        }
    }

    fun clearCache(context: Context) {
        synchronized(this) {
            val cache = simpleCache
            if (cache != null) {
                for (key in cache.keys) {
                    cache.removeResource(key)
                }
            } else {
                val cacheDir = File(context.cacheDir, "audio_cache")
                cacheDir.deleteRecursively()
            }
        }
    }

    fun formatBytes(bytes: Long): String {
        return when {
            bytes <= 0L -> "0 MB"
            bytes < 1024L * 1024L -> "< 1 MB"
            bytes < 1024L * 1024L * 1024L -> {
                val mb = bytes.toDouble() / (1024.0 * 1024.0)
                String.format(java.util.Locale.US, "%.1f MB", mb)
            }
            else -> {
                val gb = bytes.toDouble() / (1024.0 * 1024.0 * 1024.0)
                String.format(java.util.Locale.US, "%.2f GB", gb)
            }
        }
    }
}
