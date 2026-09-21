package io.github.bryanttang.bpos.firebase

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FirebaseConfigTest {

    @Test
    fun `三個值都有就成立`() {
        val config = firebaseConfigOf(
            projectId = "demo-b-pos",
            applicationId = "1:000000000000:android:0000000000000000",
            apiKey = "FAKE_KEY_FOR_TEST",
        )

        assertEquals("demo-b-pos", config?.projectId)
        assertEquals("1:000000000000:android:0000000000000000", config?.applicationId)
        assertEquals("FAKE_KEY_FOR_TEST", config?.apiKey)
    }

    @Test
    fun `前後空白會去掉`() {
        val config = firebaseConfigOf("  demo-b-pos  ", " 1:0:android:0 ", " FAKE_KEY ")

        assertEquals("demo-b-pos", config?.projectId)
        assertEquals("1:0:android:0", config?.applicationId)
        assertEquals("FAKE_KEY", config?.apiKey)
    }

    // 缺一個就整組不成立。只有專案 ID 沒有金鑰的話，Firestore 連得上但登入永遠失敗，
    // 店員看到的是「帳號或密碼不對」——那是最難查的那種錯。
    @Test
    fun `少了專案 ID 就沒有設定`() {
        assertNull(firebaseConfigOf("", "1:0:android:0", "FAKE_KEY"))
    }

    @Test
    fun `少了應用程式 ID 就沒有設定`() {
        assertNull(firebaseConfigOf("demo-b-pos", "", "FAKE_KEY"))
    }

    @Test
    fun `少了 API 金鑰就沒有設定`() {
        assertNull(firebaseConfigOf("demo-b-pos", "1:0:android:0", ""))
    }

    @Test
    fun `只有空白等於沒有`() {
        assertNull(firebaseConfigOf("   ", "1:0:android:0", "FAKE_KEY"))
    }

    @Test
    fun `三個都沒有就沒有設定`() {
        assertNull(firebaseConfigOf("", "", ""))
    }
}
