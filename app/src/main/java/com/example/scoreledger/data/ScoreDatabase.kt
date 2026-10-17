package com.example.scoreledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ChildEntity::class, SubjectEntity::class, ExamEntity::class, ScoreEntity::class], version = 1, exportSchema = true)
abstract class ScoreDatabase : RoomDatabase() {
    abstract fun dao(): ScoreDao
    companion object {
        fun create(context: Context): ScoreDatabase =
            Room.databaseBuilder(context.applicationContext, ScoreDatabase::class.java, "scoreledger.db")
                // Never add fallbackToDestructiveMigration(). Export and test future migrations.
                .build()
    }
}
