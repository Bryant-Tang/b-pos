package io.github.bryanttang.bpos.menu

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Source
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import java.time.Duration

/**
 * 讀一次 `tenants/{storeId}/published/menu`。
 *
 * **一次 get()，不是 listener。** SPEC 第六節〈監聽器範圍〉第 3 條：菜單只在開店時讀一次，
 * 一份單一文件就是 1 次讀取。平板整天不關機，把監聽器掛在菜單上一天能燒掉幾萬次讀取，
 * 而菜單一天大概只改一次。
 */
class FirestoreMenuSource(
    /** 跟 [io.github.bryanttang.bpos.sync.FirestoreOrderIntentSender] 同樣的理由收 provider：
     *  Firebase 還沒設定好時 `getInstance()` 會丟例外，延後到真的要用時再取，
     *  這個錯誤才會被歸類成「這次抓不到」，而不是讓整個開店流程炸在組裝階段。 */
    private val firestoreProvider: () -> FirebaseFirestore,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : MenuSource {

    constructor(
        firestore: FirebaseFirestore,
        timeout: Duration = DEFAULT_TIMEOUT,
    ) : this({ firestore }, timeout)

    override suspend fun fetch(storeId: String): MenuFetch = try {
        val doc = firestoreProvider()
            .collection("tenants").document(storeId)
            .collection("published").document("menu")

        // 指定 Source.SERVER，不用預設的「連不上就給我快取」。
        //
        // 離線時該拿出來的是 Room 裡那份快照，那份我們自己控制得了版本與時間；
        // 讓 Firestore 的快取也插一腳，就變成兩層快取互相蓋來蓋去，
        // 出問題時分不出店員看到的是哪一份、什麼時候抓的。
        // 連不上時這裡會丟 UNAVAILABLE，剛好就是我們要的「這次抓不到」。
        val snapshot = withTimeout(timeout.toMillis()) {
            doc.get(Source.SERVER).await()
        }

        val data = snapshot.data
        if (!snapshot.exists() || data == null) {
            MenuFetch.NotPublished
        } else {
            toMenu(data)?.let { MenuFetch.Published(it) } ?: MenuFetch.NotPublished
        }
    } catch (e: TimeoutCancellationException) {
        MenuFetch.Unavailable("讀取菜單逾時（${timeout.seconds} 秒）")
    } catch (e: FirebaseFirestoreException) {
        MenuFetch.Unavailable("${e.code}: ${e.message}")
    } catch (e: IllegalStateException) {
        // Firebase 還沒初始化。這在接上專案設定之前是常態，不該讓 App 掛掉。
        MenuFetch.Unavailable(e.message)
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
