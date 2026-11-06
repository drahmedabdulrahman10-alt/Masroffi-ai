package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CategoryEntity::class,
        TransactionEntity::class,
        BudgetEntity::class,
        CategoryRuleEntity::class
    ],
    version = 2,
    exportSchema = false
)
@TypeConverters(RoomConverters::class)
abstract class MasroofiDatabase : RoomDatabase() {

    abstract fun dao(): MasroofiDao

    companion object {
        @Volatile
        private var INSTANCE: MasroofiDatabase? = null

        /**
         * Explicit non-destructive migration from version 1 to version 2.
         * Preserves all user records while ensuring the isRefundOffset column and indexes exist.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE transactions ADD COLUMN isRefundOffset INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        fun getInstance(context: Context): MasroofiDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context.applicationContext).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): MasroofiDatabase {
            return Room.databaseBuilder(
                context,
                MasroofiDatabase::class.java,
                "masroofi_finance.db"
            )
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
