package com.savevault.app.backup

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.savevault.app.R
import com.savevault.app.data.GameRepository
import com.savevault.app.data.Settings
import java.util.concurrent.TimeUnit

/** Roda periodicamente e faz backup de todos os jogos com "backup automático" ligado. */
class AutoBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val manager = BackupManager(applicationContext)
        val games = GameRepository.get(applicationContext).games.value.filter { it.autoBackup }
        var done = 0
        val failed = ArrayList<String>()
        for (game in games) {
            when (val r = manager.backup(game, force = false)) {
                is BackupOutcome.Done -> {
                    done++
                    r.warning?.let { failed += "${game.name}: $it" }
                }
                is BackupOutcome.Failed -> failed += "${game.name}: ${r.message}"
                BackupOutcome.Skipped -> Unit
            }
        }
        manager.settings.lastAutoRun = System.currentTimeMillis()
        if (done > 0 || failed.isNotEmpty()) notify(applicationContext, done, failed)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "auto_backup"
        const val CHANNEL_ID = "backups"

        fun createChannel(context: Context) {
            val ch = NotificationChannel(CHANNEL_ID, "Backups", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }

        /** (Re)agenda o trabalho de acordo com as configurações e os jogos cadastrados. */
        fun reschedule(context: Context) {
            val settings = Settings(context)
            val wm = WorkManager.getInstance(context)
            val games = GameRepository.get(context).games.value.filter { it.autoBackup }
            if (!settings.autoEnabled || games.isEmpty()) {
                wm.cancelUniqueWork(WORK_NAME)
                return
            }
            val usesDrive = games.any { it.destination.drive }
            val network = when {
                !usesDrive -> NetworkType.NOT_REQUIRED
                settings.wifiOnly -> NetworkType.UNMETERED
                else -> NetworkType.CONNECTED
            }
            val constraints = Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                .setRequiredNetworkType(network)
                .build()
            val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(
                settings.autoIntervalHours.toLong().coerceAtLeast(1), TimeUnit.HOURS,
            ).setConstraints(constraints).build()
            wm.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        @android.annotation.SuppressLint("MissingPermission") // checado logo abaixo
        private fun notify(context: Context, done: Int, failed: List<String>) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) return
            val title = when {
                failed.isEmpty() -> "Backup automático: $done jogo(s) salvo(s)"
                done == 0 -> "Backup automático falhou"
                else -> "Backup automático: $done salvo(s), ${failed.size} com problema"
            }
            val text = failed.joinToString("\n").ifEmpty { "Tudo certo." }
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_backup)
                .setContentTitle(title)
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(1001, n)
        }
    }
}
