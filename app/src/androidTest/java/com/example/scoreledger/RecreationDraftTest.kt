package com.example.scoreledger

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.data.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecreationDraftTest {
    @get:Rule val compose=createEmptyComposeRule()
    @Test fun activityRecreationRetainsEditorAndPersistedDraft() {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        val draft=runBlocking { val child=repository.addChild("重建测试孩子");EntryDraft(childId=child,title="重建前标题").also{repository.putDraft(it)} }
        val isolated=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        RecreationHostActivity.testRepository=repository
        RecreationHostActivity.testServices=isolated.services(repository)
        try {
            ActivityScenario.launch(RecreationHostActivity::class.java).use { scenario ->
                compose.waitUntil(10000){compose.onAllNodesWithText("继续草稿：重建前标题").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("继续草稿：重建前标题").performClick()
                compose.waitUntil(10000){compose.onAllNodesWithText("重建前标题").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("重建前标题").performTextReplacement("旋转后仍保留")
                compose.waitUntil(10000){runBlocking{repository.draft(draft.id,draft.childId)?.let(EntryDraft::decode)?.title=="旋转后仍保留"}}
                scenario.recreate()
                compose.waitUntil(10000){compose.onAllNodesWithText("旋转后仍保留").fetchSemanticsNodes().isNotEmpty()}
                compose.onNodeWithText("旋转后仍保留").assertExists()
                compose.onNodeWithText("草稿已保存到本机").assertExists()
                assertEquals(draft.id,runBlocking{repository.draft(draft.id,draft.childId)}?.id)
                assertEquals(0,runBlocking{db.dao().examCount()})
            }
        }finally{RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;isolated.cleanup();db.close()}
    }
}
