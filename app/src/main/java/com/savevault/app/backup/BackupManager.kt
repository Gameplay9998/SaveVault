package com.savevault.app.backup

import android.content.Context
import android.net.Uri
import android.os.Process
import com.savevault.app.data.BackupVersion
import com.savevault.app.data.Game
import com.savevault.app.data.GameRepository
import com.savevault.app.data.Settings
import com.savevault.app.data.SourceType
import com.savevault.app.data.StorageLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface BackupOutcome {
    data object Skipped : BackupOutcome
    data class Done(val size: Long, val files: Int, val warning: String? = null) : BackupOutcome
    data class Failed(val message: String) : BackupOutcome
}

class BackupManager(context: Context) {

    private val ctx = context.applicationContext
    private val repo = GameRepository.get(ctx)
    val settings = Settings(ctx)
    val drive = DriveClient(ctx, settings)

    companion object {
        /** Impede dois backups/restaurações simultâneos (manual + automático). */
        private val lock = Mutex()
        private val ROOT_EXCLUDES = setOf("cache", "code_cache", "lib", "no_backup")
        private val TS_FORMAT = "yyyy-MM-dd_HH-mm-ss"
        private val TS_REGEX = Regex("""(\d{4}-\d{2}-\d{2}_\d{2}-\d{2}-\d{2})\.zip$""")
    }

