package com.earflows.app.ui.screens

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.earflows.app.test.PipelineTestRunner
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TestScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var results by remember { mutableStateOf<List<PipelineTestRunner.TestResult>>(emptyList()) }
    var convResults by remember { mutableStateOf<List<com.earflows.app.test.ConversationModeTest.TestResult>>(emptyList()) }
    var isRunning by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pipeline Tests") },
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
            // Run button
            Button(
                onClick = {
                    isRunning = true
                    results = emptyList()
                    scope.launch {
                        val runner = PipelineTestRunner(context)
                        results = runner.runAllTests()
                        isRunning = false
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isRunning) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Tests en cours...")
                } else {
                    Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Lancer les tests")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Conversation mode tests button
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    isRunning = true
                    convResults = emptyList()
                    scope.launch {
                        val runner = com.earflows.app.test.ConversationModeTest(context)
                        convResults = runner.runAllTests()
                        isRunning = false
                    }
                },
                enabled = !isRunning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Tests Mode Conversation (BT)")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Summary
            if (results.isNotEmpty()) {
                val passed = results.count { it.passed }
                val total = results.size
                val allPassed = passed == total

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (allPassed) Color(0xFF00B894).copy(alpha = 0.15f)
                        else Color(0xFFE17055).copy(alpha = 0.15f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (allPassed) Icons.Default.CheckCircle else Icons.Default.Error,
                            null,
                            tint = if (allPassed) Color(0xFF00B894) else Color(0xFFE17055),
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                "$passed/$total tests OK",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp
                            )
                            Text(
                                "Latence totale: ${results.sumOf { it.durationMs }}ms",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }

            // Individual results
            for (result in results) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Status dot
                        Box(
                            modifier = Modifier
                                .padding(top = 4.dp)
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(
                                    if (result.passed) Color(0xFF00B894) else Color(0xFFE17055)
                                )
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    result.name,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                Text(
                                    "${result.durationMs}ms",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                result.message,
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                lineHeight = 14.sp
                            )
                        }
                    }
                }
            }

            // Conversation mode results
            if (convResults.isNotEmpty()) {
                Spacer(modifier = Modifier.height(16.dp))

                val cPassed = convResults.count { it.passed }
                val cTotal = convResults.size
                Text("Mode Conversation: $cPassed/$cTotal",
                    fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(8.dp))

                for (result in convResults) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
                            Box(modifier = Modifier.padding(top = 4.dp).size(10.dp).clip(CircleShape)
                                .background(if (result.passed) Color(0xFF00B894) else Color(0xFFE17055)))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(result.name, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(result.message, fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 14.sp)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}
