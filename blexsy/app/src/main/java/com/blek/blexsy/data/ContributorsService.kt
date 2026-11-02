package com.blek.blexsy.data

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ContributorItem(
    val name: String,
    val role: String,
    @SerialName("avatar_url") val avatarUrl: String = "",
    @SerialName("github_url") val githubUrl: String = ""
)

@Serializable
data class CreditItem(
    val name: String,
    val role: String,
    val url: String = ""
)

@Serializable
data class ContributorsData(
    val contributors: List<ContributorItem> = emptyList(),
    val credits: List<CreditItem> = emptyList()
)

/**
 * Fetches dynamic contributors and credits from remote GitHub JSON.
 * ponytail: memory + SharedPreferences caching with hardcoded fallback; upgrade to room database if dynamic profiles grow.
 */
object ContributorsService {
    var remoteUrl: String = "https://github.com/ariblekk/soundpulse/blob/main/contributors.json"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy { HttpClient(CIO) }
    private var memoryCache: ContributorsData? = null

    val fallback = ContributorsData(
        contributors = listOf(
            ContributorItem(
                name = "ariblekk",
                role = "Creator & Lead Dev",
                avatarUrl = "https://github.com/ariblekk.png",
                githubUrl = "https://github.com/ariblekk"
            ),
            ContributorItem(
                name = "Romy Jatmiko",
                role = "Bug Hunter & Auth Middleware",
                avatarUrl = "https://api.dicebear.com/7.x/identicon/svg?seed=Romy%20Jatmiko",
                githubUrl = "https://github.com/romyjatmiko"
            ),
            ContributorItem(
                name = "Ridwan Saidi",
                role = "Bug Hunter & Audio Lifecycle",
                avatarUrl = "https://api.dicebear.com/7.x/identicon/svg?seed=Ridwan%20Saidi",
                githubUrl = "https://github.com/ridwansaidi"
            )
        ),
        credits = listOf(
            CreditItem("AndroidX Media3", "Audio Playback Engine", "https://github.com/androidx/media"),
            CreditItem("YouTube InnerTube", "Audio Stream & Metadata", "https://github.com"),
            CreditItem("Coil 3", "Image Loading & Cache", "https://github.com/coil-kt/coil"),
            CreditItem("Material3 Expressive", "UI Primitives & Motion", "https://m3.material.io")
        )
    )

    fun normalizeRawGithubUrl(url: String): String {
        val clean = url.trim()
        return if (clean.startsWith("https://github.com/") && clean.contains("/blob/")) {
            clean.replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("/blob/", "/")
        } else {
            clean
        }
    }

    suspend fun getContributors(context: Context? = null): ContributorsData = withContext(Dispatchers.IO) {
        memoryCache?.let { return@withContext it }

        val prefs = context?.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        val cachedRaw = prefs?.getString("cached_contributors_json", null)
        if (!cachedRaw.isNullOrBlank()) {
            runCatching {
                val parsed = json.decodeFromString<ContributorsData>(cachedRaw)
                if (parsed.contributors.isNotEmpty()) memoryCache = parsed
            }
        }

        val fetchUrl = normalizeRawGithubUrl(remoteUrl)
        val fresh = runCatching {
            val responseText: String = client.get(fetchUrl).body()
            val parsed = json.decodeFromString<ContributorsData>(responseText)
            if (parsed.contributors.isNotEmpty() || parsed.credits.isNotEmpty()) {
                prefs?.edit()?.putString("cached_contributors_json", responseText)?.apply()
                parsed
            } else null
        }.getOrNull()

        if (fresh != null) {
            memoryCache = fresh
            return@withContext fresh
        }

        memoryCache ?: fallback
    }
}
