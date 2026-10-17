package com.example.scoreledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.data.*
import com.example.scoreledger.ui.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic data only, isolated in-memory DB; no user application database touched. */
@RunWith(AndroidJUnit4::class)
class EntryNavigationTest {
    @get:Rule val compose=createComposeRule()
    private val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,ScoreDatabase::class.java).build()
    private val isolated=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
    @After fun close() { db.close();isolated.cleanup() }
    @Test fun childSwitchDoesNotExposeAnotherChildDraftAndResumeWorks() {
        val repository=ScoreRepository(db,true)
        val ids=runBlocking {
            val a=repository.addChild("虚构甲");val b=repository.addChild("虚构乙")
            repository.putDraft(EntryDraft(childId=a,title="甲专属草稿"));a to b
        }
        val model=ScoreViewModel(repository)
        compose.setContent { ScoreLedgerTheme { StarterScreen(model,isolated.services(repository)) } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("继续草稿：甲专属草稿").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("虚构甲").performClick()
        compose.onNodeWithText("虚构乙").performClick()
        compose.waitUntil(10000) { model.selectedChild.value==ids.second }
        compose.onNodeWithText("继续草稿：甲专属草稿").assertDoesNotExist()
        compose.onNodeWithText("虚构乙").performClick()
        compose.onNodeWithText("虚构甲").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("继续草稿：甲专属草稿").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("继续草稿：甲专属草稿").performClick()
        compose.waitUntil(10000) { model.editor.value!=null }
        compose.onNodeWithText("甲专属草稿").assertExists()
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithText("保留草稿并返回").performClick()
        compose.waitUntil(10000) { model.editor.value==null }
        compose.onNodeWithText("继续草稿：甲专属草稿").assertExists()
    }
}
