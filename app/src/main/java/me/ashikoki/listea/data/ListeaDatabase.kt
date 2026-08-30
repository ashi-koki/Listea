package me.ashikoki.listea.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

@Database(
    entities = [ListEntity::class, ListItemEntity::class],
    version = 6,
    exportSchema = false
)
abstract class ListeaDatabase : RoomDatabase() {

    abstract fun listsDao(): ListsDao

    companion object {
        /** V3 added the optional webhook action and its last-delivery record to each list. */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE lists ADD COLUMN webhookEnabled INTEGER NOT NULL DEFAULT 0"
                )
                connection.execSQL(
                    "ALTER TABLE lists ADD COLUMN webhookUrl TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL("ALTER TABLE lists ADD COLUMN lastDeliveryAt INTEGER")
                connection.execSQL("ALTER TABLE lists ADD COLUMN lastDeliveryStatus TEXT")
                connection.execSQL("ALTER TABLE lists ADD COLUMN lastDeliveryCode INTEGER")
                connection.execSQL("ALTER TABLE lists ADD COLUMN lastDeliveryError TEXT")
            }
        }

        /** V3.1 linked lists to a folder subtree and items to their source file. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE lists ADD COLUMN sourceRootUri TEXT")
                connection.execSQL("ALTER TABLE lists ADD COLUMN sourceRelativePath TEXT")
                connection.execSQL("ALTER TABLE list_items ADD COLUMN sourceUri TEXT")
                connection.execSQL("ALTER TABLE list_items ADD COLUMN sourceRelativePath TEXT")
            }
        }

        /** V3.3 lets a re-sync flag an item whose source file has vanished, without deleting it. */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN sourceMissing INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        /** V3.4 remembers where swipe Review left off, by item id. */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE lists ADD COLUMN reviewCurrentItemId INTEGER")
            }
        }

        /**
         * V3.5 adds the per-item review actions. Purely additive with a false default, so every
         * existing item survives unchanged and simply carries no actions.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0"
                )
                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN custom1 INTEGER NOT NULL DEFAULT 0"
                )
                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN custom2 INTEGER NOT NULL DEFAULT 0"
                )
            }
        }

        @Volatile
        private var instance: ListeaDatabase? = null

        fun get(context: Context): ListeaDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ListeaDatabase::class.java,
                    "listea.db"
                ).addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6
                )
                    .build()
                    .also { instance = it }
            }
    }
}
