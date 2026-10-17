package com.example.scoreledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scoreledger.data.ScoreRepository
import com.example.scoreledger.ui.*

/** Non-exported debug-only instrumentation host; never opens the user's persisted database. */
class RecreationHostActivity:ComponentActivity() {
    companion object { var testRepository:ScoreRepository?=null; var testServices:UiServices?=null; var testRegistryOwner:androidx.activity.result.ActivityResultRegistryOwner?=null }
    internal var diagnosticModel:ScoreViewModel?=null
        private set
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository=requireNotNull(testRepository)
        val services=requireNotNull(testServices) { "Isolated UI managers are mandatory in the test host" }
        setContent {
            val model:ScoreViewModel=viewModel(factory=object:ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T:ViewModel> create(modelClass:Class<T>):T=ScoreViewModel(repository) as T
            })
            SideEffect { diagnosticModel=model }
            val owner=testRegistryOwner
            if(owner==null) ScoreLedgerTheme { StarterScreen(model,services) }
            else androidx.compose.runtime.CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) { ScoreLedgerTheme { StarterScreen(model,services) } }
        }
    }
}
