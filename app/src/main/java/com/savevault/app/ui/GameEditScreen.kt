package com.savevault.app.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.AsyncImage
import com.savevault.app.data.Category
import com.savevault.app.data.Destination
import com.savevault.app.data.EMULATOR_PRESETS
import com.savevault.app.data.Game
import com.savevault.app.data.SourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameEditScreen(vm: MainViewModel, gameId: String?, initialCategory: Category) {
    val context = LocalContext.current
    val existing = remember(gameId) { gameId?.let { vm.game(it) } }

    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: initialCategory) }
    var emulator by rememberSaveable { mutableStateOf(existing?.emulator ?: "") }
    var sourceType by rememberSaveable {
        mutableStateOf(existing?.sourceType ?: SourceType.FOLDER)
    }
    var sourceUri by rememberSaveable { mutableStateOf(existing?.sourceUri) }
    var rootPath by rememberSaveable { mutableStateOf(existing?.rootPath ?: "") }
    var packageName by rememberSaveable { mutableStateOf(existing?.packageName) }
    var destination by rememberSaveable { mutableStateOf(existing?.destination ?: Destination.LOCAL) }
    var autoBackup by rememberSaveable { mutableStateOf(existing?.autoBackup ?: true) }
    var onlyIfChanged by rememberSaveable { mutableStateOf(existing?.onlyIfChanged ?: true) }
    var keepVersions by remember { mutableFloatStateOf((existing?.keepVersions ?: 5).toFloat()) }
    var newCover by rememberSaveable { mutableStateOf<Uri?>(null) }
    var removeCover by rememberSaveable { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            sourceUri = uri.toString()
        }
    }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) { newCover = uri; removeCover = false }
    }

    val coverModel: Any? = newCover ?: existing?.coverPath?.takeIf { !removeCover }?.let { java.io.File(it) }

    fun save() {
        val n = name.trim()
        error = when {
            n.isEmpty() -> "Dê um nome ao jogo"
            sourceType == SourceType.FOLDER && sourceUri == null -> "Escolha a pasta do save"
            sourceType == SourceType.ROOT && rootPath.isBlank() -> "Informe o caminho do save"
            else -> null
        }
        if (error != null) return
        val sourceChanged = existing != null &&
            (existing.sourceType != sourceType || existing.sourceUri != sourceUri || existing.rootPath != rootPath)
        val base = existing ?: Game(name = n, category = category)
        val game = base.copy(
            name = n,
            category = category,
            emulator = emulator.trim().ifEmpty { null }.takeIf { category == Category.EMULATOR },
            sourceType = sourceType,
            sourceUri = sourceUri.takeIf { sourceType == SourceType.FOLDER },
            rootPath = rootPath.trim().takeIf { sourceType == SourceType.ROOT },
            packageName = packageName.takeIf { sourceType == SourceType.ROOT },
            destination = destination,
            autoBackup = autoBackup,
            onlyIfChanged = onlyIfChanged,
            keepVersions = Math.round(keepVersions),
            lastFingerprint = if (sourceChanged) null else base.lastFingerprint,
        )
        saving = true
        vm.saveGame(game, newCover, removeCover) {
            saving = false
            vm.back()
            if (existing == null) vm.navigate(Screen.Detail(it.id))
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "Novo jogo" else "Editar jogo") },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                actions = {
                    if (saving) CircularProgressIndicator(Modifier.size(24.dp).padding(end = 8.dp))
                    else TextButton(onClick = ::save) { Text("Salvar", fontWeight = FontWeight.Bold) }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // ---------- capa + nome ----------
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Box(
                    Modifier
                        .width(110.dp)
                        .height(146.dp)
                        .clickable {
                            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                ) {
                    if (coverModel != null) {
                        AsyncImage(
                            model = coverModel,
                            contentDescription = "Capa",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                        )
                    } else {
                        Card(
                            Modifier.fillMaxSize(),
                            shape = RoundedCornerShape(14.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                        ) {
                            Column(
                                Modifier.fillMaxSize(),
                                verticalArrangement = Arrangement.Center,
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Icon(Icons.Outlined.AddPhotoAlternate, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.height(6.dp))
                                Text("Foto do jogo", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("Nome do jogo") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedButton(onClick = {
                        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) {
                        Icon(Icons.Outlined.AddPhotoAlternate, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (coverModel == null) "Escolher foto" else "Trocar foto")
                    }
                    if (coverModel != null) {
                        TextButton(onClick = { newCover = null; removeCover = true }) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Remover foto")
                        }
                    }
                }
            }

            // ---------- aba ----------
            Section("Aba") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(Category.ANDROID to "Android", Category.EMULATOR to "Emulador").forEachIndexed { i, (cat, label) ->
                        SegmentedButton(
                            selected = category == cat,
                            onClick = { category = cat },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
            }

            // ---------- emulador ----------
            if (category == Category.EMULATOR) {
                Section("Emulador") {
                    var expanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                        OutlinedTextField(
                            value = emulator,
                            onValueChange = { emulator = it },
                            label = { Text("Nome do emulador") },
                            singleLine = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
                        )
                        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            EMULATOR_PRESETS.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p.name) },
                                    onClick = { emulator = if (p.name == "Outro") "" else p.name; expanded = false },
                                )
                            }
                        }
                    }
                    EMULATOR_PRESETS.firstOrNull { it.name == emulator }?.let {
                        Hint("Saves costumam ficar em: ${it.hint}")
                    }
                }
            }

            // ---------- origem do save ----------
            Section("Onde está o save") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf(SourceType.FOLDER to "Pasta", SourceType.ROOT to "Root").forEachIndexed { i, (t, label) ->
                        SegmentedButton(
                            selected = sourceType == t,
                            onClick = { sourceType = t },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (sourceType == SourceType.FOLDER) {
                    OutlinedButton(onClick = { pickFolder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Folder, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(sourceUri?.let { folderLabel(it) } ?: "Escolher pasta do save")
                    }
                    Hint(
                        "Funciona sem root. Escolha a pasta onde os saves ficam (ex.: RetroArch/saves). " +
                            "No Android 11+ o sistema não deixa escolher Android/data — nesse caso use Root, " +
                            "ou configure o emulador para salvar numa pasta comum.",
                    )
                } else {
                    OutlinedButton(onClick = { showAppPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Outlined.Apps, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(packageName ?: "Escolher app instalado")
                    }
                    packageName?.let { pkg ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = rootPath == "/data/data/$pkg",
                                onClick = { rootPath = "/data/data/$pkg" },
                                label = { Text("Dados internos") },
                            )
                            FilterChip(
                                selected = rootPath == "/sdcard/Android/data/$pkg",
                                onClick = { rootPath = "/sdcard/Android/data/$pkg" },
                                label = { Text("Android/data") },
                            )
                        }
                    }
                    OutlinedTextField(
                        value = rootPath, onValueChange = { rootPath = it },
                        label = { Text("Caminho do save") },
                        placeholder = { Text("/data/data/com.exemplo.jogo") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint("Precisa de root (Magisk/KernelSU). Pastas de cache e bibliotecas são ignoradas.")
                }
            }

            // ---------- destino ----------
            Section("Salvar backup em") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    Destination.entries.forEachIndexed { i, d ->
                        SegmentedButton(
                            selected = destination == d,
                            onClick = { destination = d },
                            shape = SegmentedButtonDefaults.itemShape(i, Destination.entries.size),
                        ) { Text(d.label, maxLines = 1) }
                    }
                }
                if (destination.drive && !vm.settingsState.driveConnected) {
                    Hint("Conecte sua conta do Google Drive nas Configurações.", error = true)
                }
            }

            // ---------- automático ----------
            Section("Backup automático") {
                SwitchRow("Incluir no backup automático", autoBackup) { autoBackup = it }
                SwitchRow("Só fazer backup se o save mudou", onlyIfChanged) { onlyIfChanged = it }
                Spacer(Modifier.height(4.dp))
                Text("Manter as últimas ${Math.round(keepVersions)} versões", style = MaterialTheme.typography.bodyMedium)
                Slider(value = keepVersions, onValueChange = { keepVersions = it }, valueRange = 1f..20f, steps = 18)
            }

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, enabled = !saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Salvar jogo")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            onDismiss = { showAppPicker = false },
            onPick = { pkg, label ->
                showAppPicker = false
                packageName = pkg
                rootPath = "/data/data/$pkg"
                if (name.isBlank()) name = label
            },
        )
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
fun Hint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

fun folderLabel(uri: String): String =
    Uri.parse(uri).lastPathSegment?.substringAfter(':')?.ifEmpty { "Armazenamento (raiz)" } ?: uri

private data class AppInfo(val pkg: String, val label: String, val icon: ImageBitmap?)

@Composable
private fun AppPickerDialog(onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName && pm.getLaunchIntentForPackage(it.packageName) != null }
                .map { ai ->
                    AppInfo(
                        pkg = ai.packageName,
                        label = pm.getApplicationLabel(ai).toString(),
                        icon = runCatching { pm.getApplicationIcon(ai).toBitmap(96, 96).asImageBitmap() }.getOrNull(),
                    )
                }
                .sortedBy { it.label.lowercase() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar") } },
        title = { Text("Escolha o jogo") },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("Buscar") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    val filtered = list.filter {
                        query.isBlank() || it.label.contains(query, true) || it.pkg.contains(query, true)
                    }
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        items(filtered, key = { it.pkg }) { app ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(app.pkg, app.label) }
                                    .padding(vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (app.icon != null) Image(app.icon, null, Modifier.size(36.dp))
                                else Spacer(Modifier.size(36.dp))
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(app.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        app.pkg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )
}
