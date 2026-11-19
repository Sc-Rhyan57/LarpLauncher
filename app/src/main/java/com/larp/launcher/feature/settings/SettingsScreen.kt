package com.larp.launcher.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Restore
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Webhook
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.larp.launcher.core.design.components.LarpCard
import com.larp.launcher.core.LarpEnv
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenLicenses: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var apiUrl by remember(state.env.apiUrl) { mutableStateOf(state.env.apiUrl) }
    var wsUrl by remember(state.env.wsUrl) { mutableStateOf(state.env.wsUrl) }
    var discordClientId by remember(state.env.discordClientId) { mutableStateOf(state.env.discordClientId) }
    var discordRedirectUri by remember(state.env.discordRedirectUri) { mutableStateOf(state.env.discordRedirectUri) }
    var discordScope by remember(state.env.discordScope) { mutableStateOf(state.env.discordScope) }
    var iceServer by remember(state.env.webrtcIceServers.joinToString(",")) { mutableStateOf(state.env.webrtcIceServers.joinToString(",")) }
    var turnUrl by remember(state.env.turnUrl) { mutableStateOf(state.env.turnUrl) }
    var turnUser by remember(state.env.turnUser) { mutableStateOf(state.env.turnUser) }
    var turnCredential by remember(state.env.turnCredential) { mutableStateOf(state.env.turnCredential) }
    var gamePackage by remember(state.env.gamePackage) { mutableStateOf(state.env.gamePackage) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Ajustes", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(inner),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { SectionHeader("Aparência", Icons.Filled.Palette) }
            item {
                LarpCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Cor dinâmica (Material You)", modifier = Modifier.weight(1f))
                            Switch(checked = state.dynamicColor, onCheckedChange = { viewModel.setDynamicColor(it) })
                        }
                        Text(
                            "Modo escuro",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                            listOf("system" to "Sistema", "light" to "Claro", "dark" to "Escuro").forEachIndexed { i, (value, label) ->
                                SegmentedButton(
                                    selected = state.darkMode == value,
                                    onClick = { viewModel.setDarkMode(value) },
                                    shape = SegmentedButtonDefaults.itemShape(i, 3)
                                ) { Text(label) }
                            }
                        }
                    }
                }
            }

            item { SectionHeader("Servidor / API", Icons.Filled.Dns) }
            item {
                LarpCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = apiUrl,
                            onValueChange = { apiUrl = it },
                            label = { Text("API URL (HTTP)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = wsUrl,
                            onValueChange = { wsUrl = it },
                            label = { Text("WebSocket URL (WSS)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Se WS URL ficar vazio, deriva de API URL (http→ws).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { SectionHeader("Discord OAuth", Icons.Filled.Webhook) }
            item {
                LarpCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = discordClientId,
                            onValueChange = { discordClientId = it },
                            label = { Text("Client ID") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = discordRedirectUri,
                            onValueChange = { discordRedirectUri = it },
                            label = { Text("Redirect URI") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = discordScope,
                            onValueChange = { discordScope = it },
                            label = { Text("Escopos") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item { SectionHeader("Voz / WebRTC", Icons.Filled.Dns) }
            item {
                LarpCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = iceServer,
                            onValueChange = { iceServer = it },
                            label = { Text("ICE Servers (separe por vírgula)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = turnUrl,
                            onValueChange = { turnUrl = it },
                            label = { Text("TURN URL (opcional)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = turnUser,
                            onValueChange = { turnUser = it },
                            label = { Text("TURN usuário") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = turnCredential,
                            onValueChange = { turnCredential = it },
                            label = { Text("TURN senha") },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item { SectionHeader("Jogo base", Icons.Filled.SportsEsports) }
            item {
                LarpCard {
                    OutlinedTextField(
                        value = gamePackage,
                        onValueChange = { gamePackage = it },
                        label = { Text("Package name do jogo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            item {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Button(
                        onClick = {
                            val env = LarpEnv(
                                apiUrl = apiUrl.trim().trimEnd('/'),
                                wsUrl = wsUrl.trim().trimEnd('/'),
                                discordClientId = discordClientId.trim(),
                                discordRedirectUri = discordRedirectUri.trim(),
                                discordScope = discordScope.trim(),
                                webrtcIceServers = iceServer.split(",").map { it.trim() }.filter { it.isNotBlank() },
                                turnUrl = turnUrl.trim(),
                                turnUser = turnUser.trim(),
                                turnCredential = turnCredential.trim(),
                                gamePackage = gamePackage.trim(),
                                fridaGadgetBundled = state.env.fridaGadgetBundled,
                                sentryDsn = state.env.sentryDsn,
                                fcmSenderId = state.env.fcmSenderId
                            )
                            viewModel.saveEnv(env)
                            scope.launch { snackbarHostState.showSnackbar("ENVs salvas") }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.Filled.Save, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Salvar ENVs")
                    }
                    OutlinedButton(
                        onClick = {
                            viewModel.resetEnv()
                            scope.launch { snackbarHostState.showSnackbar("Restaurado para defaults") }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Restore, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Restaurar")
                    }
                }
            }

            item { SectionHeader("Cache", Icons.Filled.Storage) }
            item {
                LarpCard {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.clearCache { count ->
                                    scope.launch { snackbarHostState.showSnackbar("$count arquivos removidos") }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(imageVector = Icons.Filled.CleaningServices, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Limpar cache de mods")
                        }
                    }
                }
            }

            item { SectionHeader("Sobre", Icons.Filled.Info) }
            item {
                LarpCard {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Larp Launcher", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Versão 0.1.0 • GPLv3",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Reconstrução open-source do ReVanced/Regis com Material You.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item {
                OutlinedButton(onClick = onOpenLicenses, modifier = Modifier.fillMaxWidth()) {
                    Icon(imageVector = Icons.Filled.Code, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Ver licenças open source")
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp)
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text = text,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
