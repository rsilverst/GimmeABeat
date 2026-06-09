package com.rsilverst.gimmeabeat.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.rsilverst.gimmeabeat.wear.ui.HeartRateScreen
import com.rsilverst.gimmeabeat.wear.ui.theme.GimmeABeatWearTheme

class MainActivity : ComponentActivity() {

    private val viewModel: HeartRateViewModel by viewModels { HeartRateViewModel.Factory }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        viewModel.onPermissionResult(results.values.all { it })
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            GimmeABeatWearTheme {
                HeartRateScreen(
                    viewModel = viewModel,
                    onRequestPermission = {
                        requestPermissions.launch(HeartRateViewModel.REQUIRED_PERMISSIONS)
                    },
                )
            }
        }
        viewModel.checkPermission(this)
    }
}
