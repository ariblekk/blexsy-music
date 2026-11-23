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
    var remoteUrl: String = "https://raw.githubusercontent.com/ariblekk/blexsy-music/refs/heads/main/contributors.json"

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
                avatarUrl = "https://instagram.fcgk18-1.fna.fbcdn.net/v/t51.82787-19/724242684_18128506642621521_6819034126101877277_n.jpg?stp=dst-jpg_s150x150_tt6&_nc_cat=100&_nc_map=urlgen_bucketless&ccb=7-5&_nc_sid=f7ccc5&efg=eyJ2ZW5jb2RlX3RhZyI6InByb2ZpbGVfcGljLnd3dy4xMDgwLkMzIn0%3D&_nc_ohc=T7tI0leWoLcQ7kNvwEcVDNc&_nc_oc=Adr1ErUR0PD2B1v269DwUgwimHVkbkEuPjZvqbl5NG5KtxZOa3mbWweHwnG0n-IClJg&_nc_zt=24&_nc_ht=instagram.fcgk18-1.fna&_nc_gid=HPEk4d2S4x45exuvwYMiRQ&_nc_ss=7b6a8&oh=00_AQRaW8oNMidSQ4J1ZAq_7M20gzXF6dpxsXljqQ4IJ9GRqA&oe=6AEA5D47",
                githubUrl = "https://www.instagram.com/jim_0r/"
            ),
            ContributorItem(
                name = "Ridwan Saidi",
                role = "Bug Hunter & Audio Lifecycle",
                avatarUrl = "https://instagram.fcgk18-1.fna.fbcdn.net/v/t51.82787-19/750355989_18442402204190046_4128534047951000039_n.jpg?stp=dst-jpg_s150x150_tt6&_nc_cat=109&_nc_map=urlgen_bucketless&ccb=7-5&_nc_sid=f7ccc5&efg=eyJ2ZW5jb2RlX3RhZyI6InByb2ZpbGVfcGljLnd3dy4xMDgwLkMzIn0%3D&_nc_ohc=V01po6mGf0MQ7kNvwH7BpLq&_nc_oc=Adq6R8T8S4h_tzpzQ3ke6A_dIFA0FLHeoiPSxtUuvhiD6iCCzFUnON57t6X-60pesLM&_nc_zt=24&_nc_ht=instagram.fcgk18-1.fna&_nc_gid=ONfP9581SUcyqelR2KB5mA&_nc_ss=7b6a8&oh=00_AQTMCuGD-am-fPqOXoHrIr_8_9gRR69kjxxMxmlhy59Xjw&oe=6AEA3730",
                githubUrl = "https://www.instagram.com/rdwn124_"
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
