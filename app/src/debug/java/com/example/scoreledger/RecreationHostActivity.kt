package com.example.scoreledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.scoreledger.data.ScoreRepository
import com.example.scoreledger.ui.*

/** Non-exported debug-only instrumentation host; never opens the user's persisted database. */
class RecreationHostActivity:ComponentActivity() {
    data class LayoutOverride(val widthDp:Int,val fontScale:Float)
    companion object { var testLayout:LayoutOverride?=null; var testRepository:ScoreRepository?=null; var testServices:UiServices?=null; var testRegistryOwner:androidx.activity.result.ActivityResultRegistryOwner?=null }
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
            val content:@Composable ()->Unit={
                if(owner==null) ScoreLedgerTheme { StarterScreen(model,services) }
                else CompositionLocalProvider(androidx.activity.compose.LocalActivityResultRegistryOwner provides owner) { ScoreLedgerTheme { StarterScreen(model,services) } }
            }
            val layout=testLayout
            if(layout==null) content()
            else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,layout.fontScale)) {
                Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter) {
                    Box(Modifier.widthIn(max=layout.widthDp.dp).fillMaxSize()) { content() }
                }
            }
        }
    }
}
