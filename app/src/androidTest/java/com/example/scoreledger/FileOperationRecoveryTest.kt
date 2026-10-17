package com.example.scoreledger

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.ui.FileOperationModel
import com.example.scoreledger.ui.FileStage
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileOperationRecoveryTest {
    @Test fun coldStartJournalIsInterruptedAndPickerStaleResultsAreRejected() {
        val context=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            val preferences=context.getSharedPreferences("file_operation_test",0)
            assertTrue(preferences.edit().putString("active","synthetic:WRITING").commit())
            val model=FileOperationModel();model.initialize(context,"test")
            assertEquals(FileStage.IDLE,model.stage)
            assertTrue(model.message.contains("结果未确认"))
            assertFalse(preferences.contains("active"))
            val first=requireNotNull(model.pick())
            assertNull(model.pick())
            assertFalse(model.pickerFinished("obsolete"))
            assertTrue(model.busy)
            model.pickerFailed(first)
            assertFalse(model.busy)
            val retry=requireNotNull(model.pick())
            assertNotEquals(first,retry)
            assertFalse(model.pickerFinished(first))
            assertTrue(model.pickerFinished(retry))
            assertFalse(model.busy)
        }finally{context.cleanup()}
    }
    @Test fun clearingViewModelDuringWriteKeepsInterruptedJournalForNextLaunch()=runBlocking {
        val context=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val store=ViewModelStore()
        try {
            val started=CompletableDeferred<Unit>();val stopped=CompletableDeferred<Unit>()
            lateinit var old:FileOperationModel
            withContext(Dispatchers.Main) {
                old=FileOperationModel();store.put("operation",old);old.initialize(context,"cancelled-write")
                old.run(FileStage.WRITING) {
                    started.complete(Unit)
                    try { awaitCancellation() } finally { stopped.complete(Unit) }
                }
            }
            withTimeout(5000){started.await()}
            assertTrue(context.getSharedPreferences("file_operation_cancelled-write",0).contains("active"))
            withContext(Dispatchers.Main){store.clear()}
            withTimeout(5000){stopped.await()}
            withContext(Dispatchers.Main) {
                assertFalse(old.busy)
                assertTrue(old.message.contains("结果未确认"))
                assertTrue(context.getSharedPreferences("file_operation_cancelled-write",0).contains("active"))
                val reopened=FileOperationModel();reopened.initialize(context,"cancelled-write")
                assertFalse(reopened.busy)
                assertTrue(reopened.message.contains("上次操作被中断"))
                assertNotNull(reopened.pick())
            }
        }finally{withContext(Dispatchers.Main){store.clear()};context.cleanup()}
    }

}
