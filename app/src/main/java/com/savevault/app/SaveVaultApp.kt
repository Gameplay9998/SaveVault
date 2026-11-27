package com.savevault.app

import android.app.Application
import com.savevault.app.backup.AutoBackupWorker

class SaveVaultApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AutoBackupWorker.createChannel(this)
        AutoBackupWorker.reschedule(this)
    }
}
