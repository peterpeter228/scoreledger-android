package com.example.scoreledger
import android.app.Application
import com.example.scoreledger.data.ScoreDatabase
import com.example.scoreledger.data.ScoreRepository
class ScoreLedgerApplication : Application() {
    val reportManager by lazy { com.example.scoreledger.export.ReportManager(this,repository) }
    val backupManager by lazy { com.example.scoreledger.backup.BackupManager(this,repository) }
    val repository: ScoreRepository by lazy {
        ScoreRepository(ScoreDatabase.create(this), BuildConfig.INTERNAL_BETA)
    }
}
