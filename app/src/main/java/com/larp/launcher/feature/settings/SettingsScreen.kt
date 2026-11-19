package com.larp.launcher.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.larp.launcher.core.design.components.LarpCard
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var apiUrlText by remember { mutableStateOf(state.apiUrl) }

    LaunchedEffect(state.apiUrl) {
        apiUrlText = state.apiUrl
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Ajustes", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionTitle(text = "Aparência", icon = Icons.Filled.Palette)
                AppearanceSection(
                    dynamicColor = state.dynamicColor,
                    onDynamicColorChange = { viewModel.setDynamicColor(it) },
                    darkMode = state.darkMode,
                    onDarkModeChange = { viewModel.setDarkMode(it) }
                )
            }
            item {
                SectionTitle(text = "Servidor", icon = Icons.Filled.Storage)
                ServerSection(
                    apiUrl = apiUrlText,
                    onApiUrlChange = { apiUrlText = it },
                    onSave = { viewModel.saveApiUrl(apiUrlText) }
                )
            }
            item {
                SectionTitle(text = "Sobre", icon = Icons.Filled.Info)
                AboutSection()
            }
            item {
                Button(
                    onClick = onOpenLicenses,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(text = "Ver licenças open source")
                }
            }
            item {
                Button(
                    onClick = {
                        viewModel.clearCache { count ->
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = if (count > 0) {
                                        "Cache limpo ($count arquivos)"
                                    } else {
                                        "Cache já estava vazio"
                                    }
                                )
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(imageVector = Icons.Filled.CleaningServices, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = "Limpar cache de mods")
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String, icon: ImageVector) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun AppearanceSection(
    dynamicColor: Boolean,
    onDynamicColorChange: (Boolean) -> Unit,
    darkMode: String,
    onDarkModeChange: (String) -> Unit
) {
    LarpCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Cor dinâmica (Material You)",
                    modifier = Modifier.weight(1f)
                )
                Switch(checked = dynamicColor, onCheckedChange = onDynamicColorChange)
            }
            Text(
                text = "Tema",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                val options = listOf(
                    "light" to "Claro",
                    "dark" to "Escuro",
                    "system" to "Sistema"
                )
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = darkMode == value,
                        onClick = { onDarkModeChange(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                    ) {
                        Text(text = label)
                    }
                }
            }
        }
    }
}

@Composable
private fun ServerSection(
    apiUrl: String,
    onApiUrlChange: (String) -> Unit,
    onSave: () -> Unit
) {
    LarpCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = apiUrl,
                onValueChange = onApiUrlChange,
                label = { Text("URL da API") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Button(
                onClick = onSave,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = "Salvar")
            }
        }
    }
}

@Composable
private fun AboutSection() {
    LarpCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Larp Launcher",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Versão 0.1.0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Licença GPLv3",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
