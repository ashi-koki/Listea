package me.ashikoki.listea.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.execSQL

@Database(
    entities = [
        ListEntity::class,
        ListItemEntity::class,
        WebhookRecordEntity::class,
        FileReviewStateEntity::class
    ],
    version = 11,
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

        /**
         * V4.5 keeps the webhook bodies that never got through, so a misconfigured endpoint
         * costs a resend rather than a round of review. A new table only: nothing existing is
         * touched, and a database that has never failed a delivery simply has none of these.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS unsent_webhooks (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "event TEXT NOT NULL, " +
                        "listTitle TEXT NOT NULL, " +
                        "itemCount INTEGER NOT NULL, " +
                        "payload TEXT NOT NULL, " +
                        "failedAt INTEGER NOT NULL, " +
                        "reason TEXT NOT NULL)"
                )
            }
        }

        /**
         * V8 moves a review decision off the List row and onto the file it is about.
         *
         * Three steps, in one transaction, and the order matters. Items first learn their own
         * root-relative path — the identity a decision is stored against — computed from the
         * folder their list is linked to. Every decision already recorded then moves across to
         * that identity, so nothing a user has reviewed is lost. Finally the old columns are
         * cleared on source-backed rows, because leaving them populated would leave a second,
         * silently diverging answer to "is this checked" on disk.
         *
         * Manual items are untouched throughout: they have no file, they keep their columns, and
         * the WHERE clauses here all require a source path.
         *
         * Non-overlap means one file can appear in at most one list, so the copy across cannot
         * meet the same file twice. MAX() is used anyway — if a database from some earlier state
         * did hold a duplicate, taking "reviewed" over "not reviewed" loses nothing, whereas
         * failing the migration would lose the whole database.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE list_items ADD COLUMN rootRelativePath TEXT")
                connection.execSQL(
                    "UPDATE list_items SET rootRelativePath = (" +
                        "SELECT CASE WHEN l.sourceRelativePath IS NULL OR l.sourceRelativePath = ''" +
                        " THEN list_items.sourceRelativePath" +
                        " ELSE l.sourceRelativePath || '/' || list_items.sourceRelativePath END" +
                        " FROM lists l WHERE l.id = list_items.listId" +
                        ") WHERE sourceRelativePath IS NOT NULL"
                )
                connection.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_list_items_rootRelativePath " +
                        "ON list_items (rootRelativePath)"
                )

                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS file_review_state (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "rootUri TEXT NOT NULL, " +
                        "relativePath TEXT NOT NULL, " +
                        "isCompleted INTEGER NOT NULL DEFAULT 0, " +
                        "isFavorite INTEGER NOT NULL DEFAULT 0, " +
                        "custom1 INTEGER NOT NULL DEFAULT 0, " +
                        "custom2 INTEGER NOT NULL DEFAULT 0, " +
                        "sourceMissing INTEGER NOT NULL DEFAULT 0, " +
                        "updatedAt INTEGER NOT NULL)"
                )
                connection.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "index_file_review_state_rootUri_relativePath " +
                        "ON file_review_state (rootUri, relativePath)"
                )

                connection.execSQL(
                    "INSERT OR IGNORE INTO file_review_state (" +
                        "rootUri, relativePath, isCompleted, isFavorite, custom1, custom2, " +
                        "sourceMissing, updatedAt" +
                        ") SELECT l.sourceRootUri, i.rootRelativePath, " +
                        "MAX(i.isCompleted), MAX(i.isFavorite), MAX(i.custom1), MAX(i.custom2), " +
                        "MIN(i.sourceMissing), 0 " +
                        "FROM list_items i JOIN lists l ON l.id = i.listId " +
                        "WHERE l.sourceRootUri IS NOT NULL AND i.rootRelativePath IS NOT NULL " +
                        "GROUP BY l.sourceRootUri, i.rootRelativePath"
                )

                connection.execSQL(
                    "UPDATE list_items SET isCompleted = 0, isFavorite = 0, custom1 = 0, custom2 = 0 " +
                        "WHERE rootRelativePath IS NOT NULL"
                )
            }
        }

        /**
         * V9 caches each source file's size and modified time on its item row.
         *
         * Purely additive and nullable, so every existing item survives as "not measured yet" —
         * which the filter and the sort already know how to treat, and which any scan of the
         * linked folder fills in. Nothing is backfilled here: this migration has no folder to
         * read, and inventing a zero would be worse than admitting an absence.
         */
        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL("ALTER TABLE list_items ADD COLUMN sourceSizeBytes INTEGER")
                connection.execSQL("ALTER TABLE list_items ADD COLUMN sourceModifiedAt INTEGER")
            }
        }

        /**
         * V10 gives every item a public id, which is what the webhook now sends in place of the
         * row's sequence number. See [newItemPublicId] for the shape and the reasons.
         *
         * Additive columns, then a backfill, because an id has to exist for rows that were
         * written before the scheme did: a list built last month must keep working, and a
         * delivery that found an empty id would be worse than one that never had a good one.
         *
         * The backfill runs in Kotlin rather than SQL — the id is a base32 encoding over a hash,
         * and SQLite can do neither — so each row is read, given an id built from what it already
         * knows about itself, and written back. It is the same generator new rows use, so a
         * backfilled id is indistinguishable from a fresh one and fingerprints the same file the
         * same way.
         *
         * The time in a backfilled id is the row's own: an item's createdAt, a file state's
         * updatedAt. Neither is a lie, and both keep the ids sorting in the order the rows were
         * actually made. Where there is no usable time — a file_review_state row created by the
         * V8 migration, which had none to record and stored 0 — the migration's own clock stands
         * in, which is honest in its own way: that is when the row first became something the
         * outside world could name.
         */
        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(connection: SQLiteConnection) {
                val now = System.currentTimeMillis()

                connection.execSQL(
                    "ALTER TABLE list_items ADD COLUMN publicId TEXT NOT NULL DEFAULT ''"
                )
                connection.execSQL(
                    "ALTER TABLE file_review_state ADD COLUMN publicId TEXT NOT NULL DEFAULT ''"
                )

                backfill(
                    connection = connection,
                    read = "SELECT id, listId, title, rootRelativePath, createdAt FROM list_items",
                    update = "UPDATE list_items SET publicId = ? WHERE id = ?"
                ) { row ->
                    newItemPublicId(
                        itemIdentityOf(
                            rootRelativePath = if (row.isNull(3)) null else row.getText(3),
                            listId = row.getLong(1),
                            title = row.getText(2)
                        ),
                        row.getLong(4).takeIf { it > 0 } ?: now
                    )
                }

                backfill(
                    connection = connection,
                    read = "SELECT id, relativePath, updatedAt FROM file_review_state",
                    update = "UPDATE file_review_state SET publicId = ? WHERE id = ?"
                ) { row ->
                    newItemPublicId(
                        fileIdentity(row.getText(1)),
                        row.getLong(2).takeIf { it > 0 } ?: now
                    )
                }
            }
        }

        /**
         * Reads every row [read] returns, computes an id for it, and writes that id back.
         *
         * The whole read is drained into memory before a single update goes out. Stepping a
         * cursor over a table while updating that same table is the kind of thing SQLite is
         * entitled to have an opinion about, and an item table is thousands of rows, not
         * millions. [id] is expected to be the first column of [read].
         */
        private fun backfill(
            connection: SQLiteConnection,
            read: String,
            update: String,
            assign: (SQLiteStatement) -> String
        ) {
            val assigned = mutableListOf<Pair<Long, String>>()
            connection.prepare(read).use { row ->
                while (row.step()) assigned += row.getLong(0) to assign(row)
            }
            connection.prepare(update).use { write ->
                for ((rowId, publicId) in assigned) {
                    write.bindText(1, publicId)
                    write.bindLong(2, rowId)
                    write.step()
                    write.reset()
                }
            }
        }

        /**
         * V11 turns the unsent-payload store into a record of every delivery.
         *
         * The table stopped meaning what its name said. It held only the deliveries that went
         * wrong, so it could say what had failed and never what had gone out — and once an
         * automatic delivery started asking permission first, "the user said no" became a third
         * thing it had to be able to hold. A row is now one payload plus what became of it, so
         * the columns are renamed to match: `failedAt` was a lie the moment a success could land
         * in here, and `reason` reads oddly against one.
         *
         * Rebuilt rather than altered in place. SQLite can rename a column only from 3.25, which
         * is Android 11, and this app runs on 7 — so the honest names cost a copy. Every existing
         * row survives with its payload intact and keeps its place in the history.
         *
         * The old rows are classified from the words they already carry. Nothing but a failure or
         * a switched-off webhook could ever have been written, an empty payload was never kept,
         * and the switched-off case has always said so in the same fixed phrase — so the outcome
         * is recoverable exactly, without guessing.
         */
        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
                    "CREATE TABLE IF NOT EXISTS webhook_history (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "event TEXT NOT NULL, " +
                        "listTitle TEXT NOT NULL, " +
                        "itemCount INTEGER NOT NULL, " +
                        "payload TEXT NOT NULL, " +
                        "at INTEGER NOT NULL, " +
                        "outcome TEXT NOT NULL, " +
                        "detail TEXT NOT NULL)"
                )
                connection.execSQL(
                    "INSERT INTO webhook_history (" +
                        "id, event, listTitle, itemCount, payload, at, outcome, detail" +
                        ") SELECT id, event, listTitle, itemCount, payload, failedAt, " +
                        "CASE WHEN reason LIKE 'Nothing was sent%' THEN 'DISABLED' ELSE 'FAILED' END, " +
                        "reason FROM unsent_webhooks"
                )
                connection.execSQL("DROP TABLE IF EXISTS unsent_webhooks")
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
                    MIGRATION_5_6,
                    MIGRATION_6_7,
                    MIGRATION_7_8,
                    MIGRATION_8_9,
                    MIGRATION_9_10,
                    MIGRATION_10_11
                )
                    .build()
                    .also { instance = it }
            }
    }
}
