package com.example.scoreledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scoreledger.data.ScoreRepository
import com.example.scoreledger.ui.*

/** Non-exported debug-only instrumentation host; never opens the user's persisted database. */
class RecreationHostActivity:ComponentActivity() {
    companion object { var testRepository:ScoreRepository?=null }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val repository=requireNotNull(testRepository)
        setContent {
            val model:ScoreViewModel=viewModel(factory=object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T:ViewModel> create(modelClass:Class<T>):T=ScoreViewModel(repository) as T
            })
            ScoreLedgerTheme { StarterScreen(model) }
        }
    }
}
