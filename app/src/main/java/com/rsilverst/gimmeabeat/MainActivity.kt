package com.rsilverst.gimmeabeat

import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rsilverst.gimmeabeat.ui.theme.GimmeABeatTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels { MainViewModel.Factory }

    private val authLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.handleAuthResult(result.data)
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
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onConnectSpotify: () -> Unit,
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
            Spacer(Modifier.height(0.dp))
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

            // --- Pick a song matching BPM ---
            Text("Find a song", style = MaterialTheme.typography.titleMedium)
            GenreDropdown(
                selected = selectedGenre,
                onSelect = { viewModel.setGenre(it) },
            )

            Text(
                text = "Target BPM: $targetBpm",
                style = MaterialTheme.typography.bodyMedium,
            )
            Slider(
                value = targetBpm.toFloat(),
                onValueChange = { viewModel.setTargetBpm(it.toInt()) },
                valueRange = 60f..200f,
                steps = 0,
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
            OutlinedButton(onClick = { viewModel.playTestTrack() }) {
                Text("Play test track")
            }

            nowPlaying?.let {
                Text(
                    text = "Now: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        status?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium)
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
