package io.github.bryanttang.bpos.menu

import io.github.bryanttang.bpos.data.local.MenuSnapshotDao
import io.github.bryanttang.bpos.data.local.MenuSnapshotEntity
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.time.Clock

/**
 * 平板手上的菜單：本機一份快照，開店時去伺服器對一次版本。
 *
 * SPEC 第六節〈監聽器範圍〉第 3 條。這一層要撐住的是同一件事的兩面：
 *
 * - **網路正常時要拿到最新的價格**，否則老闆早上調了價，平板整天照舊價收錢。
 * - **網路不正常時要照樣點得了餐**，否則一斷線整間店就停擺（CLAUDE.md 第二節第三條）。
 */
class MenuRepository(
    private val dao: MenuSnapshotDao,
    private val source: MenuSource,
    private val clock: Clock = Clock.systemUTC(),
) {

    /**
     * 拿這間店現在該用的菜單。
     *
     * 每天開店呼叫一次就夠了。老闆營業中改了菜單的話，要店員自己按一下重新整理——
     * 這是刻意的：菜單在客人點到一半底下換掉，比晚幾分鐘換上去麻煩得多。
     */
    suspend fun load(storeId: String): MenuLoad {
        val stored = dao.find(storeId)
        val cached = stored?.let { readCached(it) }

        return when (val fetched = source.fetch(storeId)) {
            is MenuFetch.Published -> adopt(storeId, fetched.menu, stored, cached)

            // 伺服器說沒有菜單時，**不要**把手上這份刪掉。
            //
            // 老闆在後台誤刪了發佈文件、或是換專案時忘了發佈，都會走到這裡。
            // 把快取清掉等於在營業中讓整間店點不了餐，而留著最多是價格舊了一點——
            // 而且金額本來就由伺服器重算，舊價格只影響畫面上給店員看的估算。
            MenuFetch.NotPublished,
            is MenuFetch.Unavailable,
            -> if (cached != null && stored != null) {
                MenuLoad.Ready(cached, stored.fetchedAtMillis, fromCache = true)
            } else {
                MenuLoad.Unavailable
            }
        }
    }

    private suspend fun adopt(
        storeId: String,
        remote: Menu,
        stored: MenuSnapshotEntity?,
        cached: Menu?,
    ): MenuLoad {
        // 比的是「一不一樣」，不是「有沒有變新」。
        //
        // 用 `remote.version > cached.version` 看起來比較直覺，但老闆把菜單回復成
        // 舊的一版時，新發佈的 version 其實是更大的數字（發佈時取 Date.now()），
        // 所以那種情況本來就會過。真正會卡住的是伺服器時間倒退、或是換了 Firebase
        // 專案而新專案的 version 比較小——那時「比較舊」的那份才是現在對的那份，
        // 而平板會抱著舊快取不放，永遠換不過去。
        if (cached != null && stored != null && remote.version == cached.version) {
            return MenuLoad.Ready(cached, stored.fetchedAtMillis, fromCache = true)
        }

        val now = clock.millis()
        dao.upsert(
            MenuSnapshotEntity(
                storeId = storeId,
                version = remote.version,
                menuJson = json.encodeToString(Menu.serializer(), remote),
                fetchedAtMillis = now,
            ),
        )
        return MenuLoad.Ready(remote, now, fromCache = false)
    }

    /**
     * 解不開的快取當成「沒有快取」，並且順手刪掉。
     *
     * 會解不開的原因是欄位改名之類的程式改動（那份 JSON 只是本機快取，見 [Menu]），
     * 或是資料真的壞了。兩種情況都不該讓 App 在開店時當掉——那台平板會變成一塊磚，
     * 店員當下沒有任何辦法救它。刪掉之後這一輪會重新抓一份。
     */
    private suspend fun readCached(stored: MenuSnapshotEntity): Menu? = try {
        json.decodeFromString(Menu.serializer(), stored.menuJson)
    } catch (e: SerializationException) {
        dao.delete(stored.storeId)
        null
    } catch (e: IllegalArgumentException) {
        // MenuItem/OptionGroup 的 init 會對價格與 min/max 再檢查一次，
        // 存進去之後才壞掉的資料會在這裡被擋下來。
        dao.delete(stored.storeId)
        null
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

sealed interface MenuLoad {

    /**
     * 有菜單可以用。
     *
     * [fromCache] 為 true 表示這一份是本機那份，這次沒有跟伺服器換到新的——
     * 可能是版本一樣（正常），也可能是根本連不上（要讓店員知道）。
     * [fetchedAtMillis] 是這份內容當初抓下來的時間，畫面上可以顯示「菜單為 X 時更新」。
     */
    data class Ready(
        val menu: Menu,
        val fetchedAtMillis: Long,
        val fromCache: Boolean,
    ) : MenuLoad

    /**
     * 這台平板沒有任何菜單可用：從來沒抓成功過，而這次也沒抓到。
     *
     * 畫面上要明白講「請連上網路後重新開店」，不要顯示一份空菜單——
     * 空菜單看起來像「這間店今天什麼都沒有」，店員會以為是老闆把品項全下架了。
     */
    data object Unavailable : MenuLoad
}
