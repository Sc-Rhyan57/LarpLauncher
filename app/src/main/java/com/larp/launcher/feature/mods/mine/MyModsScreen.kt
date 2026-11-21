package com.larp.launcher.feature.mods.mine

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.larp.launcher.core.design.components.LarpCard
import com.larp.launcher.domain.model.LocalMod
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyModsScreen(
    onBack: () -> Unit,
    onLaunch: () -> Unit,
    viewModel: MyModsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSheet by remember { mutableStateOf(false) }
    var editingMod by remember { mutableStateOf<LocalMod?>(null) }
    val editSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Meus mods", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.launchWithLocalMods() }) {
                        Icon(imageVector = Icons.Filled.PlayArrow, contentDescription = "Iniciar jogo")
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
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(inner), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        if (state.mods.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(inner).padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Upload,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Nenhum mod local ainda",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Toque no botão + abaixo para subir uma DLL .NET (BepInEx plugin) e personalizar nome, descrição, autor, versão e capa.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
                Spacer(modifier = Modifier.height(20.dp))
                FilledTonalButton(onClick = { showSheet = true }) {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Adicionar DLL")
                }
            }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.mods, key = { it.id }) { mod ->
                LocalModCard(
                    mod = mod,
                    onToggle = { viewModel.toggleEnabled(mod.id, it) },
                    onEdit = { editingMod = mod },
                    onDelete = { viewModel.deleteMod(mod.id) }
                )
            }
        }
    }

    val launchState by viewModel.launch.collectAsStateWithLifecycle()
    launchState?.let { progress ->
        val failed = progress.stage == com.larp.launcher.domain.model.PatchProgress.Stage.Failed
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { viewModel.dismissLaunch() },
            title = { Text(if (failed) "Não foi possível iniciar" else "Carregando mods") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = progress.message, style = MaterialTheme.typography.bodyMedium)
                    if (!failed) {
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { progress.progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { viewModel.dismissLaunch() }) {
                    Text(if (failed) "OK" else "Cancelar")
                }
            }
        )
    }

    editingMod?.let { target ->
        ModalBottomSheet(
            onDismissRequest = { editingMod = null },
            sheetState = editSheetState
        ) {
            EditLocalModForm(
                mod = target,
                onCancel = { editingMod = null },
                onSave = { updated, coverUri ->
                    viewModel.updateMod(updated, coverUri)
                    scope.launch {
                        editSheetState.hide()
                        editingMod = null
                    }
                }
            )
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
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!mod.coverPath.isNullOrBlank()) {
                    AsyncImage(
                        model = mod.coverPath,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    Box(
                        modifier = Modifier.size(56.dp).clip(RoundedCornerShape(14.dp))
                            .background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = mod.name.firstOrNull()?.uppercase() ?: "?",
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = mod.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "por ${mod.author.ifBlank { "Desconhecido" }} • v${mod.version.ifBlank { "1.0.0" }}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (mod.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = mod.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                Switch(checked = mod.enabled, onCheckedChange = onToggle)
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatSize(mod.fileSize),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                OutlinedButton(onClick = onEdit) {
                    Icon(imageVector = Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Editar")
                }
                OutlinedButton(onClick = onDelete) {
                    Icon(imageVector = Icons.Filled.Delete, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Excluir")
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
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var version by remember { mutableStateOf("") }
    var dllUri by remember { mutableStateOf<Uri?>(null) }
    var dllFileName by remember { mutableStateOf<String?>(null) }
    var dllSize by remember { mutableStateOf<Long?>(null) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }
    var saving by remember { mutableStateOf(false) }

    val dllPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            dllUri = uri
            val info = readFileInfo(context, uri)
            dllFileName = info.first
            dllSize = info.second
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) coverUri = uri
    }

    val canSave by remember(dllUri, name) {
        derivedStateOf { dllUri != null && name.isNotBlank() }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Adicionar DLL local",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = "Selecione uma DLL .NET (BepInEx plugin). Personalize nome, descrição, autor, versão e capa.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nome do mod *") },
            placeholder = { Text("Ex: Skeld Big Chat") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Descrição") },
            placeholder = { Text("Ex: Aumenta o chat do Skeld") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("Autor") },
                placeholder = { Text("Seu nome") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = version,
                onValueChange = { version = it },
                label = { Text("Versão") },
                placeholder = { Text("1.0.0") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Arquivo DLL",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FilledTonalButton(
                    onClick = {
                        dllPicker.launch(arrayOf(
                            "application/octet-stream",
                            "application/x-msdownload",
                            "application/x-dosexec",
                            "application/x-msi",
                            "application/dll",
                            "*/*"
                        ))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(imageVector = Icons.Filled.Upload, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(text = dllFileName ?: "Selecionar DLL")
                }
                dllFileName?.let { fn ->
                    Text(
                        text = "Arquivo: $fn${dllSize?.let { " (" + formatSize(it) + ")" } ?: ""}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "Capa (opcional)",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (coverUri != null) {
                        AsyncImage(
                            model = coverUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    FilledTonalButton(onClick = { coverPicker.launch("image/*") }) {
                        Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = if (coverUri != null) "Trocar capa" else "Escolher capa")
                    }
                }
            }
        }

        if (coverUri != null || name.isNotBlank()) {
            Text(
                text = "Pré-visualização",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            LarpCard(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (coverUri != null) {
                        AsyncImage(
                            model = coverUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = name.firstOrNull()?.uppercase() ?: "?",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = name.ifBlank { "(nome do mod)" },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "por ${author.ifBlank { "Desconhecido" }} • v${version.ifBlank { "1.0.0" }}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
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
                Text("Cancelar")
            }
            Button(
                onClick = {
                    saving = true
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
                enabled = canSave && !saving,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Salvar")
                }
            }
        }
    }
}

@Composable
private fun EditLocalModForm(
    mod: LocalMod,
    onCancel: () -> Unit,
    onSave: (LocalMod, Uri?) -> Unit
) {
    var name by remember { mutableStateOf(mod.name) }
    var description by remember { mutableStateOf(mod.description) }
    var author by remember { mutableStateOf(mod.author) }
    var version by remember { mutableStateOf(mod.version) }
    var coverUri by remember { mutableStateOf<Uri?>(null) }

    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) coverUri = uri
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .padding(bottom = 24.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Editar mod",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nome do mod *") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        OutlinedTextField(
            value = description,
            onValueChange = { description = it },
            label = { Text("Descrição") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = author,
                onValueChange = { author = it },
                label = { Text("Autor") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            OutlinedTextField(
                value = version,
                onValueChange = { version = it },
                label = { Text("Versão") },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val preview: Any? = coverUri ?: mod.coverPath?.takeIf { it.isNotBlank() }
            if (preview != null) {
                AsyncImage(
                    model = preview,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(56.dp).clip(RoundedCornerShape(10.dp))
                )
                Spacer(modifier = Modifier.width(12.dp))
            }
            FilledTonalButton(onClick = { coverPicker.launch("image/*") }) {
                Icon(imageVector = Icons.Filled.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = if (preview != null) "Trocar capa" else "Escolher capa")
            }
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
                Text("Cancelar")
            }
            Button(
                onClick = {
                    onSave(
                        mod.copy(
                            name = name.trim(),
                            description = description.trim(),
                            author = author.trim(),
                            version = version.trim().ifBlank { "1.0.0" }
                        ),
                        coverUri
                    )
                },
                enabled = name.isNotBlank(),
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("Salvar")
            }
        }
    }
}

private fun readFileInfo(context: Context, uri: Uri): Pair<String?, Long?> {
    return runCatching {
        val cursor = context.contentResolver.query(uri, null, null, null, null)
        cursor?.use {
            val nameIdx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            val sizeIdx = it.getColumnIndex(android.provider.OpenableColumns.SIZE)
            it.moveToFirst()
            val name = if (nameIdx >= 0) it.getString(nameIdx) else null
            val size = if (sizeIdx >= 0) it.getLong(sizeIdx) else null
            name to size
        } ?: (null to null)
    }.getOrDefault(null to null)
}

private fun formatSize(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
    if (bytes < 1024 * 1024 * 1024) return "%.1f MB".format(bytes / (1024.0 * 1024))
    return "%.2f GB".format(bytes / (1024.0 * 1024 * 1024))
}
