package io.github.bryanttang.bpos.printer

/*
 * 測試方法名一律用 ASCII，理由見 sync/RetryPolicyTest.kt 開頭的註解。
 */

import org.junit.Assert.assertEquals
import org.junit.Test

class TextWidthTest {

    @Test
    fun `ascii counts one cell per character`() {
        assertEquals(5, displayWidth("Latte"))
    }

    // 「牛肉麵」是 3 個字但佔 6 格，String.length 算出來的數字跟印出來的寬度無關。
    @Test
    fun `chinese counts two cells per character`() {
        assertEquals(6, displayWidth("牛肉麵"))
    }

    @Test
    fun `mixed text adds up`() {
        assertEquals(8, displayWidth("珍奶 500"))
    }

    // 全形標點跟中文字一樣寬，漏掉的話帶標點的備註會算窄。
    @Test
    fun `full width punctuation counts as two`() {
        assertEquals(4, displayWidth("，。"))
    }

    // 罕用字（例如姓氏）在 Kotlin 的 String 裡是兩個 char。
    @Test
    fun `characters outside the basic plane still count as two`() {
        assertEquals(2, displayWidth("𠮷"))
    }

    @Test
    fun `text that fits is returned as one line`() {
        assertEquals(listOf("牛肉麵"), wrapToWidth("牛肉麵", 8))
    }

    @Test
    fun `text is wrapped on the cell boundary`() {
        assertEquals(listOf("牛肉", "麵加", "大辣"), wrapToWidth("牛肉麵加大辣", 4))
    }

    // 折行不可以把一個全形字切成兩半——切到一半會印出一個方塊。
    @Test
    fun `a full width character is never split across lines`() {
        val lines = wrapToWidth("A牛肉", 3)

        assertEquals(listOf("A牛", "肉"), lines)
    }

    @Test
    fun `characters outside the basic plane are not split either`() {
        val lines = wrapToWidth("A𠮷", 3)

        assertEquals(listOf("A𠮷"), lines)
    }

    // 空白的備註仍然佔一行；回空清單會讓那一行整個消失。
    @Test
    fun `empty text is still one line`() {
        assertEquals(listOf(""), wrapToWidth("", 8))
    }
}
