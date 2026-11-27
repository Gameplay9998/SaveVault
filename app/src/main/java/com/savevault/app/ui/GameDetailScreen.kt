package com.savevault.app.ui

import android.text.format.Formatter
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.savevault.app.data.BackupVersion
import com.savevault.app.data.Game
import com.savevault.app.data.SourceType
import com.savevault.app.data.StorageLocation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameDetailScreen(vm: MainViewModel, gameId: String, snackbar: SnackbarHostState) {
    val games by vm.games.collectAsStateWithLifecycle()
    val game = games.firstOrNull { it.id == gameId }
    val context = LocalContext.current

    LaunchedEffect(gameId) { vm.loadVersions(gameId) }
    if (game == null) {
        // Só volta se esta tela ainda for a atual (evita fechar telas a mais durante a animação).
        LaunchedEffect(Unit) { if ((vm.screen as? Screen.Detail)?.gameId == gameId) vm.back() }
        return
    }

    val busy = vm.busy[game.id]
    val versions = vm.versions
    var confirmDelete by remember { mutableStateOf(false) }
    var toRestore by remember { mutableStateOf<BackupVersion?>(null) }
    var toDelete by remember { mutableStateOf<BackupVersion?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(game.name, maxLines = 1) },
                navigationIcon = {
                    IconButton(onClick = { vm.back() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Voltar") }
                },
                actions = {
                    IconButton(onClick = { vm.navigate(Screen.Edit(game.id, game.category)) }) {
                        Icon(Icons.Outlined.Edit, "Editar")
                    }
                    IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Remover") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Header(game) }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.backupNow(game) },
                        enabled = busy == null,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                    ) {
                        if (busy != null) {
                            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(busy)
                        } else {
                            Icon(Icons.Outlined.Backup, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Fazer backup agora")
                        }
                    }
                    game.lastError?.let { ErrorCard(it) }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Backups salvos", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = { vm.loadVersions(game.id) }) { Icon(Icons.Outlined.Refresh, "Atualizar") }
                    }
                    if (versions.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    versions.errors.forEach { Hint(it, error = true) }
                }
            }

            val list = versions.items.filter { it.gameId == game.id }
            if (!versions.loading && list.isEmpty()) {
                item {
                    Hint("Nenhum backup ainda. Toque em \"Fazer backup agora\".")
                }
            }
            items(list, key = { it.location.name + it.ref }) { v ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                        leadingContent = {
                            Icon(
                                if (v.location == StorageLocation.DRIVE) Icons.Outlined.Cloud else Icons.Outlined.PhoneAndroid,
                                null,
                                tint = MaterialTheme.colorScheme.secondary,
                            )
                        },
                        headlineContent = { Text(fullDate(v.timestamp)) },
                        supportingContent = {
                            Text(
                                (if (v.location == StorageLocation.DRIVE) "Google Drive" else "Local") +
                                    " · " + Formatter.formatShortFileSize(context, v.size),
                            )
                        },
                        trailingContent = {
                            Row {
                                IconButton(onClick = { toRestore = v }, enabled = busy == null) {
                                    Icon(Icons.Outlined.Restore, "Restaurar", tint = MaterialTheme.colorScheme.primary)
                                }
                                IconButton(onClick = { toDelete = v }) { Icon(Icons.Outlined.Delete, "Apagar") }
                            }
                        },
                    )
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }

    toRestore?.let { v ->
        AlertDialog(
            onDismissRequest = { toRestore = null },
            icon = { Icon(Icons.Outlined.Restore, null) },
            title = { Text("Restaurar este backup?") },
            text = {
                Text(
                    "O save atual de \"${game.name}\" será substituído pelo backup de ${fullDate(v.timestamp)}. " +
                        "Feche o jogo/emulador antes. Se quiser garantir, faça um backup do estado atual primeiro.",
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.restore(game, v); toRestore = null }) { Text("Restaurar") }
            },
            dismissButton = { TextButton(onClick = { toRestore = null }) { Text("Cancelar") } },
        )
    }

    toDelete?.let { v ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Apagar este backup?") },
            text = { Text("O backup de ${fullDate(v.timestamp)} será apagado permanentemente.") },
            confirmButton = {
                TextButton(onClick = { vm.deleteVersion(game, v); toDelete = null }) {
                    Text("Apagar", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Cancelar") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Remover \"${game.name}\"?") },
            text = { Text("O jogo sai da lista do app. Os arquivos de backup já feitos continuam guardados.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; vm.deleteGame(game) }) {
                    Text("Remover", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun Header(game: Game) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        GameCover(game.coverPath, game.name, Modifier.width(120.dp).height(160.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(game.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(game.emulator ?: if (game.category.name == "ANDROID") "Android" else "Emulador")
                if (game.autoBackup) Pill("Auto", Icons.Outlined.Autorenew, MaterialTheme.colorScheme.primary)
            }
            InfoLine(
                if (game.sourceType == SourceType.ROOT) Icons.Outlined.Security else Icons.Outlined.Folder,
                if (game.sourceType == SourceType.ROOT) game.rootPath ?: "-"
                else game.sourceUri?.let { folderLabel(it) } ?: "-",
            )
            InfoLine(
                if (game.destination.drive) Icons.Outlined.Cloud else Icons.Outlined.PhoneAndroid,
                "Destino: ${game.destination.label}",
            )
            Text(
                "Último backup: " + if (game.lastBackupAt > 0) fullDate(game.lastBackupAt) else "nunca",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InfoLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.12f))) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.ErrorOutline, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(8.dp))
            Box(Modifier.weight(1f)) {
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
