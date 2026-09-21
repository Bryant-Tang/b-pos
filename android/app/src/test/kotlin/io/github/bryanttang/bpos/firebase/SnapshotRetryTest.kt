package io.github.bryanttang.bpos.firebase

import io.github.bryanttang.bpos.sync.RetryPolicy
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 這一組驗的是「listener 掛掉之後畫面會不會自己好起來」。
 *
 * 用 [runTest] 跑，退避的 delay 走虛擬時間，所以不用真的等 2 秒。
 */
class SnapshotRetryTest {

    @Test
    fun `an error resubscribes and keeps delivering`() = runTest {
        var subscriptions = 0
        val source = flow {
            subscriptions++
            if (subscriptions == 1) {
                emit("第一份")
                throw IllegalStateException("listener 掛了")
            }
            emit("第二份")
        }

        val seen = source.retryingSnapshots().take(2).toList()

        assertEquals(listOf("第一份", "第二份"), seen)
        assertEquals(2, subscriptions)
    }

    @Test
    fun `nothing is emitted while resubscribing`() = runTest {
        // 收集端手上那份快照要原封不動留著，所以這條 Flow 在失敗與重訂閱之間
        // 不可以插入空清單之類的「重設」訊號。
        var subscriptions = 0
        val source = flow<List<String>> {
            subscriptions++
            if (subscriptions <= 2) {
                emit(listOf("A1"))
                throw IllegalStateException("listener 掛了")
            }
            emit(listOf("A1", "A2"))
        }

        val seen = source.retryingSnapshots().take(3).toList()

        assertEquals(listOf(listOf("A1"), listOf("A1"), listOf("A1", "A2")), seen)
        assertTrue("不該出現空清單", seen.none { it.isEmpty() })
    }

    @Test
    fun `repeated failures are counted from one`() = runTest {
        var subscriptions = 0
        val source = flow {
            subscriptions++
            if (subscriptions <= 3) throw IllegalStateException("第 $subscriptions 次")
            emit("終於好了")
        }

        val attempts = mutableListOf<Int>()
        val seen = source.retryingSnapshots { _, attempt -> attempts += attempt }.take(1).toList()

        assertEquals(listOf("終於好了"), seen)
        assertEquals(listOf(1, 2, 3), attempts)
    }

    @Test
    fun `the reported cause is the listener error`() = runTest {
        val boom = IllegalStateException("PERMISSION_DENIED")
        var subscriptions = 0
        val source = flow {
            subscriptions++
            if (subscriptions == 1) throw boom
            emit("好了")
        }

        val causes = mutableListOf<Throwable>()
        source.retryingSnapshots { cause, _ -> causes += cause }.take(1).toList()

        assertEquals(listOf<Throwable>(boom), causes)
    }

    // testScheduler 目前還標著 experimental，用它只是為了讀虛擬時鐘，沒有行為上的風險。
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `backoff comes from the outbox retry policy`() = runTest {
        // 這裡不重新推導一次秒數，只確認兩邊用的是同一個來源——
        // 真正的數字有 RetryPolicyTest 在顧，重複一份反而會各改各的。
        var subscriptions = 0
        val start = testScheduler.currentTime
        val source = flow {
            subscriptions++
            if (subscriptions == 1) throw IllegalStateException("掛了")
            emit(Unit)
        }

        source.retryingSnapshots().take(1).toList()

        assertEquals(RetryPolicy.delayAfter(1).toMillis(), testScheduler.currentTime - start)
    }
}
