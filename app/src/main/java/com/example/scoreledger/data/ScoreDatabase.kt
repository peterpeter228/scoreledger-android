package com.example.scoreledger.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [ChildEntity::class, SubjectEntity::class, ExamEntity::class, ScoreEntity::class, DraftEntity::class], version = 2, exportSchema = true)
abstract class ScoreDatabase : RoomDatabase() {
    abstract fun dao(): ScoreDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `drafts` (`id` TEXT NOT NULL, `childId` TEXT NOT NULL, `payloadJson` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`childId`) REFERENCES `children`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_drafts_childId` ON `drafts` (`childId`)")
            }
        }
        fun create(context: Context): ScoreDatabase =
            Room.databaseBuilder(context.applicationContext, ScoreDatabase::class.java, "scoreledger.db")
                // Never add fallbackToDestructiveMigration(). Export and test future migrations.
                .addMigrations(MIGRATION_1_2)
                .build()
    }
}
