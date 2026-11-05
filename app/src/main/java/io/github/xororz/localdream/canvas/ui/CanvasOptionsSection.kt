package io.github.xororz.localdream.canvas.ui

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.platform.LocalResources
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import io.github.xororz.localdream.BuildConfig
import io.github.xororz.localdream.R
import io.github.xororz.localdream.canvas.CanvasOptions
import io.github.xororz.localdream.utils.PromptMetadataSetting
import io.github.xororz.localdream.utils.UpdateChecker
import kotlinx.coroutines.launch

/**
 * Settings section holding every option this fork adds, kept apart from
 * upstream's settings. ModelListScreen calls it once, at the top.
 */
@Composable
fun LocalDreamCanvasOptionsSection() {
    val context = LocalContext.current
    val prefs = remember { CanvasOptions.prefs(context) }
    val scope = rememberCoroutineScope()
    val resources = LocalResources.current

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Brush,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text(stringResource(R.string.canvas_options_title), style = MaterialTheme.typography.titleMedium)
        }
        Text(
            stringResource(R.string.canvas_options_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            // Check for updates
            var checking by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = !checking) {
                        checking = true
                        scope.launch {
                            val result = UpdateChecker.check()
                            checking = false
                            when (result) {
                                is UpdateChecker.Result.NewVersion -> {
                                    Toast.makeText(context, resources.getString(R.string.canvas_update_found, result.name), Toast.LENGTH_SHORT).show()
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(result.url)))
                                    }
                                }
                                UpdateChecker.Result.UpToDate ->
                                    Toast.makeText(context, R.string.canvas_update_latest, Toast.LENGTH_SHORT).show()
                                UpdateChecker.Result.Failed ->
                                    Toast.makeText(context, R.string.canvas_update_failed, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.canvas_update_check), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(R.string.canvas_update_check_hint, BuildConfig.VERSION_NAME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (checking) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Divider()

            var savePromptMetadata by remember { mutableStateOf(PromptMetadataSetting.enabled) }
            OptionSwitch(
                title = stringResource(R.string.canvas_opt_metadata),
                description = stringResource(R.string.canvas_opt_metadata_hint),
                checked = savePromptMetadata,
            ) {
                savePromptMetadata = it
                PromptMetadataSetting.set(context, it)
            }
            Divider()

            var longPress by remember {
                mutableStateOf(prefs.getBoolean(CanvasOptions.KEY_EYEDROPPER_LONG_PRESS, CanvasOptions.DEFAULT_LONG_PRESS))
            }
            OptionSwitch(
                title = stringResource(R.string.canvas_opt_long_press),
                description = stringResource(R.string.canvas_opt_long_press_hint),
                checked = longPress,
            ) {
                longPress = it
                prefs.edit { putBoolean(CanvasOptions.KEY_EYEDROPPER_LONG_PRESS, it) }
            }
            Divider()

            var loupe by remember {
                mutableStateOf(prefs.getBoolean(CanvasOptions.KEY_EYEDROPPER_LOUPE, CanvasOptions.DEFAULT_LOUPE))
            }
            OptionSwitch(
                title = stringResource(R.string.canvas_opt_loupe),
                description = stringResource(R.string.canvas_opt_loupe_hint),
                checked = loupe,
            ) {
                loupe = it
                prefs.edit { putBoolean(CanvasOptions.KEY_EYEDROPPER_LOUPE, it) }
            }
            if (loupe) {
                var zoom by remember {
                    mutableIntStateOf(prefs.getInt(CanvasOptions.KEY_LOUPE_ZOOM, CanvasOptions.DEFAULT_LOUPE_ZOOM))
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        stringResource(R.string.canvas_opt_loupe_zoom),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    CanvasOptions.LOUPE_ZOOMS.forEach { z ->
                        FilterChip(
                            selected = zoom == z,
                            onClick = {
                                zoom = z
                                prefs.edit { putInt(CanvasOptions.KEY_LOUPE_ZOOM, z) }
                            },
                            label = { Text("${z}×") },
                        )
                    }
                }
            }
        }
        // Also a fork setting; it keeps its own card.
        Spacer(Modifier.height(24.dp))
        io.github.xororz.localdream.ui.screens.BackendPortSection()
    }
}

@Composable
private fun Divider() = HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

@Composable
private fun OptionSwitch(title: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
