package com.rsilverst.gimmeabeat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rsilverst.gimmeabeat.ui.theme.GimmeABeatTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleAuthResult(result.data)
    }

    private val notificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Whether granted or not, kick off auto mode. The service runs either way;
        // a denied notification permission just means no status bar icon.
        viewModel.startAuto()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GimmeABeatTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    HomeScreen(
                        modifier = Modifier.padding(innerPadding),
                        viewModel = viewModel,
                        onConnectSpotify = {
                            authLauncher.launch(viewModel.getAuthorizationIntent())
                        },
                        onStartAuto = { requestNotificationsThenStart() },
                    )
                }
            }
        }
    }

    private fun requestNotificationsThenStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) viewModel.startAuto()
            else notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.startAuto()
        }
    }
}

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onConnectSpotify: () -> Unit,
    onStartAuto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val heartRate by HeartRateRelay.heartRate.collectAsStateWithLifecycle()
    val isAuthorized by viewModel.isAuthorized.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val targetBpm by viewModel.targetBpm.collectAsStateWithLifecycle()
    val selectedGenre by viewModel.selectedGenre.collectAsStateWithLifecycle()
    val multiplier by viewModel.multiplier.collectAsStateWithLifecycle()
    val autoActive by viewModel.autoActive.collectAsStateWithLifecycle()
    val autoStatus by viewModel.autoStatus.collectAsStateWithLifecycle()
    val autoNowPlaying by viewModel.autoNowPlaying.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // --- Heart rate ---
        Text("From watch", style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = heartRate?.bpm?.toString() ?: "—",
                fontSize = 72.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(" bpm", style = MaterialTheme.typography.bodyLarge)
        }
        if (heartRate == null) {
            Text(
                "Waiting for heart rate from the watch…",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        HorizontalDivider()

        // --- Spotify connection ---
        if (!isAuthorized) {
            Text("Spotify not connected", style = MaterialTheme.typography.titleMedium)
            Button(onClick = onConnectSpotify) { Text("Connect Spotify") }
        } else {
            Text(
                text = "Connected as ${user?.display_name ?: "…"}",
                style = MaterialTheme.typography.titleMedium,
            )
            user?.product?.let { Text("Plan: $it", style = MaterialTheme.typography.bodySmall) }
            OutlinedButton(onClick = { viewModel.signOut() }) {
                Text("Sign out of Spotify")
            }

            HorizontalDivider()

            // --- Settings ---
            Text("Settings", style = MaterialTheme.typography.titleMedium)
            GenreDropdown(
                selected = selectedGenre,
                onSelect = { viewModel.setGenre(it) },
            )
            Text(
                text = "Song BPM = HR × ${"%.1f".format(multiplier)}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = multiplier,
                onValueChange = { viewModel.setMultiplier((it * 10).toInt() / 10f) },
                valueRange = 0.5f..2.0f,
                steps = 14,
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = "1.0× matches HR. 2.0× is typical for running cadence.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()

            // --- Manual pick ---
            Text("Manual pick", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Target BPM: $targetBpm",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = targetBpm.toFloat(),
                onValueChange = { viewModel.setTargetBpm(it.toInt()) },
                valueRange = 60f..200f,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { viewModel.findAndPlayMatchingSong() }) {
                    Text("Find & play")
                }
                heartRate?.bpm?.let { bpm ->
                    OutlinedButton(onClick = { viewModel.setTargetBpm(bpm) }) {
                        Text("Use HR ($bpm)")
                    }
                }
            }
            nowPlaying?.let {
                Text("Now: $it", style = MaterialTheme.typography.bodyMedium)
            }
            status?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }

            HorizontalDivider()

            // --- Auto mode ---
            Text("Auto mode", style = MaterialTheme.typography.titleMedium)
            Text(
                text = "Continuously picks new songs matching live HR. Runs as a " +
                    "background service so it survives screen-off.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (autoActive) {
                Button(onClick = { viewModel.stopAuto() }) { Text("Stop auto") }
            } else {
                Button(onClick = onStartAuto) { Text("Start auto") }
            }
            autoNowPlaying?.let {
                Text("Auto-now: $it", style = MaterialTheme.typography.bodyMedium)
            }
            autoStatus?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }

        Spacer(Modifier.height(8.dp))
        TextButton(onClick = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://getsongbpm.com")))
        }) {
            Text("BPM data from GetSongBPM.com", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GenreDropdown(
    selected: String?,
    onSelect: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = selected ?: "Any genre",
            onValueChange = {},
            readOnly = true,
            label = { Text("Genre") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .menuAnchor(androidx.compose.material3.ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Any genre") },
                onClick = {
                    onSelect(null)
                    expanded = false
                },
            )
            SPOTIFY_GENRES.forEach { g ->
                DropdownMenuItem(
                    text = { Text(g) },
                    onClick = {
                        onSelect(g)
                        expanded = false
                    },
                )
            }
        }
    }
}
