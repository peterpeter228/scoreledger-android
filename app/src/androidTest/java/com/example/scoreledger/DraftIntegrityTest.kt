package com.example.scoreledger

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.scoreledger.core.*
import com.example.scoreledger.data.*
import kotlinx.coroutines.*
import androidx.lifecycle.ViewModelStore
import com.example.scoreledger.ui.ScoreViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DraftIntegrityTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val clock=Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"),ZoneOffset.UTC)
    private fun database(name: String) = Room.databaseBuilder(context,ScoreDatabase::class.java,name).addMigrations(ScoreDatabase.MIGRATION_1_2).build()
    private suspend fun fixture(r: ScoreRepository): EntryDraft {
        val child=r.addChild("虚构测试甲")
        val subject=r.saveSubject(null,"测试科目",10000,0)
        return EntryDraft(childId=child,title="测试月考",date="2026-10-15",rows=listOf(DraftRow(subject,"测试科目","0","100",ScoreStatus.SCORED)))
    }
    @Test fun draftSurvivesCloseReopenAndIsChildBound() = runBlocking {
        val name="draft-${UUID.randomUUID()}.db";var db=database(name)
        try {
            var r=ScoreRepository(db,true,clock);val draft=fixture(r);val b=r.addChild("虚构测试乙")
            r.putDraft(draft);db.close();db=database(name);r=ScoreRepository(db,true,clock)
            assertEquals(draft,EntryDraft.decode(requireNotNull(r.draft(draft.id,draft.childId))))
            assertNull(r.draft(draft.id,b))
            var failed=false;try {r.putDraft(draft.copy(childId=b))} catch(_:IllegalStateException){failed=true}
            assertTrue(failed);assertEquals(0,db.dao().examCount())
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun successfulSubmitCleansDraftAndDuplicateSubmitIsIdempotent() = runBlocking {
        val name="draft-${UUID.randomUUID()}.db";val db=database(name)
        try {
            val r=ScoreRepository(db,true,clock);val draft=fixture(r)
            r.putDraft(draft);r.submitDraft(draft);r.submitDraft(draft)
            assertNull(r.draft(draft.id,draft.childId));assertEquals(1,db.dao().examCount());assertEquals(0L,db.dao().scores(draft.id).single().scoreMinor)
            val before=r.snapshot();val edit=draft.copy(editing=true,title="纠正标题")
            r.putDraft(edit);r.submitDraft(edit);assertEquals(1,db.dao().examCount())
            assertEquals(before.exams.single().createdAt,db.dao().exam(draft.id)!!.createdAt)
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun failedSubmitKeepsDraftAndRollsBackExam() = runBlocking {
        val name="draft-${UUID.randomUUID()}.db";val db=database(name)
        try {
            val r=ScoreRepository(db,true,clock);val draft=fixture(r);r.putDraft(draft)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_score BEFORE INSERT ON scores BEGIN SELECT RAISE(ABORT, 'injected'); END")
            var failed=false;try {r.submitDraft(draft)}catch(_:Exception){failed=true}
            assertTrue(failed);assertEquals(0,db.dao().examCount());assertEquals(draft,EntryDraft.decode(requireNotNull(r.draft(draft.id,draft.childId))))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_score");r.submitDraft(draft)
            assertEquals(1,db.dao().examCount());assertNull(r.draft(draft.id,draft.childId))
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun cleanupFailureRollsBackSuccessfulExamInsert() = runBlocking {
        val name="draft-${UUID.randomUUID()}.db";val db=database(name)
        try {
            val r=ScoreRepository(db,true,clock);val draft=fixture(r);r.putDraft(draft)
            db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_cleanup BEFORE DELETE ON drafts BEGIN SELECT RAISE(ABORT, 'injected'); END")
            var failed=false;try {r.submitDraft(draft)}catch(_:Exception){failed=true}
            assertTrue(failed);assertEquals(0,db.dao().examCount());assertNotNull(r.draft(draft.id,draft.childId))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_cleanup");r.submitDraft(draft)
            assertEquals(1,db.dao().examCount());assertNull(r.draft(draft.id,draft.childId))
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun staleSubmitCannotDeleteNewerDraftAndChildDeleteIsExplicit() = runBlocking {
        val name="draft-${UUID.randomUUID()}.db";val db=database(name)
        try {
            val r=ScoreRepository(db,true,clock);val draft=fixture(r);val newer=draft.copy(title="后写的草稿")
            r.putDraft(newer);var failed=false;try{r.submitDraft(draft)}catch(_:IllegalStateException){failed=true}
            assertTrue(failed);assertEquals(newer,EntryDraft.decode(requireNotNull(r.draft(draft.id,draft.childId))))
            r.deleteChild(draft.childId);assertNull(r.draft(draft.id,draft.childId));assertEquals(0,db.dao().childCount())
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun independentEditorsRejectStaleAutosaveCloseAndViewModelSubmit() = runBlocking {
        val name="editors-${UUID.randomUUID()}.db";val db=database(name)
        val store=ViewModelStore()
        try {
            val repository=ScoreRepository(db,true,clock);val initial=fixture(repository);repository.putDraft(initial)
            val row=requireNotNull(repository.draft(initial.id,initial.childId))
            lateinit var first: ScoreViewModel;lateinit var stale: ScoreViewModel
            withContext(Dispatchers.Main) {
                first=ScoreViewModel(ScoreRepository(db,true,clock));stale=ScoreViewModel(ScoreRepository(db,true,clock))
                store.put("first",first);store.put("stale",stale)
            }
            suspend fun await(condition: () -> Boolean) { withTimeout(10000) { while(!condition())delay(20) } }
            await { first.selectedChild.value==initial.childId && stale.selectedChild.value==initial.childId }
            withContext(Dispatchers.Main) { first.openDraft(row);stale.openDraft(row) }
            await { first.editor.value!=null && stale.editor.value!=null && !first.saving.value && !stale.saving.value }
            val newer=initial.copy(title="另一编辑器的新标题")
            withContext(Dispatchers.Main) { first.change(newer) }
            await { first.persistedPayload.value==newer.encode() }
            val unsaved=initial.copy(title="过期编辑器本地输入")
            withContext(Dispatchers.Main) { stale.change(unsaved) }
            await { stale.draftConflict.value }
            assertEquals(newer.encode(),repository.draft(initial.id,initial.childId)!!.payloadJson)
            assertEquals(unsaved,stale.editor.value)
            withContext(Dispatchers.Main) { stale.submit() }
            await { !stale.saving.value }
            assertEquals(0,db.dao().examCount());assertEquals(newer.encode(),repository.draft(initial.id,initial.childId)!!.payloadJson)
            assertEquals(unsaved,stale.editor.value)
            withContext(Dispatchers.Main) { stale.closeEditor(false) }
            await { !stale.saving.value }
            assertEquals(unsaved,stale.editor.value)
            withContext(Dispatchers.Main) { first.submit() }
            await { !first.saving.value }
            assertEquals("另一编辑器的新标题",db.dao().exam(initial.id)!!.title)
            // The stale editor cannot recreate a draft after the new version committed.
            withContext(Dispatchers.Main) { stale.submit() }
            await { !stale.saving.value }
            assertNull(repository.draft(initial.id,initial.childId))
            assertEquals("另一编辑器的新标题",db.dao().exam(initial.id)!!.title)

            // Deterministic local stale-callback regression within the existing method.
            // Room CAS conflicts above remain rejected; these are callbacks in ONE editor.
            val extra=repository.saveSubject(null,"第二合成科目",10000,1)
            val record=ExamWithScores(requireNotNull(db.dao().exam(initial.id)),db.dao().scores(initial.id))
            withContext(Dispatchers.Main) { first.edit(record) }
            await { first.editor.value!=null && !first.saving.value }
            withContext(Dispatchers.Main) {
                val start=requireNotNull(first.editor.value)
                first.change(start.copy(rows=start.rows+DraftRow(extra,"第二合成科目")),start)
            }
            await { first.editor.value?.rows?.size==2 && first.persistedPayload.value==first.editor.value?.encode() }
            val captured=requireNotNull(first.editor.value)
            withContext(Dispatchers.Main) {
                first.change(captured.copy(title="最新合成标题",rows=captured.rows.mapIndexed { index,row ->
                    if(index==0) row.copy(score="90",status=ScoreStatus.SCORED) else row }),captured)
                // Lifecycle/IME echo of its old displayed value must be a no-op.
                first.change(captured,captured)
                // A later unrelated callback based on the old frame cannot revert title/score.
                first.change(captured.copy(note="后续合成备注"),captured)
                first.change(captured.copy(rows=captured.rows.map { row ->
                    if(row.subjectId==extra) row.copy(score="70",status=ScoreStatus.SCORED) else row }),captured)
                val current=requireNotNull(first.editor.value)
                assertEquals("最新合成标题",current.title);assertEquals("后续合成备注",current.note)
                assertEquals("90",current.rows.first().score);assertEquals("70",current.rows.last().score)
                first.change(current.copy(title="真正的后续标题"),current)
                assertEquals("真正的后续标题",first.editor.value!!.title)
                first.removeRow(captured.rows.first().subjectId)
                val removed=requireNotNull(first.editor.value)
                first.change(captured.copy(rows=captured.rows.map { it.copy(score="5",status=ScoreStatus.SCORED) }),captured)
                assertEquals(removed,first.editor.value) // No resurrection from a deleted-row callback.
                first.undoRemoveRow()
                val restored=requireNotNull(first.editor.value)
                first.change(restored.copy(rows=restored.rows.reversed()),restored)
                val reordered=requireNotNull(first.editor.value)
                first.change(restored.copy(note="过期排序回调"),restored)
                assertEquals(reordered,first.editor.value) // No stale reordering or row-index mixing.
            }
            await { first.persistedPayload.value==first.editor.value?.encode() }
            assertEquals(first.editor.value,EntryDraft.decode(requireNotNull(repository.draft(initial.id,initial.childId))))
        } finally { withContext(Dispatchers.Main) { store.clear() };db.close();context.deleteDatabase(name) }
    }
    @Test fun generatedVersionOneSchemaMigratesWithoutLosingRows() = runBlocking {
        val name="migration-${UUID.randomUUID()}.db"
        val json=InstrumentationRegistry.getInstrumentation().context.assets.open("room-v1.json").bufferedReader().use{it.readText()}
        val schema=JSONObject(json).getJSONObject("database")
        val child=UUID.randomUUID().toString();val subject=UUID.randomUUID().toString();val exam=UUID.randomUUID().toString()
        context.openOrCreateDatabase(name,0,null).use { old ->
            val entities=schema.getJSONArray("entities")
            for(i in 0 until entities.length()) {
                val entity=entities.getJSONObject(i);val table=entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\u0024{TABLE_NAME}",table))
                // Real Room schema omits indices on tables with no secondary indexes.
                val indices=entity.optJSONArray("indices") ?: org.json.JSONArray()
                for(j in 0 until indices.length())old.execSQL(indices.getJSONObject(j).getString("createSql").replace("\u0024{TABLE_NAME}",table))
            }
            old.execSQL("INSERT INTO children VALUES (?, '迁移测试', '三年级', 0, 1, 1)",arrayOf(child))
            old.execSQL("INSERT INTO subjects (id,name,defaultMaxMinor,sortOrder,archived,createdAt,updatedAt) VALUES (?, '迁移科目', 10000, 0, 0, 1, 1)",arrayOf(subject))
            old.execSQL("INSERT INTO exams (id,childId,title,examDate,kind,term,gradeSnapshot,note,createdAt,updatedAt) VALUES (?, ?, '迁移考试', '2026-01-01', 'OTHER', '', '三年级', '', 1, 1)",arrayOf(exam,child))
            old.execSQL("INSERT INTO scores (examId,subjectId,subjectNameSnapshot,status,scoreMinor,maxMinor) VALUES (?, ?, '迁移科目', 'SCORED', 0, 10000)",arrayOf(exam,subject))
            old.version=1
        }
        val db=database(name)
        try {
            assertEquals("迁移测试",db.dao().child(child)!!.nickname)
            assertEquals("迁移科目",db.dao().subjects().single().name)
            assertEquals(exam,db.dao().allExams().single().id)
            assertEquals(0L,db.dao().scores(exam).single().scoreMinor)
            db.openHelper.readableDatabase.query("PRAGMA index_list(exams)").use { cursor ->
                val names=mutableListOf<String>();while(cursor.moveToNext())names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                assertTrue(names.contains("index_exams_childId_examDate"))
            }
            db.openHelper.readableDatabase.query("PRAGMA index_list(scores)").use { cursor ->
                val names=mutableListOf<String>();while(cursor.moveToNext())names.add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
                assertTrue(names.contains("index_scores_subjectId"))
            }
            val r=ScoreRepository(db,true,clock);val d=EntryDraft(childId=child);r.putDraft(d)
            assertEquals(d,EntryDraft.decode(requireNotNull(r.draft(d.id,child))))
            assertEquals(2,db.openHelper.readableDatabase.version)
        } finally {db.close();context.deleteDatabase(name)}
    }
    @Test fun queryNeverMixesChildrenAndTemplatesNeverUnarchive() = runBlocking {
        val name="draft-${UUID.randomUUID()}.db";val db=database(name)
        try {
            val r=ScoreRepository(db,true,clock);val d=fixture(r);r.putDraft(d);r.submitDraft(d)
            val e=requireNotNull(db.dao().exam(d.id));val record=ExamWithScores(e,db.dao().scores(d.id))
            assertTrue(RecordQuery(UUID.randomUUID().toString()).apply(listOf(record)).isEmpty())
            assertEquals(listOf(record),RecordQuery(d.childId,from="2026-10-15",through="2026-10-15").apply(listOf(record)))
            assertTrue(RecordQuery(d.childId,term="其他学期").apply(listOf(record)).isEmpty())
            assertEquals(listOf(record),RecordQuery(d.childId,through="9999-12-31").apply(listOf(record)))
            for (date in listOf("+10000-01-01","0000-01-01","2026-1-01","2026-02-30")) {
                var rejected=false;try { RecordQuery(d.childId,from=date).apply(listOf(record)) } catch(_:Exception) { rejected=true }
                assertTrue(rejected)
            }
            val s=db.dao().subjects().single();r.saveSubject(s.id,s.name,s.defaultMaxMinor,0,true)
            r.addSubjectTemplate(listOf(s.name));assertTrue(db.dao().subjects().single().archived)
        } finally {db.close();context.deleteDatabase(name)}
    }
}
