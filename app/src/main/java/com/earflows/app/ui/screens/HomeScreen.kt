package com.earflows.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.earflows.app.service.ServiceState
import com.earflows.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToDebug: () -> Unit = {}
) {
    val serviceState by viewModel.serviceState.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()
    val isVadActive by viewModel.isVadActive.collectAsState()
    val latencyMs by viewModel.latencyMs.collectAsState()
    val engineName by viewModel.engineName.collectAsState()
    val useCloud by viewModel.useCloud.collectAsState()
    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val isReplyMode by viewModel.isReplyMode.collectAsState()

    val isRunning = serviceState == ServiceState.RUNNING

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "EarFlows",
                        fontWeight = FontWeight.Bold,
                        fontSize = 22.sp
                    )
                },
                actions = {
                    IconButton(onClick = onNavigateToDebug) {
                        Icon(Icons.Default.BugReport, contentDescription = "Debug")
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(32.dp))

            // Language display
            LanguagePairCard(sourceLang = displayLang(sourceLang), targetLang = displayLang(targetLang))

            Spacer(modifier = Modifier.height(40.dp))

            // Main action button
            MainActionButton(
                isRunning = isRunning,
                isVadActive = isVadActive,
                onClick = {
                    if (isRunning) viewModel.stopService() else viewModel.startService()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Audio routing indicators + controls (only when running)
            if (isRunning) {
                val useBtMic by viewModel.useBtMic.collectAsState()

                // Mic source toggle (independent)
                MicSourceToggle(
                    useBtMic = useBtMic,
                    onToggle = { viewModel.setUseBtMic(!useBtMic) }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Reply mode toggle
                AudioRoutingCard(
                    isReplyMode = isReplyMode,
                    onToggleReply = { viewModel.setReplyMode(!isReplyMode) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Status text
            Text(
                text = when {
                    serviceState == ServiceState.STOPPED -> "Appuyez pour commencer"
                    serviceState == ServiceState.STARTING -> "Initialisation..."
                    isReplyMode -> "Parlez en francais dans les ecouteurs"
                    serviceState == ServiceState.RUNNING && isPaused -> "En pause"
                    serviceState == ServiceState.RUNNING && isVadActive -> "Voix detectee — traduction..."
                    serviceState == ServiceState.RUNNING -> "Ecoute en cours..."
                    serviceState == ServiceState.STOPPING -> "Arret en cours..."
                    serviceState == ServiceState.ERROR -> "Erreur — verifiez les permissions"
                    else -> ""
                },
                style = MaterialTheme.typography.bodyLarge,
                color = if (isReplyMode) Color(0xFFFD79A8) else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Cloud toggle
            CloudToggleCard(
                useCloud = useCloud,
                isRunning = isRunning,
                engineName = engineName,
                onToggle = { viewModel.toggleCloudMode(it) }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Stats row
            if (isRunning) {
                StatsRow(latencyMs = latencyMs, isVadActive = isVadActive)
            }

            Spacer(modifier = Modifier.weight(1f))

            // Footer
            Text(
                text = "EarFlows v0.1.0",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 16.dp)
            )
        }
    }
}

@Composable
private fun MicSourceToggle(
    useBtMic: Boolean,
    onToggle: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Current mic icon
            Icon(
                imageVector = if (useBtMic) Icons.Default.Bluetooth else Icons.Default.Mic,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (useBtMic) Color(0xFF0984E3) else Color(0xFF00B894)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "Source micro",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    if (useBtMic) "Ecouteurs Bluetooth" else "Telephone",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            // Toggle switch
            Switch(
                checked = useBtMic,
                onCheckedChange = { onToggle() },
                thumbContent = {
                    Icon(
                        imageVector = if (useBtMic) Icons.Default.Bluetooth else Icons.Default.Mic,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                }
            )
        }
    }
}

@Composable
private fun AudioRoutingCard(
    isReplyMode: Boolean,
    onToggleReply: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isReplyMode)
                Color(0xFFFD79A8).copy(alpha = 0.15f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Input row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Mic source icon
                Icon(
                    imageVector = if (isReplyMode) Icons.Default.Bluetooth else Icons.Default.Mic,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isReplyMode) Color(0xFF0984E3) else Color(0xFF00B894)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Entree",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (isReplyMode) "Micro ecouteurs BT" else "Micro telephone",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                // Arrow
                Text("→", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                // Output icon
                Icon(
                    imageVector = if (isReplyMode) Icons.Default.PhoneAndroid else Icons.Default.Bluetooth,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = if (isReplyMode) Color(0xFFE17055) else Color(0xFF0984E3)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        "Sortie",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        if (isReplyMode) "Haut-parleur tel." else "Ecouteurs BT",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Toggle button
            androidx.compose.material3.FilledTonalButton(
                onClick = onToggleReply,
                modifier = Modifier.fillMaxWidth(),
                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (isReplyMode) Color(0xFFFD79A8).copy(alpha = 0.3f)
                    else MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isReplyMode) "Retour mode ambiant" else "Mode reponse (parler en francais)"
                )
            }
        }
    }
}

@Composable
private fun LanguagePairCard(sourceLang: String, targetLang: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = sourceLang,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "  →  ",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = targetLang,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun MainActionButton(
    isRunning: Boolean,
    isVadActive: Boolean,
    onClick: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isRunning && isVadActive) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val buttonColor by animateColorAsState(
        targetValue = when {
            isRunning && isVadActive -> Color(0xFF00B894) // Green when detecting speech
            isRunning -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.surfaceVariant
        },
        label = "buttonColor"
    )

    val borderColor by animateColorAsState(
        targetValue = when {
            isRunning && isVadActive -> Color(0xFF00B894)
            isRunning -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outline
        },
        label = "borderColor"
    )

    Box(
        modifier = Modifier
            .size(160.dp)
            .scale(if (isRunning) pulseScale else 1f)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        buttonColor.copy(alpha = 0.3f),
                        buttonColor.copy(alpha = 0.1f)
                    )
                )
            )
            .border(3.dp, borderColor, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(120.dp)
        ) {
            Icon(
                imageVector = if (isRunning) Icons.Default.Stop else Icons.Default.Hearing,
                contentDescription = if (isRunning) "Stop" else "Start",
                modifier = Modifier.size(56.dp),
                tint = if (isRunning) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun CloudToggleCard(
    useCloud: Boolean,
    isRunning: Boolean,
    engineName: String,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (useCloud) Icons.Default.Cloud else Icons.Default.CloudOff,
                        contentDescription = null,
                        tint = if (useCloud) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (useCloud) "Cloud (OpenRouter)" else "Local (offline)",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Switch(
                    checked = useCloud,
                    onCheckedChange = onToggle,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = engineName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
        }
    }
}

@Composable
private fun StatsRow(latencyMs: Long, isVadActive: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        StatChip(
            icon = { Icon(Icons.Default.Mic, null, modifier = Modifier.size(14.dp)) },
            label = "VAD",
            value = if (isVadActive) "Voix" else "Silence",
            valueColor = if (isVadActive) Color(0xFF00B894) else MaterialTheme.colorScheme.onSurfaceVariant
        )
        StatChip(
            icon = null,
            label = "Latence",
            value = "${latencyMs}ms",
            valueColor = when {
                latencyMs < 500 -> Color(0xFF00B894)
                latencyMs < 2000 -> Color(0xFFFDCB6E)
                else -> Color(0xFFE17055)
            }
        )
    }
}

@Composable
private fun StatChip(
    icon: (@Composable () -> Unit)?,
    label: String,
    value: String,
    valueColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.invoke()
            if (icon != null) Spacer(modifier = Modifier.width(6.dp))
            Text(text = "$label: ", style = MaterialTheme.typography.bodySmall)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = valueColor
            )
        }
    }
}

private fun displayLang(code: String): String = when (code) {
    "tha" -> "Thai"
    "fra" -> "Francais"
    "eng" -> "English"
    "cmn" -> "Mandarin"
    "spa" -> "Espanol"
    "deu" -> "Deutsch"
    "jpn" -> "Japanese"
    "kor" -> "Korean"
    "vie" -> "Vietnamese"
    else -> code
}
