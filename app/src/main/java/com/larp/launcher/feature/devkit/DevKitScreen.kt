package com.larp.launcher.feature.devkit

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.larp.launcher.core.design.components.LarpCard
import com.larp.launcher.domain.model.DevKitSnapshotValue
import com.larp.launcher.domain.model.DevKitVariable
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevKitScreen(
    onBack: () -> Unit,
    viewModel: DevKitViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Dev Kit", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.takeSnapshotNow() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Atualizar snapshot")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showSheet = true },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Adicionar variável") }
            )
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                LarpCard {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Dev Kit overlay", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    text = "Quando ativado, mostra variáveis sobre o jogo.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = state.devKitEnabled,
                                onCheckedChange = { viewModel.setDevKitEnabled(it) }
                            )
                        }
                        if (state.devKitEnabled && !state.canDrawOverlays) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Permissão de sobreposição necessária.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        runCatching {
                                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            })
                                        }
                                    }
                                ) { Text("Conceder") }
                            }
                        }
                    }
                }
            }

            state.snapshot?.let { snapshot ->
                item {
                    LarpCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "Snapshot",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatTimestamp(snapshot.timestamp),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                IconButton(onClick = {
                                    copyToClipboard(context, snapshot.values.joinToString("\n") { formatValueLine(it) }, snackbarHostState, scope)
                                }) {
                                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar tudo")
                                }
                            }
                            if (snapshot.values.isEmpty()) {
                                Text(
                                    "Sem variáveis. Adicione uma abaixo.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                items(snapshot.values, key = { it.variableId + it.absoluteAddress }) { value ->
                    SnapshotValueCard(value = value) { text ->
                        copyToClipboard(context, text, snackbarHostState, scope)
                    }
                }
            }

            item {
                Text(
                    "Variáveis assistidas",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            if (state.variables.isEmpty()) {
                item {
                    LarpCard {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BugReport, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                "Nenhuma variável. Toque em + para adicionar.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            } else {
                items(state.variables, key = { it.id }) { variable ->
                    VariableCard(
                        variable = variable,
                        onToggle = { viewModel.toggleEnabled(variable, it) },
                        onDelete = { viewModel.deleteVariable(variable.id) }
                    )
                }
            }

            state.error?.let { err ->
                item {
                    LarpCard {
                        Text(
                            text = err,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error
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
            AddVariableForm(
                onCancel = {
                    scope.launch {
                        sheetState.hide()
                        showSheet = false
                    }
                },
                onSave = { name, address, module, size, format ->
                    viewModel.addVariable(name, address, module, size, format)
                    scope.launch {
                        sheetState.hide()
                        showSheet = false
                    }
                }
            )
        }
    }
}

@Composable
private fun SnapshotValueCard(value: DevKitSnapshotValue, onCopy: (String) -> Unit) {
    LarpCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = value.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { onCopy(formatValueLine(value)) }) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "Copiar")
                }
            }
            Text(
                text = "Endereço: ${value.address} (${value.module})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (value.absoluteAddress.isNotBlank()) {
                Text(
                    text = "Abs: ${value.absoluteAddress}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (value.error != null) {
                Text(
                    text = value.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(
                    text = "Raw: ${value.rawHex}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace
                )
                if (value.decoded.isNotBlank() && value.decoded != value.rawHex) {
                    Text(
                        text = "Valor: ${value.decoded}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

@Composable
private fun VariableCard(
    variable: DevKitVariable,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit
) {
    LarpCard(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = variable.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "${variable.address} • ${variable.module} • ${variable.size}B • ${variable.format}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            Switch(checked = variable.enabled, onCheckedChange = onToggle)
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "Excluir")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddVariableForm(
    onCancel: () -> Unit,
    onSave: (String, String, String, Int, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var module by remember { mutableStateOf("libil2cpp.so") }
    var size by remember { mutableStateOf("8") }
    var format by remember { mutableStateOf("hex") }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Adicionar variável", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nome (ex.: PlayerPos)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("Endereço (ex.: 0x028382)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = module,
            onValueChange = { module = it },
            label = { Text("Módulo (default: libil2cpp.so)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = size,
                onValueChange = { size = it.filter { c -> c.isDigit() } },
                label = { Text("Bytes") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = format,
                onValueChange = { format = it },
                label = { Text("Formato") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        Text(
            "Formatos: hex, int32, int64, float, double, ascii",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Cancelar") }
            Button(
                onClick = {
                    onSave(
                        name,
                        address,
                        module,
                        size.toIntOrNull() ?: 8,
                        format.trim().ifBlank { "hex" }
                    )
                },
                enabled = name.isNotBlank() && address.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) { Text("Salvar") }
        }
    }
}

private fun formatValueLine(value: DevKitSnapshotValue): String {
    return buildString {
        append(value.name)
        append(" [").append(value.address).append(" @ ").append(value.module).append("]")
        if (value.absoluteAddress.isNotBlank()) {
            append(" abs=").append(value.absoluteAddress)
        }
        if (value.error != null) {
            append(" erro=").append(value.error)
        } else {
            append(" raw=").append(value.rawHex)
            if (value.decoded.isNotBlank() && value.decoded != value.rawHex) {
                append(" val=").append(value.decoded)
            }
        }
    }
}

private fun formatTimestamp(ts: Long): String {
    val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
    return sdf.format(java.util.Date(ts))
}

private fun copyToClipboard(
    context: Context,
    text: String,
    snackbarHostState: SnackbarHostState,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("Larp DevKit", text))
    scope.launch { snackbarHostState.showSnackbar("Copiado") }
}
