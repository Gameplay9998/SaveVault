package com.savevault.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.savevault.app.data.Category
import com.savevault.app.data.Game

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: MainViewModel, snackbar: SnackbarHostState) {
    val games by vm.games.collectAsStateWithLifecycle()
    val tab = vm.homeTab
    val visible = games.filter { it.category == tab }.sortedBy { it.name.lowercase() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SaveVault", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { vm.backupAll(tab) }) {
                        Icon(Icons.Outlined.CloudSync, "Fazer backup de todos desta aba")
                    }
                    IconButton(onClick = { vm.navigate(Screen.Settings) }) {
                        Icon(Icons.Outlined.Settings, "Configurações")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { vm.navigate(Screen.Edit(null, tab)) },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("Adicionar jogo") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = if (tab == Category.ANDROID) 0 else 1) {
                listOf(Category.ANDROID, Category.EMULATOR).forEach { cat ->
                    val count = games.count { it.category == cat }
                    Tab(
                        selected = tab == cat,
                        onClick = { vm.homeTab = cat },
                        icon = {
                            Icon(
                                if (cat == Category.ANDROID) Icons.Outlined.Android else Icons.Outlined.VideogameAsset,
                                null,
                            )
                        },
                        text = { Text((if (cat == Category.ANDROID) "Android" else "Emuladores") + " ($count)") },
                    )
                }
            }

            if (visible.isEmpty()) {
                EmptyState(tab)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(visible, key = { it.id }) { game ->
                        GameCard(game, vm.busy[game.id]) {
                            vm.navigate(Screen.Detail(game.id))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameCard(game: Game, busyText: String?, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
            GameCover(game.coverPath, game.name, Modifier.fillMaxSize())

            // degradê para dar leitura aos selos
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .align(Alignment.BottomCenter)
                    .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (game.destination.local) Icon(Icons.Outlined.PhoneAndroid, "Local", tint = Color.White, modifier = Modifier.size(16.dp))
                if (game.destination.drive) Icon(Icons.Outlined.Cloud, "Drive", tint = Color.White, modifier = Modifier.size(16.dp))
                if (game.autoBackup) Icon(Icons.Outlined.Autorenew, "Automático", tint = Color.White, modifier = Modifier.size(16.dp))
            }
            if (game.lastError != null) {
                Badge(Modifier.align(Alignment.TopEnd).padding(8.dp), containerColor = MaterialTheme.colorScheme.error) {
                    Icon(Icons.Outlined.ErrorOutline, "Erro", modifier = Modifier.size(12.dp))
                }
            }
            if (busyText != null) {
                Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(Modifier.size(32.dp), color = Color.White)
                        Spacer(Modifier.height(8.dp))
                        Text(busyText, color = Color.White, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }
        Text(
            game.name,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
        Text(
            listOfNotNull(game.emulator, "Backup ${relativeTime(game.lastBackupAt)}").joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun EmptyState(tab: Category) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            if (tab == Category.ANDROID) Icons.Outlined.Android else Icons.Outlined.VideogameAsset,
            null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            if (tab == Category.ANDROID) "Nenhum jogo Android ainda" else "Nenhum emulador ainda",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            if (tab == Category.ANDROID)
                "Adicione um jogo escolhendo a pasta do save ou, com root, o app instalado."
            else
                "Adicione um jogo e aponte para a pasta onde o emulador grava os saves.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}
