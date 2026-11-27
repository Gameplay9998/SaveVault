package com.savevault.app.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.savevault.app.backup.AutoBackupWorker
import com.savevault.app.backup.BackupManager
import com.savevault.app.backup.BackupOutcome
import com.savevault.app.backup.DriveClient
import com.savevault.app.backup.RootShell
import com.savevault.app.data.BackupVersion
import com.savevault.app.data.Category
import com.savevault.app.data.Game
import com.savevault.app.data.GameRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

sealed interface Screen {
    data object Home : Screen
    data class Detail(val gameId: String) : Screen
    data class Edit(val gameId: String?, val category: Category) : Screen
    data object Settings : Screen
}

data class SettingsState(
    val localFolder: String?,
    val autoEnabled: Boolean,
    val intervalHours: Int,
    val wifiOnly: Boolean,
    val driveConnected: Boolean,
    val driveEmail: String?,
    val lastAutoRun: Long,
)

data class VersionsState(
    val loading: Boolean = true,
    val items: List<BackupVersion> = emptyList(),
    val errors: List<String> = emptyList(),
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val ctx = app.applicationContext
    private val repo = GameRepository.get(ctx)
    private val manager = BackupManager(ctx)
    private val settings = manager.settings

    val games = repo.games

    // ---------- navegação ----------
    val backStack = mutableStateListOf<Screen>(Screen.Home)
    val screen: Screen get() = backStack.last()
    var homeTab by mutableStateOf(Category.EMULATOR)

    fun navigate(s: Screen) { backStack.add(s) }
    fun back(): Boolean = if (backStack.size > 1) { backStack.removeAt(backStack.lastIndex); true } else false

    // ---------- mensagens (snackbar) ----------
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()
    private fun toast(msg: String) { _messages.trySend(msg) }

    /** Jogos com operação em andamento → texto exibido ("Fazendo backup…"). */
    val busy = mutableStateMapOf<String, String>()

    // ---------- configurações ----------
    var settingsState by mutableStateOf(readSettings())
        private set
    var rootAvailable by mutableStateOf<Boolean?>(null)
        private set

    private fun readSettings() = SettingsState(
        localFolder = settings.localFolderUri?.let { Uri.parse(it).lastPathSegment?.substringAfter(':') ?: it },
        autoEnabled = settings.autoEnabled,
        intervalHours = settings.autoIntervalHours,
        wifiOnly = settings.wifiOnly,
        driveConnected = settings.driveConnected,
        driveEmail = settings.driveEmail,
        lastAutoRun = settings.lastAutoRun,
    )

    private fun settingsChanged() {
        settingsState = readSettings()
        AutoBackupWorker.reschedule(ctx)
    }

    fun internalBackupDir(): String = manager.localStore().description

    fun checkRoot() {
        viewModelScope.launch { rootAvailable = withContext(Dispatchers.IO) { RootShell.isAvailable() } }
    }

    fun setLocalFolder(uri: Uri?) {
        uri?.let {
            ctx.contentResolver.takePersistableUriPermission(
                it, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        settings.localFolderUri = uri?.toString()
        settingsChanged()
    }

    fun setAutoEnabled(v: Boolean) { settings.autoEnabled = v; settingsChanged() }
    fun setInterval(h: Int) { settings.autoIntervalHours = h; settingsChanged() }
    fun setWifiOnly(v: Boolean) { settings.wifiOnly = v; settingsChanged() }

    var driveBusy by mutableStateOf(false)
        private set

    fun connectDrive(onNeedsUser: (PendingIntent) -> Unit) {
        driveBusy = true
        viewModelScope.launch {
            runCatching { manager.drive.beginConnect() }
                .onSuccess { step ->
                    when (step) {
                        is DriveClient.ConnectStep.NeedsUser -> onNeedsUser(step.intent)
                        is DriveClient.ConnectStep.Connected -> {
                            driveBusy = false
                            toast("Google Drive conectado")
                        }
                    }
                }
                .onFailure {
                    driveBusy = false
                    toast("Não foi possível conectar: ${it.message}")
                }
            settingsChanged()
        }
    }

    fun onDriveResult(data: Intent?) {
        viewModelScope.launch {
            runCatching { manager.drive.completeConnect(data) }
                .onSuccess { toast("Google Drive conectado") }
                .onFailure { toast("Conexão cancelada") }
            driveBusy = false
            settingsChanged()
        }
    }

    fun disconnectDrive() {
        viewModelScope.launch {
            manager.drive.disconnect()
            settingsChanged()
            toast("Google Drive desconectado")
        }
    }

    // ---------- jogos ----------

    fun game(id: String): Game? = repo.get(id)

    fun saveGame(game: Game, newCover: Uri?, removeCover: Boolean, onDone: (Game) -> Unit) {
        viewModelScope.launch {
            var g = game
            val old = repo.get(game.id)?.coverPath
            if (newCover != null) {
                val path = withContext(Dispatchers.IO) { runCatching { copyCover(game.id, newCover) }.getOrNull() }
                if (path == null) toast("Não foi possível carregar a imagem")
                else {
                    old?.let { File(it).delete() }
                    g = g.copy(coverPath = path)
                }
            } else if (removeCover) {
                old?.let { File(it).delete() }
                g = g.copy(coverPath = null)
            }
            repo.upsert(g)
            AutoBackupWorker.reschedule(ctx)
            onDone(g)
        }
    }

    fun deleteGame(game: Game) {
        viewModelScope.launch {
            repo.delete(game.id)
            AutoBackupWorker.reschedule(ctx)
            backStack.removeAll { (it as? Screen.Detail)?.gameId == game.id || (it as? Screen.Edit)?.gameId == game.id }
            toast("\"${game.name}\" removido (os backups continuam guardados)")
        }
    }

    /** Copia a imagem escolhida para o app, reduzida para no máximo 1024 px. */
    private fun copyCover(gameId: String, uri: Uri): String {
        val dir = File(ctx.filesDir, "covers").apply { mkdirs() }
        val bitmap: Bitmap = if (Build.VERSION.SDK_INT >= 28) {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { dec, info, _ ->
                val max = maxOf(info.size.width, info.size.height)
                if (max > 1024) {
                    val scale = 1024f / max
                    dec.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
                }
                dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 2048) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
                ?: error("imagem inválida")
        }
        val file = File(dir, "${gameId}_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return file.absolutePath
    }

    // ---------- backup / restauração ----------

    fun backupNow(game: Game) {
        if (busy.containsKey(game.id)) return
        busy[game.id] = "Fazendo backup…"
        viewModelScope.launch {
            val r = manager.backup(game, force = true)
            busy.remove(game.id)
            when (r) {
                is BackupOutcome.Done -> toast(
                    if (r.warning == null) "Backup de \"${game.name}\" concluído"
                    else "Backup feito com avisos: ${r.warning}",
                )
                is BackupOutcome.Failed -> toast("Falha no backup: ${r.message}")
                BackupOutcome.Skipped -> toast("Nada mudou desde o último backup")
            }
            if ((screen as? Screen.Detail)?.gameId == game.id) loadVersions(game.id)
        }
    }

    fun backupAll(category: Category) {
        val list = games.value.filter { it.category == category }
        if (list.isEmpty()) { toast("Nenhum jogo nesta aba"); return }
        viewModelScope.launch {
            var ok = 0; var fail = 0; var skip = 0
            for (g in list) {
                busy[g.id] = "Na fila…"
            }
            for (g in list) {
                busy[g.id] = "Fazendo backup…"
                when (manager.backup(g, force = false)) {
                    is BackupOutcome.Done -> ok++
                    is BackupOutcome.Failed -> fail++
                    BackupOutcome.Skipped -> skip++
                }
                busy.remove(g.id)
            }
            toast("Backups: $ok feitos, $skip sem mudanças, $fail com falha")
        }
    }

    var versions by mutableStateOf(VersionsState())
        private set

    fun loadVersions(gameId: String) {
        val g = repo.get(gameId) ?: return
        versions = if (versions.items.firstOrNull()?.gameId == gameId) versions.copy(loading = true) else VersionsState()
        viewModelScope.launch {
            val (items, errors) = manager.listVersions(g)
            versions = VersionsState(false, items, errors)
        }
    }

    fun restore(game: Game, v: BackupVersion) {
        if (busy.containsKey(game.id)) return
        busy[game.id] = "Restaurando…"
        viewModelScope.launch {
            runCatching { manager.restore(game, v) }
                .onSuccess { toast("Save restaurado!") }
                .onFailure { toast("Falha ao restaurar: ${it.message}") }
            busy.remove(game.id)
        }
    }

    fun deleteVersion(game: Game, v: BackupVersion) {
        viewModelScope.launch {
            runCatching { manager.deleteVersion(v) }
                .onSuccess { toast("Backup apagado") }
                .onFailure { toast("Não foi possível apagar: ${it.message}") }
            loadVersions(game.id)
        }
    }
}
