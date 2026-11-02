package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.DownloadTask

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt ASC")
    suspend fun getAll(): List<DownloadTask>

    @Upsert
    suspend fun upsert(task: DownloadTask)

    @Upsert
    suspend fun upsertAll(tasks: List<DownloadTask>)

    @Query("DELETE FROM downloads WHERE id IN (:ids)")
    suspend fun delete(ids: List<String>)
}

@Database(entities = [DownloadTask::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        /** Version 2 added a per-download target folder. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE downloads ADD COLUMN targetTreeUri TEXT NOT NULL DEFAULT ''")
            }
        }

        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "media_downloader.db")
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
