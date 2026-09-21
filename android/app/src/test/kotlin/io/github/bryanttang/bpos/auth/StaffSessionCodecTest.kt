package io.github.bryanttang.bpos.auth

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Test

class StaffSessionCodecTest {

    private val session = StaffSession.SignedIn(
        uid = "uid_1",
        email = "clerk@example.com",
        storeId = "store_demo",
        role = StaffRole.STAFF,
    )

    @Test
    fun `a session survives a round trip`() {
        assertEquals(session, StaffSessionCodec.decode(StaffSessionCodec.encode(session)))
    }

    @Test
    fun `the owner role survives a round trip`() {
        val owner = session.copy(role = StaffRole.OWNER)

        assertEquals(owner, StaffSessionCodec.decode(StaffSessionCodec.encode(owner)))
    }

    @Test
    fun `nothing stored means signed out`() {
        assertEquals(StaffSession.SignedOut, StaffSessionCodec.decode(null))
        assertEquals(StaffSession.SignedOut, StaffSessionCodec.decode(""))
    }

    // 快取不是真相的來源，只是省掉一次連網。解不開就當沒登入，
    // 不要拿半個湊出來的 session 去組 Firestore 路徑。
    @Test
    fun `unreadable json means signed out`() {
        assertEquals(StaffSession.SignedOut, StaffSessionCodec.decode("{not json"))
    }

    @Test
    fun `a stored session missing its store means signed out`() {
        val raw = """{"uid":"uid_1","email":"clerk@example.com","storeId":"","role":"STAFF"}"""

        assertEquals(StaffSession.SignedOut, StaffSessionCodec.decode(raw))
    }

    // 之後多一個角色、又退版回舊版 App 時會踩到這條。
    @Test
    fun `an unknown stored role means signed out`() {
        val raw = """{"uid":"uid_1","email":"c@example.com","storeId":"store_demo","role":"MANAGER"}"""

        assertEquals(StaffSession.SignedOut, StaffSessionCodec.decode(raw))
    }
}
