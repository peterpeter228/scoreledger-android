package com.example.scoreledger
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.Room
import com.example.scoreledger.data.*
import com.example.scoreledger.core.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Instrumented source only; run on a real Android runtime before marking this passed. */
@RunWith(AndroidJUnit4::class)
class DatabaseSmokeTest {
    @Test fun savesAndDeletesOneAtomicExam() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,ScoreDatabase::class.java).build()
        try {
            val repo=ScoreRepository(db,true);repo.ensureDefaultSubjects()
            val child=repo.addChild("测试孩子")
            val s=db.dao().subjects().first();val id=UUID.randomUUID().toString()
            repo.saveNewExam(id,child,"测试记录",LocalDate.now().toString(),listOf(ScoreValue(s.id,ScoreStatus.SCORED,8000,10000)))
            assertEquals(1,db.dao().examCount())
            repo.deleteExam(id,child);assertEquals(0,db.dao().examCount())
        } finally { db.close() }
    }
}
