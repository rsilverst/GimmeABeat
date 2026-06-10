package com.rsilverst.gimmeabeat

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rsilverst.gimmeabeat.ui.HomeScreen
import com.rsilverst.gimmeabeat.ui.SettingsScreen
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
        viewModel.startAuto()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GimmeABeatTheme {
                AppRoot(
                    viewModel = viewModel,
                    onConnectSpotify = {
                        authLauncher.launch(viewModel.getAuthorizationIntent())
                    },
                    onStartAuto = { requestNotificationsThenStart() },
                )
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

private sealed interface Screen {
    data object Home : Screen
    data object Settings : Screen
}

@Composable
private fun AppRoot(
    viewModel: MainViewModel,
    onConnectSpotify: () -> Unit,
    onStartAuto: () -> Unit,
) {
    var screen by rememberSaveable(stateSaver = ScreenSaver) {
        mutableStateOf<Screen>(Screen.Home)
    }

    val heartRate by HeartRateRelay.heartRate.collectAsStateWithLifecycle()
    val smoothedHr by HeartRateRelay.smoothedBpm.collectAsStateWithLifecycle()
    val isAuthorized by viewModel.isAuthorized.collectAsStateWithLifecycle()
    val user by viewModel.user.collectAsStateWithLifecycle()
    val multiplier by viewModel.multiplier.collectAsStateWithLifecycle()
    val selectedGenre by viewModel.selectedGenre.collectAsStateWithLifecycle()
    val autoActive by viewModel.autoActive.collectAsStateWithLifecycle()
    val autoStatus by viewModel.autoStatus.collectAsStateWithLifecycle()
    val nowPlaying by viewModel.nowPlaying.collectAsStateWithLifecycle()
    val targetBpm by viewModel.targetBpm.collectAsStateWithLifecycle()
    val manualStatus by viewModel.status.collectAsStateWithLifecycle()

    val currentHrForUi = heartRate?.bpm
    val targetForUi: Int? = remember(smoothedHr, heartRate, multiplier) {
        val raw = smoothedHr ?: heartRate?.bpm ?: return@remember null
        (raw * multiplier).toInt()
    }

    BackHandler(enabled = screen is Screen.Settings) {
        screen = Screen.Home
    }

    AnimatedContent(
        targetState = screen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen",
    ) { current ->
        when (current) {
            Screen.Home -> HomeScreen(
                heartRate = currentHrForUi,
                targetBpm = targetForUi,
                isAuthorized = isAuthorized,
                autoActive = autoActive,
                nowPlaying = nowPlaying,
                autoStatus = autoStatus,
                onConnectSpotify = onConnectSpotify,
                onToggleAuto = {
                    if (autoActive) viewModel.stopAuto() else onStartAuto()
                },
                onOpenSettings = { screen = Screen.Settings },
                modifier = Modifier,
            )
            Screen.Settings -> SettingsScreen(
                userDisplayName = user?.display_name,
                userPlan = user?.product,
                selectedGenre = selectedGenre,
                multiplier = multiplier,
                targetBpm = targetBpm,
                heartRate = currentHrForUi,
                manualStatus = manualStatus,
                onSetGenre = viewModel::setGenre,
                onSetMultiplier = viewModel::setMultiplier,
                onSetTargetBpm = viewModel::setTargetBpm,
                onUseHrAsTarget = {
                    currentHrForUi?.let { viewModel.setTargetBpm(it) }
                },
                onFindAndPlay = { viewModel.findAndPlayMatchingSong() },
                onSignOut = {
                    viewModel.signOut()
                    screen = Screen.Home
                },
                onBack = { screen = Screen.Home },
                modifier = Modifier,
            )
        }
    }
}

private val ScreenSaver = androidx.compose.runtime.saveable.Saver<Screen, String>(
    save = { if (it is Screen.Settings) "settings" else "home" },
    restore = { if (it == "settings") Screen.Settings else Screen.Home },
)
