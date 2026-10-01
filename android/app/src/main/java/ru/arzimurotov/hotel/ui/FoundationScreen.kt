package ru.arzimurotov.hotel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.arzimurotov.hotel.BuildConfig
import ru.arzimurotov.hotel.R

private val Navy = Color(0xFF132F4C)
private val Gold = Color(0xFFDDA763)

@Composable
fun HotelTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Navy, secondary = Gold,
        background = Color(0xFFF4F6F9), surface = Color.White), content = content)
}

/** Stage-one diagnostic UI. No reservation action is presented as implemented. */
@Composable
fun FoundationScreen(state: FoundationState, onRefresh: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize().background(Navy).statusBarsPadding(),
        color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().background(Navy).padding(24.dp)) {
                Text(stringResource(R.string.app_name), color = Gold, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(20.dp))
                Text(stringResource(R.string.foundation_title), color = Color.White,
                    style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.foundation_subtitle), color = Color(0xFFD8E2ED))
            }
            Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = 680.dp).fillMaxWidth().padding(20.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(stringResource(R.string.foundation_stage), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val health = (state as? FoundationState.Ready)?.health
                        val message = when {
                            state is FoundationState.Loading -> R.string.connection_loading
                            state is FoundationState.Unavailable -> R.string.connection_error
                            health?.databaseConnected == false -> R.string.connection_database_error
                            else -> R.string.connection_ready
                        }
                        Text(stringResource(message), Modifier.testTag("connectionStatus"),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        if (state is FoundationState.Loading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        StatusRow(stringResource(R.string.connection_service), health?.serverConnected == true)
                        StatusRow(stringResource(R.string.connection_database), health?.databaseConnected == true)
                        if (health != null) Text(stringResource(R.string.connection_version, health.version))
                        if (state is FoundationState.Unavailable || health?.databaseConnected == false) {
                            Text(stringResource(R.string.connection_hint), style = MaterialTheme.typography.bodyMedium)
                        }
                        Button(onClick = onRefresh, enabled = state !is FoundationState.Loading,
                            modifier = Modifier.fillMaxWidth().testTag("checkConnection"), shape = RoundedCornerShape(12.dp)) {
                            Text(stringResource(R.string.connection_retry))
                        }
                    }
                }
                Text(stringResource(R.string.foundation_next), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.foundation_description), color = Color(0xFF526578))
                Text(stringResource(R.string.demo_notice), style = MaterialTheme.typography.bodySmall)
                if (BuildConfig.DEBUG) {
                    Text(stringResource(R.string.connection_api, BuildConfig.API_BASE_URL),
                        style = MaterialTheme.typography.labelSmall, color = Color(0xFF526578))
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, connected: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(end = 8.dp))
        Text(stringResource(if (connected) R.string.connection_connected else R.string.connection_waiting),
            color = if (connected) Color(0xFF26725C) else Color(0xFF526578))
    }
}
