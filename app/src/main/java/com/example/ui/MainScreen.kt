package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.BoostNetViewModel
import com.example.ui.theme.AmoledBackground
import com.example.ui.theme.DarkSurfaceVariant
import com.example.ui.theme.GoogleBlueOnContainer
import com.example.ui.theme.GoogleBluePrimary

@Composable
fun MainScreen(viewModel: BoostNetViewModel) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }
    var showPairingDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

    val donorState by viewModel.donorState.collectAsState()
    val donorConfig by viewModel.donorConfig.collectAsState()
    val telemetryStats by viewModel.telemetryStats.collectAsState()
    val speedHistory by viewModel.speedHistory.collectAsState()
    val logEntries by viewModel.logEntries.collectAsState()
    val sessionSecret by viewModel.sessionSecret.collectAsState()
    val selfTestResult by viewModel.selfTestResult.collectAsState()
    val isTesting by viewModel.isTesting.collectAsState()

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .background(AmoledBackground)
            .statusBarsPadding(),
        bottomBar = {
            NavigationBar(
                containerColor = DarkSurfaceVariant,
                modifier = Modifier.navigationBarsPadding()
            ) {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.CellTower, contentDescription = "Node") },
                    label = { Text("Relay Node") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = GoogleBluePrimary,
                        indicatorColor = GoogleBluePrimary,
                        unselectedIconColor = Color(0xFF9AA0A6),
                        unselectedTextColor = Color(0xFF9AA0A6)
                    )
                )

                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Terminal, contentDescription = "Diagnostics") },
                    label = { Text("Diagnostics") },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = Color.Black,
                        selectedTextColor = GoogleBluePrimary,
                        indicatorColor = GoogleBluePrimary,
                        unselectedIconColor = Color(0xFF9AA0A6),
                        unselectedTextColor = Color(0xFF9AA0A6)
                    )
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when (selectedTab) {
                0 -> HomeScreen(
                    state = donorState,
                    config = donorConfig,
                    stats = telemetryStats,
                    speedHistory = speedHistory,
                    onToggleService = { viewModel.toggleDonorService(context) },
                    onShowPairing = { showPairingDialog = true },
                    onShowSettings = { showSettingsSheet = true }
                )
                1 -> DiagnosticsScreen(
                    state = donorState,
                    logEntries = logEntries,
                    selfTestResult = selfTestResult,
                    isTesting = isTesting,
                    onRunSelfTest = { viewModel.runSelfTest() },
                    onClearLogs = { viewModel.clearLogs() }
                )
            }

            if (showPairingDialog) {
                PairingDialog(
                    config = donorConfig,
                    secret = sessionSecret,
                    onDismiss = { showPairingDialog = false },
                    onRegenerateSecret = { viewModel.regeneratePairingSecret() }
                )
            }

            if (showSettingsSheet) {
                SettingsSheet(
                    currentConfig = donorConfig,
                    onDismiss = { showSettingsSheet = false },
                    onSave = { updated -> viewModel.updateConfig(updated) }
                )
            }
        }
    }
}
