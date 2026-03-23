package com.earflows.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.earflows.app.model.ModelDownloadManager
import com.earflows.app.ui.screens.HomeScreen
import com.earflows.app.ui.screens.ModelSetupScreen
import com.earflows.app.ui.screens.SettingsScreen
import com.earflows.app.ui.theme.EarFlowsTheme
import com.earflows.app.viewmodel.MainViewModel
import com.earflows.app.viewmodel.ModelSetupViewModel
import com.earflows.app.viewmodel.SettingsViewModel

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val settingsViewModel: SettingsViewModel by viewModels()
    private val modelSetupViewModel: ModelSetupViewModel by viewModels()

    private var permissionsGranted by mutableStateOf(false)

    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionsGranted = results.values.all { it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Check permissions
        permissionsGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (!permissionsGranted) {
            permissionLauncher.launch(requiredPermissions)
        }

        // Always start at home — model setup is available from settings
        setContent {
            EarFlowsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(navController = navController, startDestination = "home") {
                        composable("home") {
                            HomeScreen(
                                viewModel = mainViewModel,
                                onNavigateToSettings = { navController.navigate("settings") },
                                onNavigateToDebug = { navController.navigate("debug") }
                            )
                        }
                        composable("debug") {
                            com.earflows.app.ui.screens.DebugScreen(
                                viewModel = mainViewModel,
                                onBack = { navController.popBackStack() },
                                onNavigateToTests = { navController.navigate("tests") }
                            )
                        }
                        composable("tests") {
                            com.earflows.app.ui.screens.TestScreen(
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable("settings") {
                            SettingsScreen(
                                viewModel = settingsViewModel,
                                onBack = { navController.popBackStack() },
                                onNavigateToModelSetup = {
                                    navController.navigate("model_setup")
                                }
                            )
                        }
                        composable("model_setup") {
                            ModelSetupScreen(
                                viewModel = modelSetupViewModel,
                                onComplete = {
                                    // Refresh model state in settings and go back
                                    settingsViewModel.refreshModelState()
                                    navController.popBackStack()
                                },
                                onSkip = {
                                    navController.popBackStack()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        mainViewModel.bindToExistingService()
    }
}
