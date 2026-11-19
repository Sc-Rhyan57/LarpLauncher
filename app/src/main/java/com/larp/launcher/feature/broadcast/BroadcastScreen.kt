package com.larp.launcher.feature.broadcast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.larp.launcher.core.design.components.LarpCard
import com.larp.launcher.core.design.theme.BroadcastCritical
import com.larp.launcher.core.design.theme.BroadcastInfo
import com.larp.launcher.core.design.theme.BroadcastWarn
import com.larp.launcher.domain.model.BroadcastMessage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val dateFormatter by lazy {
    SimpleDateFormat("dd/MM/yyyy HH:mm", Locale("pt", "BR"))
}

@Composable
fun BroadcastScreen(
    onBack: () -> Unit,
    viewModel: BroadcastViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf("") }
    var severity by remember { mutableStateOf(BroadcastMessage.Severity.Info) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Broadcast", fontWeight = FontWeight.SemiBold) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            if (state.isAdmin) {
                BroadcastComposer(
                    message = message,
                    onMessageChange = { message = it },
                    severity = severity,
                    onSeverityChange = { severity = it },
                    sending = state.sending,
                    onSend = {
                        viewModel.send(message, severity)
                        message = ""
                    }
                )
            } else {
                LarpCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Somente ADMs podem enviar broadcast",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (state.lastError != null) {
                Text(
                    text = state.lastError ?: "",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            if (state.loading && state.history.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            if (state.history.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nenhum broadcast enviado",
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
                items(state.history, key = { it.id }) { item ->
                    BroadcastCard(item)
                }
            }
        }
    }
}

@Composable
private fun BroadcastComposer(
    message: String,
    onMessageChange: (String) -> Unit,
    severity: BroadcastMessage.Severity,
    onSeverityChange: (BroadcastMessage.Severity) -> Unit,
    sending: Boolean,
    onSend: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        OutlinedTextField(
            value = message,
            onValueChange = onMessageChange,
            label = { Text("Mensagem") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2
        )
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            val options = listOf(
                BroadcastMessage.Severity.Info to "Info",
                BroadcastMessage.Severity.Warn to "Aviso",
                BroadcastMessage.Severity.Critical to "Crítico"
            )
            options.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = severity == value,
                    onClick = { onSeverityChange(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size)
                ) {
                    Text(text = label)
                }
            }
        }
        Button(
            onClick = onSend,
            enabled = !sending && message.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            if (sending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Icon(imageVector = Icons.AutoMirrored.Filled.Send, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(text = "Enviar para todos")
        }
    }
}

@Composable
private fun BroadcastCard(message: BroadcastMessage) {
    val color = when (message.severity) {
        BroadcastMessage.Severity.Info -> BroadcastInfo
        BroadcastMessage.Severity.Warn -> BroadcastWarn
        BroadcastMessage.Severity.Critical -> BroadcastCritical
    }
    LarpCard(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                border = BorderStroke(2.dp, color),
                shape = RoundedCornerShape(24.dp)
            )
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = message.message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = "${message.authorName} • ${dateFormatter.format(Date(message.createdAt))}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
