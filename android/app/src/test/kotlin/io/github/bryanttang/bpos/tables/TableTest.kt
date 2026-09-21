package io.github.bryanttang.bpos.tables

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TableTest {

    // 相對座標必須落在 0 到 1
    @Test
    fun `coordinates must be relative`() {
        Table(tableId = "t1", label = "A1", zoneId = "z1", x = 0f, y = 1f, sort = 0, seats = 4)
        assertThrows(IllegalArgumentException::class.java) {
            Table(tableId = "t1", label = "A1", zoneId = "z1", x = 1.5f, y = 0f, sort = 0, seats = 4)
        }
        assertThrows(IllegalArgumentException::class.java) {
            Table(tableId = "t1", label = "A1", zoneId = "z1", x = 0f, y = -0.1f, sort = 0, seats = 4)
        }
    }

    // 沒超過上限的桌名原樣顯示
    @Test
    fun `a short label is shown as is`() {
        assertEquals("A3", displayLabel("A3"))
        assertEquals("窗邊", displayLabel("窗邊"))
        assertEquals("剛好十個字的桌名喔", displayLabel("剛好十個字的桌名喔"))
    }

    // 超過上限就截斷並加省略號
    @Test
    fun `an over-long label is truncated`() {
        assertEquals("一二三四五六七八九十…", displayLabel("一二三四五六七八九十十一"))
    }

    /**
     * SPEC 說桌名是任意字串，店家想叫什麼都可以。
     * 中文、英數、符號混用都不能出事。
     */
    @Test
    fun `any string is a valid label`() {
        assertEquals("包廂一", displayLabel("包廂一"))
        assertEquals("吧台3", displayLabel("吧台3"))
        assertEquals("蘇東坡", displayLabel("蘇東坡"))
        assertEquals("窗邊-A", displayLabel("窗邊-A"))
    }

    /**
     * 這是用 String.length 會踩到的坑：Kotlin 的 length 算的是 UTF-16 code unit，
     * 罕用字（這裡用「𠮷」，常見於人名與老店名）一個字佔兩個 code unit。
     *
     * 用 length 判斷的話，五個「𠮷」就會被誤判成十個字而截斷；
     * 更糟的是從中間切下去會把一個字劈成兩半，畫面上出現亂碼方塊。
     */
    @Test
    fun `supplementary characters count as one character each`() {
        val name = "𠮷".repeat(10)
        assertEquals(name, displayLabel(name))

        val tooLong = "𠮷".repeat(11)
        assertEquals("𠮷".repeat(10) + "…", displayLabel(tooLong))
    }

    // 空字串不會出事
    @Test
    fun `an empty label does not blow up`() {
        assertEquals("", displayLabel(""))
    }

    // 上限必須是正數
    @Test
    fun `the limit must be positive`() {
        assertThrows(IllegalArgumentException::class.java) { displayLabel("A1", maxChars = 0) }
    }
}
