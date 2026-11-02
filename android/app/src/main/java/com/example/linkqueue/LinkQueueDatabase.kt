
package com.example.linkqueue

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [SavedLink::class],
    version = 2,
    exportSchema = false
)
abstract class LinkQueueDatabase : RoomDatabase() {
    abstract fun savedLinkDao(): SavedLinkDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_links ADD COLUMN jobId TEXT")
                db.execSQL("ALTER TABLE saved_links ADD COLUMN status TEXT NOT NULL DEFAULT 'completed'")
                db.execSQL("ALTER TABLE saved_links ADD COLUMN error TEXT")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_saved_links_jobId ON saved_links(jobId)")
            }
        }

        @Volatile
        private var INSTANCE: LinkQueueDatabase? = null

        fun getInstance(context: Context): LinkQueueDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LinkQueueDatabase::class.java,
                    "linkqueue.db"
                ).addMigrations(MIGRATION_1_2)
                    .build().also { INSTANCE = it }
            }
        }
    }
}
