package com.larp.launcher.feature.mods.mine

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.automirrored.filled.PlayArrow
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.larp.launcher.core.design.components.LarpCard
import com.larp.launcher.domain.model.LocalMod
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyModsScreen(
    onBack: () -> Unit,
    onLaunch: () -> Unit,
    viewModel: MyModsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Meus mods", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Voltar"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.launchWithLocalMods()
                        onLaunch()
                    }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.PlayArrow,
                            contentDescription = "Iniciar"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showSheet = true }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = "Adicionar DLL")
            }
        }
    ) { inner ->
        if (state.mods.isEmpty() && !state.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Toque em \"+\" para adicionar uma DLL",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(inner),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.mods, key = { it.id }) { mod ->
                    LocalModCard(
                        mod = mod,
                        onToggle = { viewModel.toggleEnabled(mod.id, it) },
                        onEdit = {
                            viewModel.updateMod(mod)
                        },
                        onDelete = { viewModel.deleteMod(mod.id) }
                    )
                }
            }
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = sheetState
        ) {
            AddLocalModForm(
                onCancel = { showSheet = false },
                onSave = { result ->
                    if (result.dllUri != null) {
                        viewModel.addMod(
                            dllUri = result.dllUri,
                            coverUri = result.coverUri,
                            name = result.name,
                            description = result.description,
                            author = result.author,
                            version = result.version
                        )
                    }
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
private fun LocalModCard(
    mod: LocalMod,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    LarpCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!mod.coverPath.isNullOrBlank()) {
                    AsyncImage(
                        model = mod.coverPath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mod.name.firstOrNull()?.uppercase() ?: "?",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mod.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "por ${mod.author.ifBlank { "Desconhecido" }} • v${mod.version}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (mod.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = mod.description.take(80),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Switch(checked = mod.enabled, onCheckedChange = onToggle)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = onEdit) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Editar")
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = null)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "Excluir")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddLocalModForm(
    onCancel: () -> Unit,
    onSave: (MyModEditResult) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var dllUri by remember { mutableStateOf<Uri?>(null) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }

    val dllPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            dllUri = uri
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            coverUri = uri
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Adicionar DLL local",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nome do mod") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Descrição") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = author,
            onValueChange = { author = it },
            label = { Text("Autor") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = version,
            onValueChange = { version = it },
            label = { Text("Versão") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        OutlinedButton(
            onClick = {
                dllPicker.launch(arrayOf("application/octet-stream", "application/x-msdownload"))
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(imageVector = Icons.Filled.Upload, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = dllUri?.let { "DLL selecionada" } ?: "Selecionar DLL"
            )
        }

        OutlinedButton(
            onClick = { coverPicker.launch("image/*") },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp)
        ) {
            Icon(imageVector = Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = coverUri?.let { "Capa selecionada" } ?: "Escolher capa (opcional)"
            )
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
                onClick = {
                    onSave(
                        MyModEditResult(
                            dllUri = dllUri,
                            coverUri = coverUri,
                            name = name,
                            description = description,
                            author = author,
                            version = version
                        )
                    )
                },
                enabled = dllUri != null && name.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text(text = "Salvar")
            }
        }
    }
}
