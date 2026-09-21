package io.github.bryanttang.bpos.auth

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 這幾條看起來理所當然，但寫錯的代價是「登出按了，下次開機又自己進去了」，
 * 而那在店裡沒有人會注意到——直到某天換班的人發現自己是用別人的帳號在送單。
 */
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesSessionStoreTest {

    private val store = SharedPreferencesSessionStore(ApplicationProvider.getApplicationContext())

    private val session = StaffSession.SignedIn(
        uid = "uid_1",
        email = "clerk@example.com",
        storeId = "store_demo",
        role = StaffRole.STAFF,
    )

    @Test
    fun `nothing written yet reads as signed out`() {
        assertEquals(StaffSession.SignedOut, store.read())
    }

    @Test
    fun `a written session reads back`() {
        store.write(session)

        assertEquals(session, store.read())
    }

    @Test
    fun `writing again replaces the previous session`() {
        store.write(session)
        store.write(session.copy(uid = "uid_2", email = "boss@example.com", role = StaffRole.OWNER))

        assertEquals("uid_2", (store.read() as StaffSession.SignedIn).uid)
    }

    @Test
    fun `clearing really clears`() {
        store.write(session)
        store.clear()

        assertEquals(StaffSession.SignedOut, store.read())
    }
}
