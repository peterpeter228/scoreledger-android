package com.example.scoreledger

import android.graphics.Bitmap
import android.graphics.Rect
import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.os.Handler
import android.os.Looper
import android.view.Window
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.data.*
import com.example.scoreledger.core.ScoreStatus
import com.example.scoreledger.core.ScoreValue
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Only synthetic isolated data and managers. Does not clear or mutate the app database. */
@RunWith(AndroidJUnit4::class)
class SyntheticJourneyTest {
    @get:Rule val compose=createEmptyComposeRule()
    private fun item(list:String,matcher:SemanticsMatcher):SemanticsNodeInteraction {
        compose.onNodeWithTag(list).performScrollToNode(matcher)
        return compose.onNode(matcher).performScrollTo()
    }
    /** Room-backed commands outlive Compose idleness. Await the actual destination and
     * enabled save action, not elapsed time or only a database row count. */
    private fun awaitEditorReady() {
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag("entry-list").fetchSemanticsNodes().size==1 &&
                compose.onAllNodes(hasTestTag("save-record") and isEnabled()).fetchSemanticsNodes().size==1
        }
        compose.onNodeWithTag("entry-list").assertExists()
        compose.onNodeWithTag("save-record").assertIsEnabled()
    }
    private fun awaitScreen(tag:String) {
        compose.waitUntil(10000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size==1 }
        compose.onNodeWithTag(tag).assertExists()
    }
    private fun awaitFocusedKeyboard(scenario:ActivityScenario<RecreationHostActivity>,tag:String) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val priorFlags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply {
            flags=flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        }
        try {
                compose.waitUntil(10000) {
                    var visible=false
                    scenario.onActivity {
                        val insets=ViewCompat.getRootWindowInsets(it.window.decorView)
                        visible=insets?.isVisible(WindowInsetsCompat.Type.ime())==true &&
                            (insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0)>0
                    }
                    visible && automation.windows.any { window ->
                        window.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD &&
                            Rect().also { window.getBoundsInScreen(it) }.let { !it.isEmpty && it.height()>0 }
                    } && compose.onAllNodes(hasTestTag(tag) and isFocused()).fetchSemanticsNodes().size==1
                }
        } finally { automation.serviceInfo=automation.serviceInfo.apply { flags=priorFlags } }
    }
    private fun assertEditable(node:SemanticsNodeInteraction,value:String) = node.assert(
        SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,
            androidx.compose.ui.text.AnnotatedString(value)))
    private fun assertSaveAboveVisibleIme(scenario:ActivityScenario<RecreationHostActivity>,scoreTag:String,captureName:String) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val flags=automation.serviceInfo.flags
        automation.serviceInfo=automation.serviceInfo.apply { this.flags=this.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS }
        try {
            fun keyboardBounds():Rect?=automation.windows.firstOrNull { it.type==AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?.let { window -> Rect().also { window.getBoundsInScreen(it) } }?.takeUnless { it.isEmpty }
            var visible=false
            var bottom=0
            fun readInsets() {
                scenario.onActivity { activity ->
                    val insets=ViewCompat.getRootWindowInsets(activity.window.decorView)
                    visible=insets?.isVisible(WindowInsetsCompat.Type.ime())==true
                    bottom=insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                }
            }
            compose.waitUntil(10000) { readInsets();visible && bottom>0 && keyboardBounds()!=null }
            fun assertCurrentKeyboardGeometry() {
                readInsets()
                assertTrue("IME must remain visible with positive inset at capture",visible && bottom>0)
                val ime=requireNotNull(keyboardBounds()) { "Real input-method window disappeared before capture" }
                assertTrue("Actual keyboard must have positive height",ime.height()>0)
                val offset=IntArray(2);scenario.onActivity { it.window.decorView.getLocationOnScreen(offset) }
                val save=compose.onNodeWithTag("save-record").assertIsDisplayed().fetchSemanticsNode().boundsInWindow
                val score=compose.onNodeWithTag(scoreTag).assertIsDisplayed().fetchSemanticsNode().boundsInWindow
                assertTrue("Save action overlaps real keyboard",save.bottom+offset[1]<=ime.top+1)
                assertTrue("Focused score overlaps real keyboard",score.bottom+offset[1]<=ime.top+1)
            }
            assertCurrentKeyboardGeometry()
            // Recheck after completed draw frames, immediately before screenshot, not just
            // before an animation that can remove the keyboard again.
            captureSyntheticUi(captureName,scenario) { assertCurrentKeyboardGeometry() }
        } finally { automation.serviceInfo=automation.serviceInfo.apply { this.flags=flags } }
    }
    private fun awaitRenderedFrames(scenario:ActivityScenario<RecreationHostActivity>) {
        compose.waitForIdle()
        // Match genuine-Main evidence: semantics/layout completion is not rendered-surface completion.
        val rendered=CountDownLatch(2)
        val listener=Window.OnFrameMetricsAvailableListener { window,_,_->
            rendered.countDown()
            if(rendered.count>0) window.decorView.postInvalidateOnAnimation()
        }
        scenario.onActivity { activity ->
            activity.window.addOnFrameMetricsAvailableListener(listener,Handler(Looper.getMainLooper()))
            activity.window.decorView.postInvalidateOnAnimation()
        }
        try { check(rendered.await(5,TimeUnit.SECONDS)) { "Destination frames did not render before screenshot" } }
        finally { scenario.onActivity { it.window.removeOnFrameMetricsAvailableListener(listener) } }
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100,3000)
    }
    /** Diagnostic evidence only; image existence is not a visual correctness assertion. */
    private fun captureSyntheticUi(name:String,scenario:ActivityScenario<RecreationHostActivity>,beforeCapture:(()->Unit)?=null) {
        awaitRenderedFrames(scenario)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.filesDir,"ui-evidence")
        check(directory.isDirectory || directory.mkdirs())
        beforeCapture?.invoke()
        val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(directory,"synthetic-$name.png").outputStream().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG,100,output))
            }
        } finally { bitmap.recycle() }
    }
    /** Capture before ActivityScenario closes; values are booleans/counts/enums only.
     * The semantics tree belongs to this synthetic isolated fixture, never the user's database. */
    private fun captureFailure(scenario:ActivityScenario<RecreationHostActivity>,repository:ScoreRepository) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val directory=File(instrumentation.targetContext.filesDir,"ui-evidence").apply { mkdirs() }
        runCatching {
            val bitmap=requireNotNull(instrumentation.uiAutomation.takeScreenshot())
            try { File(directory,"synthetic-failure.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) } } finally { bitmap.recycle() }
        }
        runCatching { File(directory,"synthetic-failure-semantics.txt").writeText(compose.onRoot(useUnmergedTree=true).printToString()) }
        runCatching {
            val state=org.json.JSONObject()
            scenario.onActivity { activity -> activity.diagnosticModel?.let { model ->
                state.put("editorPresent",model.editor.value!=null).put("editingExisting",model.editor.value?.editing==true)
                    .put("saving",model.saving.value).put("draftSaveState",model.draftSaveState.value.name)
                    .put("errorPresent",model.error.value!=null).put("conflict",model.draftConflict.value)
                    .put("selectedChildPresent",model.selectedChild.value!=null)
                    .put("editorBelongsToSelectedChild",model.editor.value?.childId==model.selectedChild.value)
                    .put("visibleExamCount",model.exams.value.size).put("draftCount",model.drafts.value.size)
                    .put("appliedTitleSearchPresent",model.recordQuery.value.titleSearch.isNotBlank())
            } }
            val snapshot=runBlocking { repository.snapshot() }
            state.put("storedExamCount",snapshot.exams.size).put("storedScoreCount",snapshot.scores.size)
                .put("storedDraftsForExistingRecords",runBlocking { snapshot.exams.count { repository.draft(it.id,it.childId)!=null } })
            File(directory,"synthetic-failure-state.json").writeText(state.toString(2))
        }
    }
    @Test fun emptyInstallCreateTemplateEnterMixedStatesEditAndIsolateChildren() {
        val isolated=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build();val repository=ScoreRepository(db,true)
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=isolated.services(repository)
        try { ActivityScenario.launch(RecreationHostActivity::class.java).use { scenario ->
            try {
            awaitScreen("home-list")
            compose.onNodeWithTag("first-nickname").performClick()
            // Inject text only after the real keyboard/input session is ready. Sending an
            // edit during IME startup can race its initial empty InputConnection snapshot.
            awaitFocusedKeyboard(scenario,"first-nickname")
            compose.onNodeWithTag("first-nickname").assertIsFocused().performTextInput("合成孩子甲")
            assertEditable(compose.onNodeWithTag("first-nickname"),"合成孩子甲")
            compose.onNodeWithTag("new-record").assertIsEnabled()
            // A real Back closes the nickname keyboard for the default-layout evidence only.
            // The later score-typing checkpoint explicitly opens and verifies the IME again.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10000) {
                var visible=true
                scenario.onActivity { visible=ViewCompat.getRootWindowInsets(it.window.decorView)?.isVisible(WindowInsetsCompat.Type.ime())==true }
                !visible
            }
            // Never repair lost input by retyping: retain the exact local value across Back.
            compose.onNodeWithTag("first-nickname").assert(hasText("合成孩子甲",substring=false))
            compose.onNodeWithTag("new-record").assertIsEnabled().performClick()
            compose.waitUntil(10000){runBlocking{db.dao().childCount()==1 && db.dao().subjectCount()==3}}
            awaitEditorReady()
            val subjects=runBlocking{db.dao().subjects()}.associateBy{it.name}
            val orderedSubjects=subjects.values.sortedBy { it.sortOrder }
            // Put all three compact fields in view without expanding metadata. Validate geometry,
            // rather than treating an enabled save action as proof that score rows have rendered.
            item("entry-list",hasTestTag("score-${orderedSubjects.last().id}")).assertIsDisplayed()
            var previousBounds:List<androidx.compose.ui.geometry.Rect>?=null
            var stableLayouts=0
            compose.waitUntil(10000) {
                val viewport=compose.onNodeWithTag("entry-list").fetchSemanticsNode().boundsInRoot
                val bounds=orderedSubjects.map { subject ->
                    compose.onAllNodesWithTag("score-${subject.id}").fetchSemanticsNodes().singleOrNull()?.boundsInRoot
                }
                if(bounds.all { it!=null && it.height>0f && it.top>=viewport.top && it.bottom<=viewport.bottom }) {
                    val ready=bounds.filterNotNull()
                    stableLayouts=if(ready==previousBounds) stableLayouts+1 else 0
                    previousBounds=ready
                    stableLayouts>=2
                } else { stableLayouts=0;false }
            }
            orderedSubjects.forEach { compose.onNodeWithTag("score-${it.id}").assertIsDisplayed() }
            compose.onNodeWithTag("save-record").assertIsDisplayed()
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            captureSyntheticUi("00-default-three-subject-entry",scenario)
            item("entry-list",hasText("修改考试信息")).performClick()
            item("entry-list",hasText("考试名称")).performTextReplacement("合成混合状态考试")
            item("entry-list",hasText("本次年级（可选）")).performTextReplacement("合成七年级")
            item("entry-list",hasTestTag("score-${subjects.getValue("语文").id}")).performTextReplacement("0")
            item("entry-list",hasTestTag("score-${subjects.getValue("数学").id}")).performTextReplacement("８５．５")
            item("entry-list",hasTestTag("subject-options-${subjects.getValue("英语").id}")).performClick()
            item("entry-list",hasText("缺考") and hasAnyAncestor(hasTestTag("subject-${subjects.getValue("英语").id}"))).performClick()
            item("entry-list",hasTestTag("score-${subjects.getValue("语文").id}")).assertExists()
            compose.onNodeWithTag("score-${subjects.getValue("语文").id}").performClick()
            assertSaveAboveVisibleIme(scenario,"score-${subjects.getValue("语文").id}","01-entry-before-save")
            compose.onNodeWithTag("save-record").performClick()
            compose.waitUntil(10000){runBlocking{db.dao().examCount()==1}}
            compose.waitUntil(10000){compose.onAllNodesWithTag("detail-list").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("saved-record-notice").assertExists()
            val first=runBlocking{repository.snapshot()};val scores=first.scores.associateBy{it.subjectId}
            assertEquals(0L,scores.getValue(subjects.getValue("语文").id).scoreMinor)
            assertEquals(8550L,scores.getValue(subjects.getValue("数学").id).scoreMinor)
            assertEquals("ABSENT",scores.getValue(subjects.getValue("英语").id).status)
            assertEquals("合成七年级",first.exams.single().gradeSnapshot)
            item("detail-list",hasText("合成混合状态考试")).assertExists()
            captureSyntheticUi("02-history-after-save",scenario)
            val editAction=item("detail-list",hasText("编辑这次记录")).assertIsEnabled()
            captureSyntheticUi("02b-edit-target-before-click",scenario)
            editAction.performClick()
            awaitEditorReady()
            val editDraft=runBlocking { repository.draft(first.exams.single().id,first.exams.single().childId) }
            assertNotNull("Edit must reopen the same persisted record",editDraft)
            assertTrue(EntryDraft.decode(requireNotNull(editDraft)).editing)
            assertEquals(first.exams.single().id,EntryDraft.decode(requireNotNull(editDraft)).id)
            item("entry-list",hasText("修改考试信息")).performClick()
            item("entry-list",hasText("考试名称")).performTextReplacement("合成纠正标题")
            item("entry-list",hasText("本次年级（可选）")).performTextReplacement("合成八年级")
            item("entry-list",hasTestTag("score-${subjects.getValue("数学").id}")).performTextReplacement("86.25")
            compose.onNodeWithTag("save-record").performClick()
            compose.waitUntil(10000){runBlocking{db.dao().allExams().single().title=="合成纠正标题"}}
            assertEquals(first.exams.single().id,runBlocking{db.dao().allExams().single().id})
            awaitScreen("detail-list")
            val corrected=runBlocking { repository.snapshot() }
            assertEquals(1,corrected.exams.size)
            assertEquals("合成八年级",corrected.exams.single().gradeSnapshot)
            assertEquals(8625L,corrected.scores.single { it.subjectId==subjects.getValue("数学").id }.scoreMinor)
            item("detail-list",hasText("本次年级：合成八年级",substring=true)).assertExists()
            item("detail-list",hasText("得分 86.25")).assertExists()
            item("detail-list",hasText("返回记录")).performClick()
            // Create another synthetic child in the same isolated repository; exercise UI scope switch.
            runBlocking{repository.addChild("合成孩子乙")}
            compose.onNodeWithText("合成孩子甲").performClick()
            compose.waitUntil(10000){compose.onAllNodesWithText("合成孩子乙").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("合成孩子乙").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 && compose.onAllNodesWithText("还没有记录。先记下最近一次考试。").fetchSemanticsNodes().size==1 }
            compose.onNodeWithText("还没有记录。先记下最近一次考试。").assertExists()
            compose.onNodeWithText("合成纠正标题").assertDoesNotExist()
            captureSyntheticUi("03-child-isolation",scenario)
            compose.onNodeWithText("合成孩子乙").performClick()
            compose.onNodeWithText("合成孩子甲").performClick()
            compose.waitUntil(10000) { compose.onAllNodesWithTag("home-list").fetchSemanticsNodes().size==1 && compose.onAllNodesWithText("合成纠正标题").fetchSemanticsNodes().isNotEmpty() }
            item("home-list",hasText("合成纠正标题")).assertExists()
            compose.onNodeWithTag("new-record").performClick()
            awaitEditorReady()
            val resumed=runBlocking{db.dao().allExams().single()}
            assertEquals("合成八年级",resumed.gradeSnapshot)
            // Real UI reuse must not inherit prior score or absence status.
            subjects.values.forEach { subject ->
                item("entry-list",hasTestTag("score-${subject.id}")).assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText,androidx.compose.ui.text.AnnotatedString("")))
                item("entry-list",hasTestTag("score-${subject.id}")).assertIsEnabled()
            }
            item("entry-list",hasTestTag("score-${subjects.getValue("语文").id}")).performTextReplacement("99.99")
            item("entry-list",hasTestTag("score-${subjects.getValue("语文").id}")).performImeAction()
            compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("score-${subjects.getValue("数学").id}") and isFocused()).fetchSemanticsNodes().size==1 }
            compose.onNodeWithTag("score-${subjects.getValue("数学").id}").assertIsFocused().performTextReplacement("８５．５")
            compose.onNodeWithTag("score-${subjects.getValue("数学").id}").performImeAction()
            compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("score-${subjects.getValue("英语").id}") and isFocused()).fetchSemanticsNodes().size==1 }
            compose.onNodeWithTag("score-${subjects.getValue("英语").id}").assertIsFocused().performImeAction()
            captureSyntheticUi("04-continuous-entry",scenario)
            compose.onNodeWithTag("save-record").performClick()
            compose.waitUntil(10000){runBlocking{db.dao().examCount()==2}}
            compose.waitUntil(10000){compose.onAllNodesWithTag("detail-list").fetchSemanticsNodes().isNotEmpty()}
            val second=runBlocking{repository.snapshot()}.exams.first{it.id!=first.exams.single().id}
            val secondScores=runBlocking{repository.snapshot()}.scores.filter{it.examId==second.id}.associateBy{it.subjectId}
            assertEquals(9999L,secondScores.getValue(subjects.getValue("语文").id).scoreMinor)
            assertEquals("MISSING",secondScores.getValue(subjects.getValue("英语").id).status)
            item("detail-list",hasText("返回记录")).performClick()
            item("home-list",hasTestTag("all-records")).performClick()
            item("history-list",hasTestTag("filter-toggle")).performClick()
            item("history-list",hasTestTag("filter-search")).performClick()
            awaitFocusedKeyboard(scenario,"filter-search")
            compose.onNodeWithTag("filter-search").performTextInput("合成纠正标题")
            assertEditable(compose.onNodeWithTag("filter-search"),"合成纠正标题")
            // Finish typing with a genuine Back before scrolling to Apply. Otherwise the
            // keyboard's pending resize can move the target during pointer injection.
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10000) {
                var hidden=false
                scenario.onActivity {
                    val insets=ViewCompat.getRootWindowInsets(it.window.decorView)
                    hidden=insets!=null && !insets.isVisible(WindowInsetsCompat.Type.ime()) &&
                        insets.getInsets(WindowInsetsCompat.Type.ime()).bottom==0
                }
                hidden
            }
            assertEditable(compose.onNodeWithTag("filter-search"),"合成纠正标题")
            val apply=item("history-list",hasTestTag("apply-filters")).assertIsDisplayed().assertIsEnabled()
            val applyBounds=apply.fetchSemanticsNode().boundsInWindow
            awaitRenderedFrames(scenario)
            assertEquals("Apply target must remain stationary after rendering",applyBounds,apply.fetchSemanticsNode().boundsInWindow)
            apply.performClick()
            // The selected record exists with or without the filter. It is not evidence that
            // Apply ran: require the applied query and its one-record result before leaving.
            compose.waitUntil(10000) {
                var applied=false
                scenario.onActivity { applied=it.diagnosticModel?.recordQuery?.value?.titleSearch=="合成纠正标题" }
                applied && compose.onAllNodesWithTag("apply-filters").fetchSemanticsNodes().isEmpty()
            }
            item("history-list",hasText("名称含“合成纠正标题”",substring=true)).assertExists()
            item("history-list",hasText("1次考试")).assertExists()
            compose.onNodeWithTag("record-${second.id}").assertDoesNotExist()
            item("history-list",hasTestTag("record-${first.exams.single().id}")).performClick()
            item("detail-list",hasText("合成纠正标题")).assertExists()
            item("detail-list",hasText("返回记录")).performClick()
            awaitScreen("history-list")
            item("history-list",hasText("名称含“合成纠正标题”",substring=true)).assertExists()
            item("history-list",hasText("1次考试")).assertExists()
            captureSyntheticUi("05-filter-return",scenario)
            // Delete and undo through the real controls; compare the entire isolated database,
            // including the untouched second record and second child, not just a count.
            val beforeDelete=runBlocking { repository.snapshot() }
            val deletedId=first.exams.single().id
            item("history-list",hasTestTag("record-$deletedId")).performClick()
            item("detail-list",hasText("更多操作")).performClick()
            item("detail-list",hasText("删除记录")).performClick()
            compose.onNodeWithText("确认删除").performClick()
            compose.waitUntil(5000) {
                runBlocking { db.dao().allExams().none { it.id==deletedId } } &&
                    compose.onAllNodes(hasTestTag("undo-delete") and isEnabled()).fetchSemanticsNodes().size==1
            }
            assertTrue(runBlocking { db.dao().allScores().none { it.examId==deletedId } })
            compose.onNodeWithTag("undo-delete").performClick()
            compose.waitUntil(10000) { runBlocking { db.dao().allExams().any { it.id==deletedId } } }
            val afterUndo=runBlocking { repository.snapshot() }
            assertEquals(beforeDelete.children.toSet(),afterUndo.children.toSet())
            assertEquals(beforeDelete.subjects.toSet(),afterUndo.subjects.toSet())
            assertEquals(beforeDelete.exams.toSet(),afterUndo.exams.toSet())
            assertEquals(beforeDelete.scores.toSet(),afterUndo.scores.toSet())
            compose.waitUntil(10000) { compose.onAllNodesWithText("1次考试").fetchSemanticsNodes().size==1 }
            item("history-list",hasTestTag("record-$deletedId")).assertExists()
            } catch(failure:Throwable) { captureFailure(scenario,repository);throw failure }
        } }finally{RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup()}
    }
    @Test fun multiSelectAndCustomSubjectSaveReopenAndIsolatePopulatedChildren() {
        val isolated=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        val childA=runBlocking { repository.addChild("合成多科甲") }
        val math=runBlocking { repository.saveSubject(null,"数学",10000,0) }
        val physics=runBlocking { repository.saveSubject(null,"物理",10000,1) }
        val chemistry=runBlocking { repository.saveSubject(null,"化学",10000,2) }
        val originalId=UUID.randomUUID().toString()
        runBlocking { repository.saveNewExam(originalId,childA,"甲原有记录",LocalDate.now().minusDays(1).toString(),listOf(ScoreValue(math,ScoreStatus.SCORED,7500,10000))) }
        val original=runBlocking { repository.snapshot() }
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=isolated.services(repository)
        try { ActivityScenario.launch(RecreationHostActivity::class.java).use { scenario ->
            try {
                compose.waitUntil(10000) { compose.onAllNodesWithText("甲原有记录").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("new-record").performClick();awaitEditorReady()
                item("entry-list",hasText("修改考试信息")).performClick()
                item("entry-list",hasText("考试名称")).performTextReplacement("甲四科记录")
                item("entry-list",hasText("收起信息")).performClick()
                item("entry-list",hasText("添加科目",substring=false)).performClick()
                item("subject-picker-list",hasText("物理",substring=false)).performClick()
                item("subject-picker-list",hasText("化学",substring=false)).performClick()
                compose.onNodeWithText("添加所选科目").assertIsEnabled().performClick()
                item("entry-list",hasTestTag("score-$physics")).assertExists()
                item("entry-list",hasTestTag("score-$chemistry")).assertExists()
                item("entry-list",hasText("添加科目",substring=false)).performClick()
                compose.onNodeWithTag("custom-subject-name").performTextInput("科学探究")
                compose.onNodeWithText("添加新科目").performClick()
                compose.waitUntil(10000) { runBlocking { db.dao().subjects().any { it.name=="科学探究" } } }
                val custom=runBlocking { db.dao().subjects().single { it.name=="科学探究" } }
                compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("save-record") and isEnabled()).fetchSemanticsNodes().size==1 }
                val values=linkedMapOf(math to "88",physics to "90",chemistry to "91",custom.id to "92")
                values.forEach { (id,value) -> item("entry-list",hasTestTag("score-$id")).performTextReplacement(value) }
                captureSyntheticUi("06-multiselect-custom-before-save",scenario)
                compose.onNodeWithTag("save-record").performClick()
                compose.waitUntil(10000) { runBlocking { db.dao().examCount()==2 } }
                awaitScreen("detail-list")
                val saved=runBlocking { repository.snapshot() }
                val added=saved.exams.single { it.id!=originalId }
                assertEquals(childA,added.childId)
                assertEquals("甲四科记录",added.title)
                val addedScores=saved.scores.filter { it.examId==added.id }.associateBy { it.subjectId }
                assertEquals(values.keys,addedScores.keys)
                values.forEach { (id,value) ->
                    assertEquals(value.toLong()*100,addedScores.getValue(id).scoreMinor)
                    assertEquals(10000L,addedScores.getValue(id).maxMinor)
                    assertEquals("SCORED",addedScores.getValue(id).status)
                }
                assertEquals(original.exams.single(),saved.exams.single { it.id==originalId })
                assertEquals(original.scores.toSet(),saved.scores.filter { it.examId==originalId }.toSet())
                item("detail-list",hasText("返回记录")).performClick()
                item("home-list",hasTestTag("latest-record")).performClick()
                item("detail-list",hasText("科学探究",substring=false)).assertExists()
                item("detail-list",hasText("得分 92",substring=false)).assertExists()
                captureSyntheticUi("07-multiselect-custom-reopened",scenario)
                item("detail-list",hasText("返回记录")).performClick()
                val childB=runBlocking { repository.addChild("合成多科乙") }
                val bId=UUID.randomUUID().toString()
                runBlocking { repository.saveNewExam(bId,childB,"乙独立记录",LocalDate.now().toString(),listOf(ScoreValue(math,ScoreStatus.SCORED,4200,10000))) }
                val beforeSwitch=runBlocking { repository.snapshot() }
                compose.onNodeWithText("合成多科甲").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("合成多科乙").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("合成多科乙").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("乙独立记录").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("甲四科记录").assertDoesNotExist()
                item("home-list",hasTestTag("latest-record")).performClick()
                item("detail-list",hasText("得分 42",substring=false)).assertExists()
                compose.onNodeWithText("科学探究",substring=false).assertDoesNotExist()
                captureSyntheticUi("08-populated-child-isolation",scenario)
                item("detail-list",hasText("返回记录")).performClick()
                compose.onNodeWithText("合成多科乙").performClick()
                compose.onNodeWithText("合成多科甲").performClick()
                compose.waitUntil(10000) { compose.onAllNodesWithText("甲四科记录").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("乙独立记录").assertDoesNotExist()
                val afterSwitch=runBlocking { repository.snapshot() }
                assertEquals(beforeSwitch,afterSwitch)
            } catch(failure:Throwable) { captureFailure(scenario,repository);throw failure }
        } } finally { RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null;db.close();isolated.cleanup() }
    }

    @Test fun narrow320dpDoubleFontEntryCanScrollTypeSaveAndReopen() {
        val isolated=IsolatedUiContext(InstrumentationRegistry.getInstrumentation().targetContext)
        val db=Room.inMemoryDatabaseBuilder(isolated,ScoreDatabase::class.java).build()
        val repository=ScoreRepository(db,true)
        val oldLayout=RecreationHostActivity.testLayout
        RecreationHostActivity.testLayout=RecreationHostActivity.LayoutOverride(320,2f)
        RecreationHostActivity.testRepository=repository;RecreationHostActivity.testServices=isolated.services(repository)
        try { ActivityScenario.launch(RecreationHostActivity::class.java).use { scenario ->
            try {
                awaitScreen("home-list")
                item("home-list",hasTestTag("first-nickname")).performTextInput("合成窄屏孩子")
                compose.onNodeWithTag("new-record").assertIsDisplayed().performClick()
                awaitEditorReady()
                val subjects=runBlocking { db.dao().subjects() }.sortedBy { it.sortOrder }
                assertEquals(3,subjects.size)
                var pixelsPerDp=0f
                scenario.onActivity { pixelsPerDp=it.resources.displayMetrics.density }
                assertTrue("Actual entry viewport must be at most 320dp",compose.onNodeWithTag("entry-list").fetchSemanticsNode().boundsInRoot.width<=320*pixelsPerDp+1f)
                subjects.forEachIndexed { index,subject ->
                    val field=item("entry-list",hasTestTag("score-${subject.id}"))
                    field.assertIsDisplayed().performTextReplacement((70+index).toString())
                    val fieldBounds=field.fetchSemanticsNode().boundsInRoot
                    val listBounds=compose.onNodeWithTag("entry-list").fetchSemanticsNode().boundsInRoot
                    assertTrue("Score field must fit narrow viewport horizontally",fieldBounds.left>=listBounds.left && fieldBounds.right<=listBounds.right)
                    item("entry-list",hasTestTag("subject-options-${subject.id}")).assertIsDisplayed()
                }
                item("entry-list",hasTestTag("score-${subjects.last().id}")).performClick()
                assertSaveAboveVisibleIme(scenario,"score-${subjects.last().id}","09-narrow320-font200-entry-ime")
                compose.onNodeWithTag("save-record").assertIsDisplayed().performClick()
                compose.waitUntil(10000) { runBlocking { db.dao().examCount()==1 } }
                awaitScreen("detail-list")
                val saved=runBlocking { repository.snapshot() }
                assertEquals(3,saved.scores.size)
                subjects.forEachIndexed { index,subject -> assertEquals((70L+index)*100,saved.scores.single { it.subjectId==subject.id }.scoreMinor) }
                item("detail-list",hasText("得分 72",substring=false)).assertIsDisplayed()
                captureSyntheticUi("10-narrow320-font200-saved-detail",scenario)
                item("detail-list",hasText("返回记录")).performClick()
                item("home-list",hasTestTag("latest-record")).performClick()
                item("detail-list",hasText("得分 70",substring=false)).assertIsDisplayed()
                assertEquals(saved,runBlocking { repository.snapshot() })
            } catch(failure:Throwable) { captureFailure(scenario,repository);throw failure }
        } } finally {
            RecreationHostActivity.testLayout=oldLayout
            RecreationHostActivity.testRepository=null;RecreationHostActivity.testServices=null
            db.close();isolated.cleanup()
        }
    }

}
