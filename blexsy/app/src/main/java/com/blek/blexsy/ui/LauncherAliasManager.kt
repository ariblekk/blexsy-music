package com.blek.blexsy.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.blek.blexsy.R

/**
 * Manages Android launcher activity aliases to enable switching the home screen app title.
 * ponytail: static activity-alias declarations in AndroidManifest; upgrade to dynamic launcher icons if custom icon packs are added.
 */
object LauncherAliasManager {
    data class AliasOption(
        val id: String,
        val label: String,
        val labelResId: Int,
        val aliasClass: String
    )

    val ALIASES = listOf(
        AliasOption("blexsy", "Blexsy", R.string.launcher_blexsy, "com.blek.blexsy.LauncherBlexsy"),
        AliasOption("spotify", "Spotify", R.string.launcher_spotify, "com.blek.blexsy.LauncherSpotify"),
        AliasOption("music", "Music", R.string.launcher_music, "com.blek.blexsy.LauncherMusic")
    )

    fun getCurrentAliasId(context: Context): String {
        val prefs = context.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
        val saved = prefs.getString("launcher_alias_id", "blexsy") ?: "blexsy"
        return if (ALIASES.any { it.id == saved }) saved else "blexsy"
    }

    fun setLauncherAlias(context: Context, targetAliasId: String) {
        val target = ALIASES.firstOrNull { it.id.equals(targetAliasId, ignoreCase = true) } ?: ALIASES.first()
        val pm = context.packageManager

        try {
            // Enable target first so at least one launcher entry remains active
            pm.setComponentEnabledSetting(
                ComponentName(context, target.aliasClass),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )

            // Disable all other aliases
            for (other in ALIASES) {
                if (other.id != target.id) {
                    pm.setComponentEnabledSetting(
                        ComponentName(context, other.aliasClass),
                        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                        PackageManager.DONT_KILL_APP
                    )
                }
            }

            context.getSharedPreferences("blexsy_prefs", Context.MODE_PRIVATE)
                .edit()
                .putString("launcher_alias_id", target.id)
                .apply()
        } catch (_: Throwable) {
            // Non-fatal if package manager restricted
        }
    }
}
