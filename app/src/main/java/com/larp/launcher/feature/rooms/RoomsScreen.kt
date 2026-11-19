package com.larp.launcher.feature.rooms

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.larp.launcher.core.design.components.LarpAvatar
import com.larp.launcher.core.design.components.LarpCard
import com.larp.launcher.core.design.components.LarpChip
import com.larp.launcher.core.design.components.LarpDot
import com.larp.launcher.domain.model.Room
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(
    onBack: () -> Unit,
    viewModel: RoomsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Salas", fontWeight = FontWeight.SemiBold) },
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
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showSheet = true }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Criar sala")
            }
        }
    ) { inner ->
        when {
            state.loading && state.rooms.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(inner),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            state.rooms.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(inner).padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nenhuma sala disponível",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(inner),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (state.error != null) {
                        item {
                            Text(
                                text = state.error ?: "",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    items(state.rooms, key = { it.id }) { room ->
                        RoomCard(
                            room = room,
                            isCurrent = room.id == state.currentRoomId,
                            isAdmin = state.isAdmin,
                            onJoin = { viewModel.joinRoom(room.id) },
                            onLeave = { viewModel.leaveCurrentRoom() },
                            onDelete = { viewModel.deleteRoom(room.id) }
                        )
                    }
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            CreateRoomForm(
                onCancel = { showSheet = false },
                onCreate = { name, game, max, voice ->
                    viewModel.createRoom(name, game, max, voice)
                    scope.launch {
                        sheetState.hide()
                        showSheet = false
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RoomCard(
    room: Room,
    isCurrent: Boolean,
    isAdmin: Boolean,
    onJoin: () -> Unit,
    onLeave: () -> Unit,
    onDelete: () -> Unit
) {
    LarpCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LarpAvatar(
                    url = null,
                    fallbackInitial = room.ownerName,
                    size = 40
                )
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = room.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        LarpDot(
                            color = if (room.locked) {
                                MaterialTheme.colorScheme.error
                            } else {
                                Color(0xFF2E7D32)
                            }
                        )
                    }
                    Text(
                        text = "por ${room.ownerName} • ${room.game}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (isAdmin) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = "Excluir sala",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LarpChip(
                    icon = Icons.Filled.Group,
                    label = "${room.playerCount}/${room.maxPlayers}"
                )
                if (room.voiceEnabled) {
                    LarpChip(icon = Icons.Filled.Mic, label = "Voz")
                }
                if (room.locked) {
                    LarpChip(label = "Trancada")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isCurrent) {
                    OutlinedButton(onClick = onLeave) {
                        Text(text = "Sair")
                    }
                } else {
                    Button(onClick = onJoin) {
                        Text(text = "Entrar")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CreateRoomForm(
    onCancel: () -> Unit,
    onCreate: (String, String, Int, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var game by remember { mutableStateOf("") }
    var maxPlayers by remember { mutableFloatStateOf(16f) }
    var voice by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Nova sala",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nome da sala") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = game,
            onValueChange = { game = it },
            label = { Text("Jogo") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "Máximo de jogadores: ${maxPlayers.toInt()}",
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = maxPlayers,
                onValueChange = { maxPlayers = it },
                valueRange = 1f..32f,
                steps = 30
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Voz habilitada",
                modifier = Modifier.weight(1f)
            )
            Switch(checked = voice, onCheckedChange = { voice = it })
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = "Cancelar")
            }
            Button(
                onClick = { onCreate(name, game, maxPlayers.toInt(), voice) },
                enabled = name.isNotBlank() && game.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = "Criar")
            }
        }
    }
}
