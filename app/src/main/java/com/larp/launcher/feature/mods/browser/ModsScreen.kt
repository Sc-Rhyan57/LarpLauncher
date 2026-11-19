package com.larp.launcher.feature.mods.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.larp.launcher.core.design.components.LarpCard
import com.larp.launcher.core.design.components.LarpChip
import com.larp.launcher.core.design.components.LarpProgressCard
import com.larp.launcher.domain.model.ServerMod

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModsScreen(
    onBack: () -> Unit,
    viewModel: ModsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val installStates by viewModel.installStates.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }
    val pullState = rememberPullToRefreshState()

    val filtered = remember(state.mods, query) {
        if (query.isBlank()) state.mods
        else state.mods.filter { it.name.contains(query, ignoreCase = true) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Mods do servidor", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(imageVector = Icons.Filled.Refresh, contentDescription = "Atualizar")
                    }
                }
            )
        }
    ) { inner ->
        PullToRefreshBox(
            isRefreshing = state.loading,
            onRefresh = { viewModel.refresh() },
            state = pullState,
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    placeholder = { Text("Buscar mods…") },
                    leadingIcon = {
                        Icon(imageVector = Icons.Filled.Search, contentDescription = null)
                    },
                    singleLine = true
                )

                if (state.error != null && state.mods.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = state.error ?: "",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    return@Column
                }

                if (state.mods.isEmpty() && !state.loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Nenhum mod disponível",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    return@Column
                }

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(filtered, key = { it.id }) { mod ->
                        ModCard(
                            mod = mod,
                            installState = installStates[mod.id] ?: InstallState.Idle,
                            onInstall = { viewModel.install(mod) },
                            onUninstall = { viewModel.uninstall(mod) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModCard(
    mod: ServerMod,
    installState: InstallState,
    onInstall: () -> Unit,
    onUninstall: () -> Unit
) {
    LarpCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                if (!mod.coverUrl.isNullOrBlank()) {
                    AsyncImage(
                        model = mod.coverUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mod.name.firstOrNull()?.uppercase() ?: "?",
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = mod.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        if (mod.isOfficial) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Icon(
                                imageVector = Icons.Filled.Verified,
                                contentDescription = "Oficial",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    Text(
                        text = "por ${mod.author} • v${mod.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = formatSize(mod.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (mod.tags.isNotEmpty()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    mod.tags.take(4).forEach { tag ->
                        LarpChip(label = tag)
                    }
                }
            }

            when (installState) {
                is InstallState.Downloading -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LarpProgressCard(
                        title = "Baixando ${mod.name}",
                        progress = installState.progress
                    )
                    Text(
                        text = "Sincronizando…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is InstallState.Installing -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LarpProgressCard(
                        title = "Instalando ${mod.name}",
                        progress = installState.progress
                    )
                    Text(
                        text = "Sincronizando…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                is InstallState.Error -> Text(
                    text = installState.message,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
                is InstallState.Done, InstallState.Idle -> {
                    if (mod.installed || installState is InstallState.Done) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Instalado",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(onClick = onUninstall) {
                                Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(text = "Remover")
                            }
                        }
                    } else {
                        Button(
                            onClick = onInstall,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(imageVector = Icons.Filled.Download, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = "Instalar")
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    if (bytes <= 0) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB")
    var size = bytes.toDouble()
    var idx = 0
    while (size >= 1024 && idx < units.lastIndex) {
        size /= 1024
        idx++
    }
    return String.format("%.1f %s", size, units[idx])
}
