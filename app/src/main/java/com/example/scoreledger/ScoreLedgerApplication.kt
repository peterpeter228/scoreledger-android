package com.example.scoreledger
import android.app.Application
import com.example.scoreledger.data.ScoreDatabase
import com.example.scoreledger.data.ScoreRepository
class ScoreLedgerApplication : Application() {
    val repository: ScoreRepository by lazy {
        ScoreRepository(ScoreDatabase.create(this), BuildConfig.INTERNAL_BETA)
    }
}
