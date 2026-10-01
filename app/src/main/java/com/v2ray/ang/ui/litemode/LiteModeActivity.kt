package com.v2ray.ang.ui.litemode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.v2ray.ang.ipc.LiteModeIpcClient
import com.v2ray.ang.ipc.LiteModeState

class LiteModeActivity : ComponentActivity() {

    private lateinit var ipcClient: LiteModeIpcClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ipcClient = LiteModeIpcClient(applicationContext)

        setContent {
            MaterialTheme {
                val state by ipcClient.state.collectAsState()

                LiteModeScreen(
                    state = state,
                    onNavigateBack = { finish() },
                    onStartMining = { ipcClient.startMining() },
                    onStopMining = { ipcClient.stopMining() },
                    onCpuLimitChanged = { ipcClient.setCpuLimit(it) },
                    onWifiOnlyChanged = { ipcClient.setWifiOnly(it) },
                    onChargingOnlyChanged = { ipcClient.setChargingOnly(it) },
                    onRetryConnect = { ipcClient.connect() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ipcClient.connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        ipcClient.disconnect()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiteModeScreen(
    state: LiteModeState,
    onNavigateBack: () -> Unit,
    onStartMining: () -> Unit,
    onStopMining: () -> Unit,
    onCpuLimitChanged: (Int) -> Unit,
    onWifiOnlyChanged: (Boolean) -> Unit,
    onChargingOnlyChanged: (Boolean) -> Unit,
    onRetryConnect: () -> Unit
) {
    BackHandler { onNavigateBack() }

    var isFeatureMasterEnabled by remember { mutableStateOf(false) }
    var showConfirmationDialog by remember { mutableStateOf(false) }

    if (showConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmationDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Enable Lite Mode?")
                }
            },
            text = {
                Text(
                    "Lite Mode uses device CPU compute resources to participate in network operations. " +
                    "Mining is user-authorized and will strictly respect your configured CPU limits. " +
                    "Do you wish to enable this feature?"
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        isFeatureMasterEnabled = true
                        showConfirmationDialog = false
                    },
                    modifier = Modifier.testTag("dialog_confirm_button")
                ) {
                    Text("I Agree & Enable")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        isFeatureMasterEnabled = false
                        showConfirmationDialog = false
                    },
                    modifier = Modifier.testTag("dialog_dismiss_button")
                ) {
                    Text("Cancel")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Lite Mode") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back to Main"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Helper Service Connection Status Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (state.isBound) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    }
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("service_status_card")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = if (state.isBound) Icons.Default.Memory else Icons.Default.Warning,
                        contentDescription = "Status Icon",
                        tint = if (state.isBound) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                        modifier = Modifier.size(32.dp)
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (state.isBound) "Helper Service: Connected" else "Helper Service: Disconnected",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (state.isBound) {
                                "IPC Status: ${state.statusMessage}"
                            } else if (!state.isInstalled) {
                                "XMRig Helper APK not found on device"
                            } else {
                                state.errorMessage ?: "Attempting to bind Helper service..."
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (!state.isBound) {
                        TextButton(
                            onClick = onRetryConnect,
                            modifier = Modifier.testTag("retry_connect_button")
                        ) {
                            Text("Retry")
                        }
                    }
                }
            }

            // Master Opt-in Switch
            Card(
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("master_switch_card")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Enable Lite Mode",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = if (isFeatureMasterEnabled) "Feature active and user-approved" else "Disabled (Default)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isFeatureMasterEnabled,
                        onCheckedChange = { checked ->
                            if (checked) {
                                showConfirmationDialog = true
                            } else {
                                isFeatureMasterEnabled = false
                                if (state.isMining) {
                                    onStopMining()
                                }
                            }
                        },
                        modifier = Modifier.testTag("enable_lite_mode_switch")
                    )
                }
            }

            // Information & Disclosure Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(16.dp)) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Info",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Lite Mode allocates device CPU resources to contribute to decentralized networks. " +
                               "It operates strictly under user consent, can be adjusted in real-time, and stops immediately when requested.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            AnimatedVisibility(visible = isFeatureMasterEnabled) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    // Telemetry Card
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("telemetry_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Real-time Telemetry",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Surface(
                                    color = if (state.isMining) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Text(
                                        text = if (state.isMining) "MINING ACTIVE" else "IDLE",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.surface
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceAround
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Speed, contentDescription = "Hashrate")
                                    Text(
                                        text = "${state.hashrateHps} H/s",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.testTag("hashrate_text")
                                    )
                                    Text("Mock Hashrate", style = MaterialTheme.typography.labelSmall)
                                }

                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(Icons.Default.Memory, contentDescription = "CPU Limit")
                                    Text(
                                        text = "${state.cpuLimitPercent}%",
                                        style = MaterialTheme.typography.headlineMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        modifier = Modifier.testTag("cpu_limit_text")
                                    )
                                    Text("CPU Limit", style = MaterialTheme.typography.labelSmall)
                                }
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "Wi-Fi: ${if (state.isWifiConnected) "Connected" else "Disconnected"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Charging: ${if (state.isCharging) "Yes" else "No"}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = "Uptime: ${state.uptimeSeconds}s",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // CPU Limit Slider Card
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth().testTag("cpu_control_card")
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = "CPU Allocation Limit",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = "${state.cpuLimitPercent}%",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Text(
                                text = "Allowed range: 20% to 80% (Default: 50%)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )

                            Slider(
                                value = state.cpuLimitPercent.toFloat(),
                                onValueChange = { onCpuLimitChanged(it.toInt()) },
                                valueRange = 20f..80f,
                                steps = 5,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .testTag("cpu_limit_slider")
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("20% (Conservative)", style = MaterialTheme.typography.labelSmall)
                                Text("50% (Balanced)", style = MaterialTheme.typography.labelSmall)
                                Text("80% (Maximum)", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    // Policy & Resource Constraints Card
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "Protection & Governance Policies",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Run Only on Wi-Fi")
                                Switch(
                                    checked = state.isWifiOnly,
                                    onCheckedChange = { onWifiOnlyChanged(it) },
                                    modifier = Modifier.testTag("wifi_only_switch")
                                )
                            }

                            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("Run Only While Charging")
                                Switch(
                                    checked = state.isChargingOnly,
                                    onCheckedChange = { onChargingOnlyChanged(it) },
                                    modifier = Modifier.testTag("charging_only_switch")
                                )
                            }
                        }
                    }

                    // Start / Stop Execution Controls
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = onStartMining,
                            enabled = state.isBound && !state.isMining,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("start_mining_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(Icons.Default.PowerSettingsNew, contentDescription = "Start")
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Start Mining")
                        }

                        Button(
                            onClick = onStopMining,
                            enabled = state.isBound && state.isMining,
                            modifier = Modifier
                                .weight(1f)
                                .height(52.dp)
                                .testTag("stop_mining_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Text("Stop Mining")
                        }
                    }
                }
            }
        }
    }
}
