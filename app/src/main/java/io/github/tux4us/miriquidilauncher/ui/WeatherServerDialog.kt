// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.tux4us.miriquidilauncher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.tux4us.miriquidilauncher.R
import io.github.tux4us.miriquidilauncher.weather.ServerCheck
import io.github.tux4us.miriquidilauncher.weather.WeatherViewModel

/** Dialog „Eigener Wetterserver“: Adresse der Vorhersage, optional der Luftqualität, optional API-Schlüssel. */
@Composable
internal fun WeatherServerDialog(viewModel: WeatherViewModel, onDismiss: () -> Unit) {
    var forecast by remember { mutableStateOf("") }
    var air by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<ServerCheck?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        viewModel.loadServer { server ->
            forecast = server?.forecastBase.orEmpty()
            air = server?.airBase.orEmpty()
            key = server?.apiKey.orEmpty()
        }
    }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = WeatherText,
        unfocusedTextColor = WeatherText,
        cursorColor = WeatherText,
        focusedLabelColor = WeatherText,
        unfocusedLabelColor = WeatherTextMuted,
        focusedBorderColor = WeatherText,
        unfocusedBorderColor = Color(0x99FFFFFF),
    )
    val submit = {
        busy = true
        viewModel.saveServer(forecast, air, key) { result ->
            busy = false
            if (result == ServerCheck.OK) onDismiss() else error = result
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF141A22),
        titleContentColor = WeatherText,
        textContentColor = WeatherText,
        title = { Text(stringResource(R.string.weather_server_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.weather_server_hint), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
                OutlinedTextField(
                    value = forecast, onValueChange = { forecast = it; error = null }, singleLine = true,
                    label = { Text(stringResource(R.string.weather_server_forecast)) },
                    placeholder = { Text("https://wetter.example.org") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = air, onValueChange = { air = it; error = null }, singleLine = true,
                    label = { Text(stringResource(R.string.weather_server_air)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it; error = null }, singleLine = true,
                    label = { Text(stringResource(R.string.weather_server_key)) },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    colors = fieldColors, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.weather_server_privacy), style = MaterialTheme.typography.bodySmall, color = WeatherTextMuted)
                error?.let {
                    Text(
                        stringResource(
                            when (it) {
                                ServerCheck.FORECAST_NOT_HTTPS -> R.string.weather_server_error_forecast_https
                                ServerCheck.FORECAST_INVALID -> R.string.weather_server_error_forecast
                                ServerCheck.AIR_NOT_HTTPS -> R.string.weather_server_error_air_https
                                ServerCheck.AIR_INVALID -> R.string.weather_server_error_air
                                ServerCheck.KEY_INVALID -> R.string.weather_server_error_key
                                else -> R.string.weather_server_error_store
                            },
                        ),
                        color = Color(0xFFFF9E9E),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = submit, enabled = !busy, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.weather_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, colors = ButtonDefaults.textButtonColors(contentColor = WeatherText)) {
                Text(stringResource(R.string.weather_cancel))
            }
        },
    )
}
