package io.github.bryanttang.bpos.sync

/*
 * 測試方法名一律用 ASCII，中文寫在上方的註解裡。
 *
 * Kotlin 的反引號方法名會原樣變成檔案名稱（`ClassName$方法名$1.class`），
 * 而 JVM 寫檔用的是作業系統 locale 的編碼。locale 是 POSIX / C 的機器上
 * （容器映像常見）編碼是 ASCII，中文名稱會讓 Kotlin 編譯器直接丟
 * InvalidPathException 內部錯誤——不是測試失敗，是整個測試編譯不起來。
 * 這種錯誤只在某些機器上出現，很難查，所以不要把名字改回中文。
 */

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.bryanttang.bpos.data.local.BposDatabase
import io.github.bryanttang.bpos.data.local.OutboxState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * 佇列的行為測試，跑在真的 Room 資料庫上（Robolectric 提供 Android 環境），
 * 送出端換成可控的假 sender。
 *
 * 時鐘是可控的 [MutableClock]，所以「退避兩秒之後才輪得到它」這種事
 * 是用撥時鐘驗的，不是用 sleep 等的。
 */
@RunWith(RobolectricTestRunner::class)
class OutboxRepositoryTest {

    private lateinit var db: BposDatabase
    private lateinit var sender: FakeSender
    private lateinit var clock: MutableClock
    private lateinit var repo: OutboxRepository

