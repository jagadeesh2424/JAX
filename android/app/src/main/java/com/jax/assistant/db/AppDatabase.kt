package com.jax.assistant.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        TaskEntity::class,
        FactEntity::class,
        NotePageEntity::class,
        NoteBlockEntity::class,
        GoalEntity::class,
        ProjectEntity::class,
        HabitEntity::class,
        PageLinkEntity::class,
        ChatMessageEntity::class,
        AgentRunEntity::class,
        AgentEventEntity::class
    ],
    version = 7,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun factDao(): FactDao
    abstract fun noteDao(): NoteDao
    abstract fun goalDao(): GoalDao
    abstract fun projectDao(): ProjectDao
    abstract fun habitDao(): HabitDao
    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun agentRunDao(): AgentRunDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_pages` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `note_blocks` (" +
                        "`id` TEXT NOT NULL, `pageId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, `checked` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_note_blocks_pageId` ON `note_blocks` (`pageId`)")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `goals` (" +
                        "`id` TEXT NOT NULL, `title` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "`targetValue` INTEGER NOT NULL, `currentValue` INTEGER NOT NULL, " +
                        "`deadline` TEXT, `isCompleted` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `projects` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `description` TEXT NOT NULL, " +
                        "`status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `note_pages` ADD COLUMN `tags` TEXT NOT NULL DEFAULT ''")
            }
        }

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `habits` (" +
                        "`id` TEXT NOT NULL, `name` TEXT NOT NULL, `category` TEXT NOT NULL, " +
                        "`streak` INTEGER NOT NULL, `lastCompletedDate` TEXT, `createdAt` INTEGER NOT NULL, " +
                        "PRIMARY KEY(`id`))"
                )
            }
        }

        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `page_links` (" +
                        "`id` TEXT NOT NULL, `fromPageId` TEXT NOT NULL, `toPageId` TEXT NOT NULL, " +
                        "`createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_page_links_fromPageId` ON `page_links` (`fromPageId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_page_links_toPageId` ON `page_links` (`toPageId`)")
            }
        }

        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `chat_messages` (" +
                        "`id` TEXT NOT NULL, `text` TEXT NOT NULL, `isUser` INTEGER NOT NULL, " +
                        "`timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_runs` (" +
                        "`id` TEXT NOT NULL, `goal` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                        "`reply` TEXT NOT NULL, `toolsUsed` TEXT NOT NULL, " +
                        "`startedAt` INTEGER NOT NULL, `finishedAt` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `agent_events` (" +
                        "`id` TEXT NOT NULL, `runId` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                        "`detail` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, PRIMARY KEY(`id`))"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    com.jax.assistant.config.AppConfig.DATABASE_NAME
                ).addMigrations(
                    MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7
                )
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
