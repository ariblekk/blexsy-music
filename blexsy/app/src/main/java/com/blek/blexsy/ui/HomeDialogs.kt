package com.blek.blexsy.ui

import com.blek.blexsy.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Dialogs launched from the navigation drawer ("New playlist" and "Import from Spotify").
 * Kept in their own file so [HomeScreen] stays a thin shell.
 */

@Composable
internal fun CreatePlaylistDialog(
    name: String,
    onNameChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_create_crate_title), style = BlexsyTypography.SectionTitle.copy(fontSize = 19.sp)) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = onNameChange,
                placeholder = { Text(stringResource(R.string.dialog_create_crate_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                singleLine = true,
                colors = dialogFieldColors(),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val trimmed = name.trim()
                    if (trimmed.isNotBlank()) onCreate(trimmed)
                },
                shape = CircleShape
            ) {
                Text(stringResource(R.string.dialog_create_crate_confirm), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_create_crate_dismiss), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp)
    )
}

@Composable
internal fun ImportSpotifyDialog(
    input: String,
    onInputChange: (String) -> Unit,
    error: String?,
    isImporting: Boolean,
    onDismiss: () -> Unit,
    onImport: (String) -> Unit,
    onInvalidInput: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isImporting) onDismiss() },
        title = { Text(stringResource(R.string.dialog_port_crate_title), style = BlexsyTypography.SectionTitle.copy(fontSize = 19.sp)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.dialog_port_crate_desc),
                    style = BlexsyTypography.Caption,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = { Text(stringResource(R.string.dialog_port_crate_placeholder), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    singleLine = true,
                    enabled = !isImporting,
                    colors = dialogFieldColors(),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                error?.let {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = BlexsyTypography.Caption)
                }
            }
        },
        confirmButton = {
            FilledTonalButton(
                onClick = {
                    val playlistId = spotifyPlaylistId(input)
                    if (playlistId.isBlank()) {
                        onInvalidInput()
                    } else {
                        onImport(playlistId)
                    }
                },
                enabled = !isImporting,
                shape = CircleShape
            ) {
                if (isImporting) {
                    ExpressiveLoadingIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                } else {
                    Text(stringResource(R.string.dialog_port_crate_confirm), fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isImporting
            ) {
                Text(stringResource(R.string.dialog_port_crate_cancel), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(12.dp)
    )
}

/** Accepts a full open.spotify.com URL, a spotify: URI, or a bare playlist ID. */
internal fun spotifyPlaylistId(raw: String): String {
    val trimmed = raw.trim()
    return when {
        "open.spotify.com/playlist/" in trimmed ->
            trimmed.substringAfter("open.spotify.com/playlist/").substringBefore("?").substringBefore("/")
        trimmed.startsWith("spotify:playlist:") -> trimmed.removePrefix("spotify:playlist:")
        else -> trimmed
    }.trim()
}

@Composable
private fun dialogFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = Color.Transparent,
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary
)
