package com.example.data.local

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Upsert
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

@Database(entities = [DownloadTask::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        fun create(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "media_downloader.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