    private val t0 = Instant.parse("2026-01-15T03:20:00Z")

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            BposDatabase::class.java,
        ).allowMainThreadQueries().build()
        sender = FakeSender()
        clock = MutableClock(t0)
        repo = OutboxRepository(db.orderIntentOutboxDao(), sender, clock)
    }

    @After
    fun tearDown() = db.close()

    private fun intent(id: String, at: Instant = clock.instant()) = OrderIntent(
        intentId = id,
        orderId = "order_$id",
        orderType = OrderType.DINE_IN,
        tableId = "table_a1",
        lines = listOf(IntentLine(itemId = "item_beef_noodle", qty = 1)),
        createdBy = "staff_uid_0001",
        clientCreatedAt = at,
    )

    @Test
    // 送出成功後標記為已同步
    fun `successful send marks the entry synced`() = runTest {
        assertTrue(repo.enqueue(intent("intent_1")))
        assertEquals(OutboxState.PENDING, repo.stateOf("intent_1"))

        val report = repo.flush()

        assertEquals(1, report.sent)
        assertEquals(OutboxState.SYNCED, repo.stateOf("intent_1"))
        assertEquals(0, repo.observePendingCount().first())
    }

    /**
     * 店員連按兩下送出。第二次不該產生第二筆，也不該把第一筆的重試進度洗掉。
     */
    @Test
    // 同一個 intentId 不會重複進佇列
    fun `the same intent id is never queued twice`() = runTest {
        assertTrue(repo.enqueue(intent("intent_1")))
        assertFalse(repo.enqueue(intent("intent_1")))

        assertEquals(1, repo.observePendingCount().first())
        repo.flush()
        assertEquals(1, sender.sent.size)
    }

    /**
     * 上一次其實送成功了，只是回應沒回到平板。重試時伺服器說「已經有了」，
     * 這要當成成功，不是失敗——當成失敗的話這筆會一直重試到天荒地老。
     */
    @Test
    // 伺服器說已經有這筆時當成送達
    fun `already present on the server counts as delivered`() = runTest {
        sender.result = SendResult.AlreadyPresent
        repo.enqueue(intent("intent_1"))

        val report = repo.flush()

        assertEquals(1, report.sent)
        assertEquals(OutboxState.SYNCED, repo.stateOf("intent_1"))
    }

    @Test
    // 被拒絕的意圖不再重試
    fun `a rejected intent is never retried`() = runTest {
        sender.result = SendResult.Rejected("桌號不存在")
        repo.enqueue(intent("intent_1"))

        assertEquals(1, repo.flush().rejected)
        assertEquals(OutboxState.REJECTED, repo.stateOf("intent_1"))

        // 時間過再久也不該再送。
        clock.advance(Duration.ofHours(6))
        assertEquals(0, repo.flush().sent)
        assertEquals(1, sender.sent.size)
    }

    @Test
    // 被拒絕的意圖連同理由浮到 UI
    fun `rejected intents surface with their reason`() = runTest {
        sender.result = SendResult.Rejected("品項已下架")
        repo.enqueue(intent("intent_1"))
        repo.flush()

        val rejected = repo.observeRejected().first()

        assertEquals(1, rejected.size)
        assertEquals("intent_1", rejected.single().intent.intentId)
        assertEquals("品項已下架", rejected.single().reason)
    }

    /**
     * 這是整個佇列最重要的一條：送失敗的單**必須留在佇列裡**。
     * 掉了就是店家收不到錢。
     */
    @Test
    // 送不出去的意圖留在佇列裡等重試
    fun `a failed send stays queued for retry`() = runTest {
        sender.result = SendResult.Failed("沒有網路")
        repo.enqueue(intent("intent_1"))

        assertEquals(1, repo.flush().failed)
        assertEquals(OutboxState.PENDING, repo.stateOf("intent_1"))
        assertEquals(1, repo.observePendingCount().first())
    }

    @Test
    // 失敗後要等退避時間到了才會再送
    fun `a failed entry waits for its backoff before resending`() = runTest {
        sender.result = SendResult.Failed("沒有網路")
        repo.enqueue(intent("intent_1"))
        repo.flush()
        assertEquals(1, sender.sent.size)

        // 第一次失敗要等 2 秒。還沒到，不該再送。
        clock.advance(Duration.ofSeconds(1))
        assertEquals(0, repo.flush().failed)
        assertEquals(1, sender.sent.size)

        clock.advance(Duration.ofSeconds(1))
        assertEquals(1, repo.flush().failed)
        assertEquals(2, sender.sent.size)

        // 第二次失敗要等 4 秒。
        clock.advance(Duration.ofSeconds(3))
        repo.flush()
        assertEquals(2, sender.sent.size)
        clock.advance(Duration.ofSeconds(1))
        repo.flush()
        assertEquals(3, sender.sent.size)
    }

    @Test
    // 網路回來後原本失敗的意圖送得出去
    fun `a previously failed intent sends once the network returns`() = runTest {
        sender.result = SendResult.Failed("沒有網路")
        repo.enqueue(intent("intent_1"))
        repo.flush()

        sender.result = SendResult.Accepted
        clock.advance(Duration.ofSeconds(2))

        assertEquals(1, repo.flush().sent)
        assertEquals(OutboxState.SYNCED, repo.stateOf("intent_1"))
        assertEquals(0, repo.observePendingCount().first())
    }

    /**
     * 同一張單的加點必須照店員按的順序抵達伺服器，否則廚房看到的出單順序會亂掉。
     */
    @Test
    // 先建立的意圖先送
    fun `older intents are sent first`() = runTest {
        repo.enqueue(intent("intent_late", at = t0.plusSeconds(30)))
        repo.enqueue(intent("intent_early", at = t0))
        repo.enqueue(intent("intent_middle", at = t0.plusSeconds(10)))

        clock.advance(Duration.ofMinutes(1))
        repo.flush()

        assertEquals(
            listOf("intent_early", "intent_middle", "intent_late"),
            sender.sent.map { it.intentId },
        )
    }

    /**
     * 積了一堆單時一次只送一批。WorkManager 單次執行有時間上限，
     * 硬要一口氣清完會被系統砍在半路。
     */
    @Test
    // 一次只送一批並回報還有沒有剩
    fun `flush sends one batch and reports whether more are due`() = runTest {
        repeat(5) { repo.enqueue(intent("intent_$it", at = t0.plusSeconds(it.toLong()))) }
        // 這幾筆的建立時間分佈在 t0 到 t0+4 秒，把時鐘撥過去才五筆都到期。
        clock.advance(Duration.ofMinutes(1))

        val first = repo.flush(batchSize = 2)
        assertEquals(2, first.sent)
        assertTrue(first.hasMoreDue)

        val second = repo.flush(batchSize = 2)
        assertEquals(2, second.sent)
        assertTrue(second.hasMoreDue)

        val third = repo.flush(batchSize = 2)
        assertEquals(1, third.sent)
        assertFalse(third.hasMoreDue)
    }

    @Test
    // 已同步的意圖不會再送一次
    fun `a synced intent is never sent again`() = runTest {
        repo.enqueue(intent("intent_1"))
        repo.flush()

        clock.advance(Duration.ofHours(1))
        repo.flush()

        assertEquals(1, sender.sent.size)
    }

    @Test
    // 佇列是空的時候 flush 不會出事
    fun `flushing an empty queue is a no-op`() = runTest {
        val report = repo.flush()
        assertEquals(FlushReport(sent = 0, failed = 0, rejected = 0, hasMoreDue = false), report)
    }

    @Test
    // 沒進過佇列的 intentId 查不到狀態
    fun `an unknown intent id has no state`() = runTest {
        assertNull(repo.stateOf("intent_never_seen"))
    }

    /**
     * 送出端丟出約定外的例外時（最現實的一個：Firebase 還沒設定好，
     * getInstance() 丟 IllegalStateException），必須當成暫時性失敗處理。
     *
     * 讓它往上炸的話，attempts 不會加、next_attempt_at 不會往後推，
     * 下一輪 worker 立刻又撈到同一筆——變成沒有退避的密集重試。
     */
    @Test
    // 送出端丟例外要當成暫時性失敗，不能讓整輪炸掉
    fun `an unexpected exception from the sender is treated as a transient failure`() = runTest {
        sender.throws = IllegalStateException("FirebaseApp is not initialized")
        repo.enqueue(intent("intent_1"))

        val report = repo.flush()

        assertEquals(1, report.failed)
        assertEquals(OutboxState.PENDING, repo.stateOf("intent_1"))

        // 退避真的生效了：還沒到兩秒就不該再送。
        clock.advance(Duration.ofSeconds(1))
        repo.flush()
        assertEquals(1, sender.sent.size)

        clock.advance(Duration.ofSeconds(1))
        repo.flush()
        assertEquals(2, sender.sent.size)
    }

    /**
     * 一筆炸掉不該讓同一輪剩下的單全部停擺。
     */
    @Test
    // 某一筆丟例外時同一輪的其他單照送
    fun `one throwing entry does not stop the rest of the batch`() = runTest {
        repo.enqueue(intent("intent_1", at = t0))
        repo.enqueue(intent("intent_2", at = t0.plusSeconds(1)))
        clock.advance(Duration.ofMinutes(1))

        sender.throws = IllegalStateException("FirebaseApp is not initialized")
        val report = repo.flush()

        assertEquals(2, report.failed)
        assertEquals(2, sender.sent.size)
        assertEquals(OutboxState.PENDING, repo.stateOf("intent_1"))
        assertEquals(OutboxState.PENDING, repo.stateOf("intent_2"))
    }

    private class FakeSender : OrderIntentSender {
        var result: SendResult = SendResult.Accepted
        /** 設了就丟這個例外，模擬 Firebase SDK 丟出約定外的錯誤。 */
        var throws: Throwable? = null
        val sent = mutableListOf<OrderIntent>()

        override suspend fun send(intent: OrderIntent): SendResult {
            sent += intent
            throws?.let { throw it }
            return result
        }
    }

    private class MutableClock(private var now: Instant) : Clock() {
        override fun instant(): Instant = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId): Clock = this
        fun advance(by: Duration) {
            now = now.plus(by)
        }
    }
}
