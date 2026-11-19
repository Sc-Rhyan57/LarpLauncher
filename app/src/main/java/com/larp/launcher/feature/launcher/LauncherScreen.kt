package com.larp.launcher.feature.launcher

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.PlayArrow
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.larp.launcher.core.design.components.LarpAvatar
import com.larp.launcher.core.design.components.LarpCard
import com.larp.launcher.core.design.components.LarpChip

private data class QuickAccess(
    val destination: LarpDestination,
    val label: String,
    val icon: ImageVector
)

@Composable
fun LauncherScreen(
    onLaunchGame: () -> Unit,
    onNavigateTo: (LarpDestination) -> Unit,
    onLogout: () -> Unit,
    viewModel: LauncherViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val quickAccess = listOf(
        QuickAccess(LarpDestination.Mods, "Mods", Icons.Filled.Extension),
        QuickAccess(LarpDestination.Rooms, "Salas", Icons.Filled.Forum),
        QuickAccess(LarpDestination.Mural, "Mural", Icons.Filled.Campaign),
        QuickAccess(LarpDestination.Malum, "Malum", Icons.Filled.MenuBook),
        QuickAccess(LarpDestination.MyMods, "Meus Mods", Icons.Filled.Storage),
        QuickAccess(LarpDestination.Frida, "Frida Mods", Icons.Filled.Code),
        QuickAccess(LarpDestination.Settings, "Ajustes", Icons.Filled.Settings)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Larp Launcher", fontWeight = FontWeight.SemiBold) },
                actions = {
                    IconButton(onClick = {
                        viewModel.logout()
                        onLogout()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Logout,
                            contentDescription = "Sair"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { inner ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(span = { GridItemSpan(2) }) {
                UserHeaderCard(state = state)
            }
            item(span = { GridItemSpan(2) }) {
                GameStatusCard(state = state, onLaunchGame = onLaunchGame)
            }
            items(quickAccess) { entry ->
                QuickAccessCard(entry = entry, onClick = { onNavigateTo(entry.destination) })
            }
        }
    }
}

@Composable
private fun UserHeaderCard(state: LauncherUiState) {
    val user = state.currentUser
    LarpCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LarpAvatar(
                url = user?.avatar,
                fallbackInitial = user?.name ?: "?",
                size = 56
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = user?.name ?: "Visitante",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                if (user?.isAdmin == true || user?.isOwner == true) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (user.isOwner) {
                            LarpChip(label = "Owner")
                        }
                        if (user.isAdmin) {
                            LarpChip(label = "ADM")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameStatusCard(state: LauncherUiState, onLaunchGame: () -> Unit) {
    val env = state.gameEnv
    val installed = env?.isInstalled == true
    LarpCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Jogo base",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (installed) {
                            "Instalado: ${env?.versionName.orEmpty()}"
                        } else {
                            "Não instalado"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Button(
                onClick = onLaunchGame,
                enabled = installed,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(imageVector = Icons.AutoMirrored.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = "Iniciar jogo", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            if (!installed) {
                Text(
                    text = "Instale o jogo base para liberar o patcheamento.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun QuickAccessCard(entry: QuickAccess, onClick: () -> Unit) {
    LarpCard(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.Start,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = entry.icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Text(
                    text = entry.label,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
            }
            IconButton(
                onClick = onClick,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.PlayArrow,
                    contentDescription = "Abrir ${entry.label}",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
