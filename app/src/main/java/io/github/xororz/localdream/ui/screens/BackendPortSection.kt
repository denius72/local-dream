package io.github.xororz.localdream.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.xororz.localdream.data.BackendPort
import io.github.xororz.localdream.service.BackendService

/** Settings: port of the local backend (8081 by default, like upstream). */
@Composable
internal fun BackendPortSection() {
    val context = LocalContext.current
    var text by remember { mutableStateOf(BackendPort.port.toString()) }
    val value = text.toIntOrNull()
    val valid = value != null && BackendPort.isValid(value)
    val changed = valid && value != BackendPort.port

    fun apply(port: Int) {
        BackendPort.set(context, port)
        text = port.toString()
        // A running backend still listens on the old port: stop it so the
        // next model load starts it on the new one.
        runCatching {
            context.startForegroundService(
                Intent(context, BackendService::class.java).setAction(BackendService.ACTION_STOP),
            )
        }
        Toast.makeText(context, "Backend port set to $port", Toast.LENGTH_SHORT).show()
    }

    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 12.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Lan,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Text("Local backend port", style = MaterialTheme.typography.titleMedium)
        }
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        ) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    "Port the image generation backend listens on (default ${BackendPort.DEFAULT}). " +
                        "If the original Local Dream is also installed and running, pick another " +
                        "port (e.g. 8091) so the two apps don't talk to each other's backend. " +
                        "Host mode for remote devices expects ${BackendPort.DEFAULT}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { t -> text = t.filter { it.isDigit() }.take(5) },
                        singleLine = true,
                        isError = !valid,
                        label = { Text("Port") },
                        supportingText = if (!valid) ({ Text("1024–65535") }) else null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = { value?.let(::apply) }, enabled = changed) { Text("Apply") }
                    if (BackendPort.port != BackendPort.DEFAULT) {
                        TextButton(onClick = { apply(BackendPort.DEFAULT) }) { Text("Default") }
                    }
                }
            }
        }
    }
}