    fun localStore(): LocalStore {
        settings.localFolderUri?.let { uri ->
            return SafLocalStore(ctx, Uri.parse(uri))
        }
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "backups").apply { mkdirs() }
        return FileLocalStore(dir)
    }

    // ------------------------------------------------------------------ backup

    suspend fun backup(game: Game, force: Boolean): BackupOutcome = lock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(ctx.cacheDir, "tmp/${game.id}.zip").apply { parentFile?.mkdirs(); delete() }
            try {
                val fingerprint = fingerprint(game)
                if (!force && game.onlyIfChanged && fingerprint != null && fingerprint == game.lastFingerprint) {
                    return@withContext BackupOutcome.Skipped
                }

                val files = when (game.sourceType) {
                    SourceType.FOLDER -> Archiver.zipTree(safTree(game), tmp)
                    SourceType.ROOT -> zipWithRoot(game, tmp)
                }
                if (files == 0) throw IOException("A pasta do save está vazia")

                val now = System.currentTimeMillis()
                val fileName = "${safeName(game.name)}_${SimpleDateFormat(TS_FORMAT, Locale.US).format(Date(now))}.zip"
                val errors = ArrayList<String>()
                var savedSomewhere = false

                if (game.destination.local) {
                    runCatching {
                        val store = localStore()
                        store.save(safeName(game.name), game.shortId, fileName, tmp)
                        pruneLocal(store, game)
                        savedSomewhere = true
                    }.onFailure { errors += "Local: ${it.message}" }
                }
                if (game.destination.drive) {
                    runCatching {
                        drive.upload(tmp, fileName, game.id, now)
                        pruneDrive(game)
                        savedSomewhere = true
                    }.onFailure {
                        // A pasta pode ter sido apagada no Drive: força procurar/criar de novo.
                        settings.driveFolderId = null
                        errors += "Drive: ${it.message}"
                    }
                }

                if (!savedSomewhere) throw IOException(errors.joinToString("\n"))

                val warning = errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
                repo.update(game.id) {
                    it.copy(
                        lastBackupAt = now,
                        // Se uma das cópias falhou, não grava a impressão digital: o próximo
                        // backup automático tenta de novo.
                        lastFingerprint = if (warning == null) fingerprint else it.lastFingerprint,
                        lastError = warning,
                    )
                }
                BackupOutcome.Done(tmp.length(), files, warning)
            } catch (e: Exception) {
                val msg = e.message ?: e.javaClass.simpleName
                repo.update(game.id) { it.copy(lastError = msg) }
                BackupOutcome.Failed(msg)
            } finally {
                tmp.delete()
            }
        }
    }

    private fun safTree(game: Game): SafTree {
        val uri = game.sourceUri ?: throw IOException("Nenhuma pasta de save escolhida")
        return SafTree(ctx, Uri.parse(uri))
    }

    private fun fingerprint(game: Game): String? = when (game.sourceType) {
        SourceType.FOLDER -> Archiver.fingerprintTree(safTree(game))
        SourceType.ROOT -> {
            val path = rootPath(game)
            val prunes = ROOT_EXCLUDES.joinToString(" -o ") { "-path ${RootShell.q("$path/$it")}" }
            val r = RootShell.run(
                "find ${RootShell.q(path)} \\( $prunes \\) -prune -o -type f -print0 2>/dev/null " +
                    "| xargs -0 stat -c '%n %s %Y' 2>/dev/null",
            )
            if (r.out.isBlank()) null else Archiver.sha1(r.out.lines().sorted().joinToString("\n"))
        }
    }

    private fun rootPath(game: Game): String =
        game.rootPath?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: throw IOException("Caminho root não definido")

    private fun zipWithRoot(game: Game, out: File): Int {
        if (!RootShell.isAvailable()) throw IOException("Root não disponível (ou acesso negado ao SaveVault)")
        val src = rootPath(game)
        val stage = File(ctx.cacheDir, "stage/${game.id}")
        stage.deleteRecursively()
        stage.mkdirs()
        val uid = Process.myUid()
        val s = RootShell.q(stage.absolutePath)
        val r = RootShell.run(
            "[ -d ${RootShell.q(src)} ] || exit 3; " +
                "cp -r ${RootShell.q(src)}/. $s/ && " +
                ROOT_EXCLUDES.joinToString(" ") { "rm -rf $s/$it;" } +
                " chown -R $uid:$uid $s && chmod -R u+rwX $s",
        )
        try {
            if (r.code == 3) throw IOException("Pasta não encontrada: $src")
            if (!r.ok) throw IOException("Falha ao copiar com root: ${r.err.take(200)}")
            return Archiver.zipDir(stage, out, ROOT_EXCLUDES)
        } finally {
            stage.deleteRecursively()
        }
    }

    // ------------------------------------------------------------------ versões

    suspend fun listVersions(game: Game): Pair<List<BackupVersion>, List<String>> = withContext(Dispatchers.IO) {
        val versions = ArrayList<BackupVersion>()
        val errors = ArrayList<String>()
        runCatching { versions += listLocal(localStore(), game) }
            .onFailure { errors += "Local: ${it.message}" }
        if (settings.driveConnected) {
            runCatching { versions += drive.list(game.id) }
                .onFailure { errors += "Drive: ${it.message}" }
        }
        versions.sortedByDescending { it.timestamp } to errors
    }

    private fun listLocal(store: LocalStore, game: Game): List<BackupVersion> =
        store.list(game.shortId).map {
            BackupVersion(game.id, it.name, parseTs(it.name) ?: it.modified, it.size, StorageLocation.LOCAL, it.ref)
        }

    private fun parseTs(name: String): Long? = TS_REGEX.find(name)?.groupValues?.get(1)?.let {
        runCatching { SimpleDateFormat(TS_FORMAT, Locale.US).parse(it)?.time }.getOrNull()
    }

    private fun pruneLocal(store: LocalStore, game: Game) {
        listLocal(store, game).sortedByDescending { it.timestamp }
            .drop(game.keepVersions.coerceAtLeast(1))
            .forEach { runCatching { store.delete(it.ref) } }
    }

    private suspend fun pruneDrive(game: Game) {
        drive.list(game.id).sortedByDescending { it.timestamp }
            .drop(game.keepVersions.coerceAtLeast(1))
            .forEach { runCatching { drive.delete(it.ref) } }
    }

    suspend fun deleteVersion(version: BackupVersion) = withContext(Dispatchers.IO) {
        when (version.location) {
            StorageLocation.LOCAL -> localStore().delete(version.ref)
            StorageLocation.DRIVE -> drive.delete(version.ref)
        }
    }

    // ------------------------------------------------------------------ restaurar

    suspend fun restore(game: Game, version: BackupVersion) = lock.withLock {
        withContext(Dispatchers.IO) {
            val tmp = File(ctx.cacheDir, "tmp/restore_${game.id}.zip").apply { parentFile?.mkdirs(); delete() }
            try {
                when (version.location) {
                    StorageLocation.LOCAL -> localStore().open(version.ref).use { input ->
                        tmp.outputStream().use { input.copyTo(it) }
                    }
                    StorageLocation.DRIVE -> drive.download(version.ref, tmp)
                }
                when (game.sourceType) {
                    SourceType.FOLDER -> Archiver.unzipToTree(tmp, safTree(game))
                    SourceType.ROOT -> unzipWithRoot(game, tmp)
                }
                val fp = runCatching { fingerprint(game) }.getOrNull()
                repo.update(game.id) { it.copy(lastFingerprint = fp, lastError = null) }
            } finally {
                tmp.delete()
            }
        }
    }

    private fun unzipWithRoot(game: Game, zip: File) {
        if (!RootShell.isAvailable()) throw IOException("Root não disponível")
        val target = rootPath(game)
        val stage = File(ctx.cacheDir, "stage/restore_${game.id}")
        stage.deleteRecursively()
        try {
            Archiver.unzipToDir(zip, stage)
            val t = RootShell.q(target)
            val s = RootShell.q(stage.absolutePath)
            val stop = game.packageName?.let { "am force-stop ${RootShell.q(it)}; " } ?: ""
            val r = RootShell.run(
                stop +
                    "mkdir -p $t && owner=\$(stat -c '%u:%g' $t) && " +
                    "cp -r $s/. $t/ && chown -R \$owner $t && (restorecon -R $t 2>/dev/null; true)",
            )
            if (!r.ok) throw IOException("Falha ao restaurar com root: ${r.err.take(200)}")
        } finally {
            stage.deleteRecursively()
        }
    }

    private fun safeName(name: String): String =
        name.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().take(60).ifEmpty { "jogo" }
}
