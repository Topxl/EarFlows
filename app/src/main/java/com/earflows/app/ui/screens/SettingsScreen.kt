package com.earflows.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.SpatialAudio
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.earflows.app.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val sourceLang by viewModel.sourceLang.collectAsState()
    val targetLang by viewModel.targetLang.collectAsState()
    val splitChannel by viewModel.splitChannel.collectAsState()
    val recordTranslation by viewModel.recordTranslation.collectAsState()
    val outputVolume by viewModel.outputVolume.collectAsState()
    val hasApiKey by viewModel.hasApiKey.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Parametres") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // --- Languages ---
            SectionHeader(icon = { Icon(Icons.Default.Language, null) }, title = "Langues")

            LanguageSelector(
                label = "Langue source",
                selected = sourceLang,
                onSelect = { viewModel.setSourceLang(it) }
            )
            Spacer(modifier = Modifier.height(8.dp))
            LanguageSelector(
                label = "Langue cible",
                selected = targetLang,
                onSelect = { viewModel.setTargetLang(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- Audio ---
            SectionHeader(icon = { Icon(Icons.Default.VolumeUp, null) }, title = "Audio")

            SettingsCard {
                // Volume slider
                Text("Volume sortie traduction", style = MaterialTheme.typography.bodyMedium)
                var sliderVolume by remember { mutableFloatStateOf(outputVolume) }
                Slider(
                    value = sliderVolume,
                    onValueChange = { sliderVolume = it },
                    onValueChangeFinished = { viewModel.setOutputVolume(sliderVolume) },
                    valueRange = 0f..1.5f,
                    steps = 14
                )
                Text(
                    "${(sliderVolume * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            SettingsToggle(
                icon = { Icon(Icons.Default.SpatialAudio, null) },
                title = "Split-channel stereo",
                subtitle = "Original a gauche, traduction a droite",
                checked = splitChannel,
                onCheckedChange = { viewModel.setSplitChannel(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- Recording ---
            SectionHeader(icon = null, title = "Enregistrement")

            SettingsToggle(
                icon = null,
                title = "Enregistrer la traduction",
                subtitle = "Sauvegarde un fichier audio separe pour la piste traduite",
                checked = recordTranslation,
                onCheckedChange = { viewModel.setRecordTranslation(it) }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // --- Cloud API ---
            SectionHeader(icon = { Icon(Icons.Default.Key, null) }, title = "API Cloud")

            SettingsCard {
                Text("Cle API OpenAI", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))

                var apiKeyInput by remember { mutableStateOf("") }
                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    placeholder = { Text(if (hasApiKey) "********" else "sk-...") },
                    visualTransformation = PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row {
                    androidx.compose.material3.Button(
                        onClick = {
                            if (apiKeyInput.isNotBlank()) {
                                viewModel.setApiKey(apiKeyInput)
                                apiKeyInput = ""
                            }
                        },
                        enabled = apiKeyInput.isNotBlank()
                    ) {
                        Text("Sauvegarder")
                    }

                    if (hasApiKey) {
                        Spacer(modifier = Modifier.width(8.dp))
                        androidx.compose.material3.OutlinedButton(
                            onClick = { viewModel.clearApiKey() }
                        ) {
                            Text("Supprimer")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (hasApiKey) "Cle API configuree (stockage chiffre)" else "Aucune cle configuree",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (hasApiKey) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SectionHeader(icon: (@Composable () -> Unit)?, title: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(vertical = 8.dp)
    ) {
        icon?.invoke()
        if (icon != null) Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

@Composable
private fun SettingsToggle(
    icon: (@Composable () -> Unit)?,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            icon?.invoke()
            if (icon != null) Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageSelector(
    label: String,
    selected: String,
    onSelect: (String) -> Unit
) {
    val languages = listOf(
        "tha" to "Thai",
        "fra" to "Francais",
        "eng" to "English",
        "cmn" to "Mandarin",
        "spa" to "Espanol",
        "deu" to "Deutsch",
        "jpn" to "Japonais",
        "kor" to "Coreen",
        "vie" to "Vietnamien",
        "ara" to "Arabe",
        "hin" to "Hindi",
        "por" to "Portugais",
        "rus" to "Russe",
        "ita" to "Italien"
    )

    var expanded by remember { mutableStateOf(false) }
    val selectedDisplay = languages.find { it.first == selected }?.second ?: selected

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedDisplay,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            languages.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text(name) },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    }
                )
            }
        }
    }
}
