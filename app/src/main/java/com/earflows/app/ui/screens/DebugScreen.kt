package com.earflows.app.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.earflows.app.service.ServiceState
import com.earflows.app.translation.RealtimeTranslationEngine
import com.earflows.app.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onNavigateToTests: () -> Unit = {}
) {
    val serviceState by viewModel.serviceState.collectAsState()
    val debugState by viewModel.debugState.collectAsState()

    // Ensure we're bound to the service to receive debug updates
    androidx.compose.runtime.LaunchedEffect(Unit) {
        viewModel.ensureBound()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.BugReport, null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Pipeline Debug")
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            // Service state
            StatusBadge(
                label = "Service",
                value = serviceState.name,
                color = when (serviceState) {
                    ServiceState.RUNNING -> Color(0xFF00B894)
                    ServiceState.STARTING -> Color(0xFFFDCB6E)
                    ServiceState.ERROR -> Color(0xFFE17055)
                    else -> Color.Gray
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // ========== PIPELINE STAGES ==========

            // Stage 1: MICROPHONE / ASR
            PipelineStage(
                icon = Icons.Default.Mic,
                title = "1. Micro → ASR",
                status = debugState.asrStatus,
                statusColor = when (debugState.asrStatus) {
                    "SPEECH" -> Color(0xFF00B894)
                    "LISTENING" -> Color(0xFF0984E3)
                    "PROCESSING" -> Color(0xFFFDCB6E)
                    "RESTARTING" -> Color(0xFFE17055)
                    else -> Color.Gray
                }
            ) {
                // Audio level meter
                Text("Niveau micro:", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(4.dp))
                val normalizedRms = ((debugState.asrRmsDb + 2f) / 12f).coerceIn(0f, 1f)
                val animatedRms by animateFloatAsState(normalizedRms, label = "rms")
                LinearProgressIndicator(
                    progress = { animatedRms },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = when {
                        normalizedRms > 0.7f -> Color(0xFF00B894)
                        normalizedRms > 0.3f -> Color(0xFFFDCB6E)
                        else -> Color(0xFFE17055)
                    },
                )
                Text(
                    "RMS: ${"%.1f".format(debugState.asrRmsDb)} dB",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stage 2: SOURCE TEXT
            PipelineStage(
                icon = Icons.Default.RecordVoiceOver,
                title = "2. Texte reconnu (source)",
                status = if (debugState.lastSourceText.isNotBlank()) "OK" else "---",
                statusColor = if (debugState.lastSourceText.isNotBlank()) Color(0xFF00B894) else Color.Gray
            ) {
                Text(
                    text = debugState.lastSourceText.ifBlank { "(en attente de parole...)" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Default,
                    color = if (debugState.lastSourceText.isNotBlank())
                        MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stage 3: TRANSLATION
            PipelineStage(
                icon = Icons.Default.Translate,
                title = "3. Traduction",
                status = if (debugState.translateLatencyMs > 0) "${debugState.translateLatencyMs}ms" else "---",
                statusColor = when {
                    debugState.translateLatencyMs in 1..500 -> Color(0xFF00B894)
                    debugState.translateLatencyMs in 501..2000 -> Color(0xFFFDCB6E)
                    debugState.translateLatencyMs > 2000 -> Color(0xFFE17055)
                    else -> Color.Gray
                }
            ) {
                Text(
                    text = debugState.lastTranslatedText.ifBlank { "(en attente de traduction...)" },
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = if (debugState.lastTranslatedText.isNotBlank())
                        MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stage 4: TTS
            PipelineStage(
                icon = Icons.Default.RecordVoiceOver,
                title = "4. Synthese vocale (TTS)",
                status = debugState.ttsStatus,
                statusColor = when (debugState.ttsStatus) {
                    "SPEAKING" -> Color(0xFF00B894)
                    "OFF" -> Color.Gray
                    else -> Color(0xFFFDCB6E)
                }
            ) {}

            Spacer(modifier = Modifier.height(24.dp))

            // ========== STATS ==========
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Metriques de reussite", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    StatRow("Micro source", debugState.micSource)
                    StatRow("Capture active", if (debugState.isCapturing) "OUI" else "NON")
                    StatRow("Segments voix", "${debugState.totalSpeechSegments}")
                    StatRow("Appels API", "${debugState.totalApiCalls}")
                    StatRow("Traductions OK", "${debugState.totalTranslations}")
                    StatRow("Taux reussite", if (debugState.totalApiCalls > 0)
                        "${debugState.totalTranslations * 100 / debugState.totalApiCalls}%" else "---")
                    StatRow("First token", "${debugState.firstTokenLatencyMs}ms")
                    StatRow("Latence totale", "${debugState.translateLatencyMs}ms")
                    StatRow("Latence moyenne", "${debugState.avgLatencyMs}ms")
                    StatRow("Erreurs", "${debugState.totalErrors}")
                    StatRow("Derniere erreur", debugState.lastError.ifBlank { "aucune" })
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ========== TEST BUTTON ==========
            androidx.compose.material3.OutlinedButton(
                onClick = onNavigateToTests,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Lancer la batterie de tests")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // ========== IMPROVEMENT POINTS ==========
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Points d'amelioration", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    ImprovementItem("P0", "Whisper ONNX avec mel spectrogramme pour ASR offline")
                    ImprovementItem("P0", "Silero VAD fix (state tensor format)")
                    ImprovementItem("P1", "Enregistrement audio parallele (desactive)")
                    ImprovementItem("P1", "Sortie audio via AudioTrack (ecouteurs BT)")
                    ImprovementItem("P2", "Historique des sessions enregistrees")
                    ImprovementItem("P2", "Split-channel stereo")
                    ImprovementItem("P3", "SeamlessStreaming ONNX (speech-to-speech)")
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StatusBadge(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            val animatedColor by animateColorAsState(color, label = "statusColor")
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(animatedColor)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(value, fontWeight = FontWeight.Bold, color = animatedColor)
        }
    }
}

@Composable
private fun PipelineStage(
    icon: ImageVector,
    title: String,
    status: String,
    statusColor: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, modifier = Modifier.size(18.dp), tint = statusColor)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = statusColor
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun ImprovementItem(priority: String, text: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        val color = when (priority) {
            "P0" -> Color(0xFFE17055)
            "P1" -> Color(0xFFFDCB6E)
            "P2" -> Color(0xFF0984E3)
            else -> Color.Gray
        }
        Text(
            priority,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color,
            modifier = Modifier.width(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
