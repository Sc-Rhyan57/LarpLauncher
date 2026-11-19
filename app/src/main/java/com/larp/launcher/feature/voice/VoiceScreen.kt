package com.larp.launcher.feature.voice

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
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.larp.launcher.core.design.components.LarpAvatar
import com.larp.launcher.core.design.components.LarpCard
import com.larp.launcher.core.design.components.LarpChip
import com.larp.launcher.domain.model.RoomParticipant

@Composable
fun VoiceScreen(
    onBack: () -> Unit,
    roomId: String? = null,
    viewModel: VoiceViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val resolvedRoomId = remember(roomId) { roomId ?: state.roomId ?: "lobby" }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Voz", fontWeight = FontWeight.SemiBold) },
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
        bottomBar = {
            if (state.connected) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = { viewModel.toggleMute() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = if (state.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                            contentDescription = null
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = if (state.muted) "Ativar microfone" else "Mutar")
                    }
                    OutlinedButton(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text(text = "Desconectar")
                    }
                }
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val circleColor = if (state.connected) Color(0xFFB3261E) else Color(0xFF2E7D32)
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(50))
                    .background(circleColor),
                contentAlignment = Alignment.Center
            ) {
                if (state.connecting) {
                    CircularProgressIndicator(
                        color = Color.White,
                        strokeWidth = 3.dp,
                        modifier = Modifier.size(32.dp)
                    )
                } else {
                    IconButton(onClick = {
                        if (state.connected) {
                            viewModel.disconnect()
                        } else {
                            viewModel.connect(resolvedRoomId)
                        }
                    }) {
                        Icon(
                            imageVector = Icons.Filled.PhoneInTalk,
                            contentDescription = if (state.connected) "Desconectar" else "Conectar",
                            tint = Color.White,
                            modifier = Modifier.size(40.dp)
                        )
                    }
                }
            }

            LarpCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = when {
                            state.connecting -> "Conectando…"
                            state.connected -> "Conectado à sala ${state.roomId ?: resolvedRoomId}"
                            else -> "Desconectado"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "O chat de voz usa Cloudflare Calls (WebRTC)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            state.error?.let { err ->
                Text(
                    text = err,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (state.connected) {
                Text(
                    text = "Participantes (${state.participants.size})",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(vertical = 4.dp)
                ) {
                    items(state.participants, key = { it.userId }) { participant ->
                        ParticipantRow(participant = participant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParticipantRow(participant: RoomParticipant) {
    LarpCard(modifier = Modifier.fillMaxWidth(), cornerRadius = 18) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LarpAvatar(
                url = participant.avatar,
                fallbackInitial = participant.name,
                size = 40
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = participant.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium
                )
                if (participant.speaking && !participant.muted) {
                    LarpChip(label = "Falando")
                }
            }
            Icon(
                imageVector = if (participant.muted) Icons.Filled.MicOff else Icons.Filled.Mic,
                contentDescription = if (participant.muted) "Mutado" else "Microfone ativo",
                tint = if (participant.muted) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                }
            )
        }
    }
}
