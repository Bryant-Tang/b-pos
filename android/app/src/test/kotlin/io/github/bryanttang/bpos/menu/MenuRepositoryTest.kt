package io.github.bryanttang.bpos.menu

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bryanttang.bpos.data.local.BposDatabase
import io.github.bryanttang.bpos.data.local.MenuSnapshotDao
import io.github.bryanttang.bpos.data.local.MenuSnapshotEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * 方法名一律用 ASCII（見 MenuMappingTest 的說明）。資料全部是虛構的。
 *
 * 用真的 Room（記憶體內）而不是假的 DAO：REPLACE 的行為、主鍵分店的行為
 * 都是這一層要靠的東西，用假 DAO 等於把要測的東西自己實作一遍。
 */
@RunWith(RobolectricTestRunner::class)
class MenuRepositoryTest {

    private lateinit var db: BposDatabase
    private lateinit var dao: MenuSnapshotDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            BposDatabase::class.java,
        ).build()
        dao = db.menuSnapshotDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `the first open fetches the menu and keeps a copy`() = runTest {
        val repo = repository(FakeSource(MenuFetch.Published(menu(version = 100))))

        val loaded = repo.load(STORE_ID) as MenuLoad.Ready

        assertEquals(100L, loaded.menu.version)
        assertFalse(loaded.fromCache)
        assertEquals(NOW, loaded.fetchedAtMillis)
        assertEquals(100L, dao.find(STORE_ID)?.version)
    }

    @Test
    fun `the same version is not written again`() = runTest {
        store(version = 100, fetchedAt = 111)
        val repo = repository(FakeSource(MenuFetch.Published(menu(version = 100))))

        val loaded = repo.load(STORE_ID) as MenuLoad.Ready

        assertTrue(loaded.fromCache)
        // 沒有重寫，所以抓下來的時間還是上一次那個。
        assertEquals(111L, loaded.fetchedAtMillis)
        assertEquals(111L, dao.find(STORE_ID)?.fetchedAtMillis)
    }

    @Test
    fun `a newer published version replaces the cached one`() = runTest {
        store(version = 100, fetchedAt = 111)
        val repo = repository(FakeSource(MenuFetch.Published(menu(version = 200))))

        val loaded = repo.load(STORE_ID) as MenuLoad.Ready

        assertEquals(200L, loaded.menu.version)
        assertFalse(loaded.fromCache)
        assertEquals(NOW, loaded.fetchedAtMillis)
        assertEquals(200L, dao.find(STORE_ID)?.version)
    }

    @Test
    fun `a smaller version on the server still wins`() = runTest {
        // 比的是「一不一樣」而不是「有沒有變新」。換到店家的 Firebase 專案時，
        // 新專案發佈出來的 version 可能比測試專案那份小——用大於來判斷的話，
        // 平板會抱著測試專案的菜單不放，永遠換不過去，而那是錯的價格。
        store(version = 999, fetchedAt = 111)
        val repo = repository(FakeSource(MenuFetch.Published(menu(version = 100))))

        val loaded = repo.load(STORE_ID) as MenuLoad.Ready

        assertEquals(100L, loaded.menu.version)
        assertFalse(loaded.fromCache)
        assertEquals(100L, dao.find(STORE_ID)?.version)
    }

    @Test
    fun `an offline tablet still gets the cached menu`() = runTest {
        store(version = 100, fetchedAt = 111)
        val repo = repository(FakeSource(MenuFetch.Unavailable("UNAVAILABLE")))

        val loaded = repo.load(STORE_ID) as MenuLoad.Ready

        assertEquals(100L, loaded.menu.version)
        assertTrue(loaded.fromCache)
        assertEquals(111L, loaded.fetchedAtMillis)
    }

    @Test
    fun `an offline tablet that never fetched has nothing to show`() = runTest {
        val repo = repository(FakeSource(MenuFetch.Unavailable("UNAVAILABLE")))

        assertEquals(MenuLoad.Unavailable, repo.load(STORE_ID))
    }

    @Test
    fun `the cached menu is kept when the server says nothing is published`() = runTest {
        // 老闆誤刪了發佈文件、或換專案時忘了按發佈。把快取清掉等於營業中
        // 讓整間店點不了餐；留著最多是價格舊了一點，而金額本來就由伺服器重算。
        store(version = 100, fetchedAt = 111)
        val repo = repository(FakeSource(MenuFetch.NotPublished))

        val loaded = repo.load(STORE_ID) as MenuLoad.Ready

        assertEquals(100L, loaded.menu.version)
        assertTrue(loaded.fromCache)
        assertNotNull(dao.find(STORE_ID))
    }

    @Test
    fun `nothing published and nothing cached is reported as unavailable`() = runTest {
        val repo = repository(FakeSource(MenuFetch.NotPublished))

        assertEquals(MenuLoad.Unavailable, repo.load(STORE_ID))
    }

    @Test
    fun `an unreadable cache is thrown away and refetched instead of crashing`() = runTest {
        dao.upsert(
            MenuSnapshotEntity(
                storeId = STORE_ID,
                version = 100,
                menuJson = "{ this is not json",
                fetchedAtMillis = 111,
            ),
        )
        val repo = repository(FakeSource(MenuFetch.Published(menu(version = 200))))

        val loaded = repo.load(STORE_ID) as MenuLoad.Ready

        assertEquals(200L, loaded.menu.version)
        assertFalse(loaded.fromCache)
        assertEquals(200L, dao.find(STORE_ID)?.version)
    }

    @Test
    fun `a cache holding an impossible price is treated as no cache`() = runTest {
        // MenuItem 的 init 會擋負價。存進去之後才壞掉的資料要在讀出來時被擋下來，
        // 而不是讓開店流程丟例外——那台平板會變成一塊磚。
        dao.upsert(
            MenuSnapshotEntity(
                storeId = STORE_ID,
                version = 100,
                menuJson = """
                    {"version":100,"categories":[],"optionGroups":[],
                     "items":[{"itemId":"item_beef_noodle","name":"牛肉麵",
                               "price":-180,"categoryId":"cat_noodle"}]}
                """.trimIndent(),
                fetchedAtMillis = 111,
            ),
        )
        val repo = repository(FakeSource(MenuFetch.Unavailable("UNAVAILABLE")))

        assertEquals(MenuLoad.Unavailable, repo.load(STORE_ID))
        assertNull(dao.find(STORE_ID))
    }

    @Test
    fun `each store keeps its own snapshot`() = runTest {
        repository(FakeSource(MenuFetch.Published(menu(version = 100)))).load(STORE_ID)
        repository(FakeSource(MenuFetch.Published(menu(version = 200)))).load(OTHER_STORE_ID)

        assertEquals(100L, dao.find(STORE_ID)?.version)
        assertEquals(200L, dao.find(OTHER_STORE_ID)?.version)
    }

    @Test
    fun `a round trip through the cache keeps the menu intact`() = runTest {
        val source = FakeSource(MenuFetch.Published(MenuFixtures.menu))
        repository(source).load(STORE_ID)

        // 第二次開店時伺服器連不上，只能吃快取——這時拿到的必須跟原本那份一樣，
        // 不然「離線也點得了餐」是假的。
        val offline = repository(FakeSource(MenuFetch.Unavailable(null))).load(STORE_ID)

        assertEquals(MenuFixtures.menu, (offline as MenuLoad.Ready).menu)
    }

    @Test
    fun `the store is only asked once per load`() = runTest {
        val source = FakeSource(MenuFetch.Published(menu(version = 100)))
        repository(source).load(STORE_ID)

        // 菜單是 1 次讀取，不是 1 次讀取加上「順便再確認一下」。
        assertEquals(1, source.calls)
    }

    private fun repository(source: MenuSource) = MenuRepository(
        dao = dao,
        source = source,
        clock = Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC),
    )

    private suspend fun store(version: Long, fetchedAt: Long) {
        dao.upsert(
            MenuSnapshotEntity(
                storeId = STORE_ID,
                version = version,
                menuJson = json.encodeToString(Menu.serializer(), menu(version)),
                fetchedAtMillis = fetchedAt,
            ),
        )
    }

    private fun menu(version: Long) = MenuFixtures.menu.copy(version = version)

    private class FakeSource(private val result: MenuFetch) : MenuSource {
        var calls = 0
            private set

        override suspend fun fetch(storeId: String): MenuFetch {
            calls++
            return result
        }
    }

    private companion object {
        const val STORE_ID = "store_demo"
        const val OTHER_STORE_ID = "store_demo_2"
        const val NOW = 1_700_000_999_000L

        /** 要跟 MenuRepository 存進去的格式一致，不然這裡塞的快取它讀不回來。 */
        val json = Json { ignoreUnknownKeys = true }
    }
}
