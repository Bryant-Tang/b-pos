package io.github.bryanttang.bpos.data.local

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 升級測試：1 → 2 加了菜單快照那張表。
 *
 * 這支測試值得存在，是因為 [BposDatabase] 刻意沒有開 `fallbackToDestructiveMigration()`。
 * 升級腳本寫錯的話，店裡那台平板會在開啟資料庫時直接當掉，而且當下沒有任何辦法救它——
 * 店員手上是一塊磚，裡面還躺著沒送出去的單。
 *
 * 作法是自己手動建一個「第 1 版的資料庫」（不用 Room，因為程式裡的 Room 已經是第 2 版了），
 * 塞一筆還沒送出的意圖進去，然後用真正的 [BposDatabase] 打開它。Room 開啟時會拿
 * schema 的 identity hash 對一次，對不上就丟例外——所以只要這支測試綠的，
 * 就代表升級腳本產出的表跟 Room 期待的一模一樣。
 *
 * 測試資料全部是虛構的（CLAUDE.md 第一節）。
 */
@RunWith(RobolectricTestRunner::class)
class BposDatabaseMigrationTest {

    private lateinit var context: Context
    private lateinit var dbFile: String
    private var opened: BposDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dbFile = "migration-test.db"
        context.deleteDatabase(dbFile)
    }

    @After
    fun tearDown() {
        opened?.close()
        context.deleteDatabase(dbFile)
    }

    @Test
    fun `an unsent order intent survives the upgrade to version 2`() = runTest {
        createVersion1Database()

        val db = openWithMigration()
        val survivor = db.orderIntentOutboxDao().findById(INTENT_ID)

        assertEquals(INTENT_ID, survivor?.intentId)
        // 不是只檢查「那一列還在」——升級如果把欄位搬錯位，列還在但內容會是亂的，
        // 而這筆東西是店家收不到的錢。
        assertEquals("order_1", survivor?.orderId)
        assertEquals("dine_in", survivor?.orderType)
        assertEquals("pending", survivor?.state)
        assertEquals(3, survivor?.attempts)
    }

    @Test
    fun `the menu snapshot table exists and is usable after the upgrade`() = runTest {
        createVersion1Database()

        val db = openWithMigration()
        val dao = db.menuSnapshotDao()

        assertNull(dao.find(STORE_ID))

        dao.upsert(
            MenuSnapshotEntity(
                storeId = STORE_ID,
                version = 1_700_000_000_000,
                menuJson = """{"version":1700000000000}""",
                fetchedAtMillis = 1_700_000_001_000,
            ),
        )

        assertEquals(1_700_000_000_000, dao.find(STORE_ID)?.version)
    }

    private fun openWithMigration(): BposDatabase =
        Room.databaseBuilder(context, BposDatabase::class.java, dbFile)
            .addMigrations(BposDatabase.MIGRATION_1_2)
            .build()
            .also { opened = it }

    /**
     * 照 `schemas/…/1.json` 手工造一個第 1 版的資料庫。
     *
     * `room_master_table` 那一列是 Room 自己用來認「這個檔案是哪一版 schema」的，
     * 少了它 Room 會以為這是一個空的新資料庫、直接建表，升級腳本根本不會被執行——
     * 那樣這支測試會變成一直綠但什麼都沒測到。
     */
    private fun createVersion1Database() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbFile)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL(V1_OUTBOX_TABLE)
                        db.execSQL(V1_OUTBOX_INDEX)
                        db.execSQL(ROOM_MASTER_TABLE)
                        db.execSQL(
                            "INSERT OR REPLACE INTO room_master_table (id, identity_hash) " +
                                "VALUES (42, '$V1_IDENTITY_HASH')",
                        )
                    }

                    override fun onUpgrade(db: SupportSQLiteDatabase, old: Int, new: Int) = Unit
                })
                .build(),
        )

        helper.writableDatabase.use { db ->
            db.execSQL(
                """
                INSERT INTO order_intent_outbox
                    (intent_id, order_id, order_type, table_id, lines_json, created_by,
                     client_created_at, state, attempts, next_attempt_at, last_error)
                VALUES
                    ('$INTENT_ID', 'order_1', 'dine_in', 'table_1', '[]', 'uid_clerk_1',
                     1700000000000, 'pending', 3, 1700000060000, NULL)
                """.trimIndent(),
            )
        }
    }

    private companion object {
        const val INTENT_ID = "intent_1"
        const val STORE_ID = "store_demo"

        /**
         * 來自 `schemas/io.github.bryanttang.bpos.data.local.BposDatabase/1.json`。
         * 第 1 版已經發佈定案了，這個值不會再變。
         */
        const val V1_IDENTITY_HASH = "8e332a57f1adf22cb15286159deb9987"

        const val V1_OUTBOX_TABLE = "CREATE TABLE IF NOT EXISTS `order_intent_outbox` (" +
            "`intent_id` TEXT NOT NULL, `order_id` TEXT NOT NULL, `order_type` TEXT NOT NULL, " +
            "`table_id` TEXT, `lines_json` TEXT NOT NULL, `created_by` TEXT NOT NULL, " +
            "`client_created_at` INTEGER NOT NULL, `state` TEXT NOT NULL, " +
            "`attempts` INTEGER NOT NULL, `next_attempt_at` INTEGER NOT NULL, " +
            "`last_error` TEXT, PRIMARY KEY(`intent_id`))"

        const val V1_OUTBOX_INDEX =
            "CREATE INDEX IF NOT EXISTS `index_order_intent_outbox_state_next_attempt_at` " +
                "ON `order_intent_outbox` (`state`, `next_attempt_at`)"

        const val ROOM_MASTER_TABLE = "CREATE TABLE IF NOT EXISTS room_master_table " +
            "(id INTEGER PRIMARY KEY, identity_hash TEXT)"
    }
}
