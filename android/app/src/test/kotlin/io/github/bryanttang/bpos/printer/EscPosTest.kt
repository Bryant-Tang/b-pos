// 測試方法名一律用 ASCII（可以有空白、包在反引號裡），中文說明寫在註解。
// Kotlin 的反引號方法名會原樣變成 .class 的檔名，而 JVM 寫檔用的是作業系統
// locale 的編碼。locale 是 POSIX 的機器上（容器映像很常見），中文方法名會讓
// Kotlin 編譯器丟 InvalidPathException 內部錯誤——不是測試失敗，是整個測試
// 模組編不起來，而且錯誤訊息完全看不出跟中文有關。
package io.github.bryanttang.bpos.printer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EscPosTest {

    @Test
    fun `initialize is ESC at`() {
        assertArrayEquals(bytes(0x1B, 0x40), EscPos.initialize())
    }

    @Test
    fun `cut carries the feed distance`() {
        assertArrayEquals(bytes(0x1D, 0x56, 66, 80), EscPos.cut())
        assertArrayEquals(bytes(0x1D, 0x56, 66, 0), EscPos.cut(feedDots = 0))
    }

    @Test
    fun `a feed distance beyond one byte is rejected`() {
        // 指令的欄位只有一個位元組，溢位的話印表機會把它當成別的數字。
        assertTrue(runCatching { EscPos.cut(256) }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { EscPos.cut(-1) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `eight black dots pack into one byte`() {
        val actual = EscPos.raster(bitmapOf("########"))

        assertArrayEquals(
            bytes(0x1D, 0x76, 0x30, 0x00, /* 每列 1 位元組 */ 0x01, 0x00, /* 1 列 */ 0x01, 0x00, 0xFF),
            actual,
        )
    }

    @Test
    fun `the high bit is the leftmost dot`() {
        val actual = EscPos.raster(bitmapOf("#.#....."))

        assertEquals(0xA0.toByte(), actual.last())
    }

    @Test
    fun `a width below a multiple of eight pads with white`() {
        // 三點寬會佔滿一個位元組，右邊那五點是留白，不是沿用左邊的顏色。
        val actual = EscPos.raster(bitmapOf("###"))

        assertEquals(1, rowBytesOf(actual))
        assertEquals(0xE0.toByte(), actual.last())
    }

    @Test
    fun `a width past the byte boundary takes two bytes per row`() {
        val actual = EscPos.raster(bitmapOf("#.......#"))

        assertEquals(2, rowBytesOf(actual))
        assertArrayEquals(bytes(0x80, 0x80), actual.copyOfRange(actual.size - 2, actual.size))
    }

    @Test
    fun `the row count is little endian`() {
        val tall = bitmapOf(*Array(300) { "########" })

        val actual = EscPos.raster(tall)

        // 300 = 0x012C，低位在前。
        assertEquals(0x2C.toByte(), actual[6])
        assertEquals(0x01.toByte(), actual[7])
    }

    @Test
    fun `a tall image is sent as several bands`() {
        val five = bitmapOf(*Array(5) { "########" })

        // 每列 1 位元組，上限 2 位元組，所以一段兩列：2、2、1。
        val actual = EscPos.raster(five, maxBandBytes = 2)

        val heights = bandHeightsOf(actual, rowBytes = 1)
        assertEquals(listOf(2, 2, 1), heights)
        // 三個表頭加五列資料，一個位元組都不多。
        assertEquals(3 * 8 + 5, actual.size)
    }

    @Test
    fun `banding loses no row`() {
        // 每一列的黑點數都不同，切段後仍要照原順序出現。
        val rows = Array(7) { y -> "#".repeat(y + 1).padEnd(8, '.') }

        val whole = EscPos.raster(bitmapOf(*rows), maxBandBytes = 4096)
        val banded = EscPos.raster(bitmapOf(*rows), maxBandBytes = 2)

        assertEquals(dataOf(whole, rowBytes = 1), dataOf(banded, rowBytes = 1))
    }

    @Test
    fun `a row wider than the limit is still sent whole`() {
        // 切得再細也不可能小於一列，硬切只會印出半張圖。
        val actual = EscPos.raster(bitmapOf("#.......#"), maxBandBytes = 1)

        assertEquals(listOf(1), bandHeightsOf(actual, rowBytes = 2))
    }

    @Test
    fun `the band limit must be positive`() {
        val failure = runCatching { EscPos.raster(bitmapOf("########"), maxBandBytes = 0) }
            .exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `a print job is initialize then raster then cut`() {
        val bitmap = bitmapOf("########")

        val actual = EscPos.printJob(bitmap)

        assertArrayEquals(
            EscPos.initialize() + EscPos.raster(bitmap) + EscPos.cut(),
            actual,
        )
    }

    private fun rowBytesOf(command: ByteArray): Int =
        (command[4].toInt() and 0xFF) or ((command[5].toInt() and 0xFF) shl 8)

    /** 走過整串位元組，把每一段表頭宣告的列數收集起來。 */
    private fun bandHeightsOf(command: ByteArray, rowBytes: Int): List<Int> {
        val heights = mutableListOf<Int>()
        var at = 0
        while (at < command.size) {
            assertArrayEquals(
                "第 ${heights.size + 1} 段的表頭",
                bytes(0x1D, 0x76, 0x30, 0x00),
                command.copyOfRange(at, at + 4),
            )
            val rows = (command[at + 6].toInt() and 0xFF) or
                ((command[at + 7].toInt() and 0xFF) shl 8)
            heights += rows
            at += 8 + rows * rowBytes
        }
        return heights
    }

    /** 只留下圖的資料，把每一段的表頭拿掉。 */
    private fun dataOf(command: ByteArray, rowBytes: Int): List<Byte> {
        val data = mutableListOf<Byte>()
        var at = 0
        while (at < command.size) {
            val rows = (command[at + 6].toInt() and 0xFF) or
                ((command[at + 7].toInt() and 0xFF) shl 8)
            at += 8
            repeat(rows * rowBytes) { data += command[at++] }
        }
        return data
    }

    private fun bitmapOf(vararg rows: String): MonoBitmap {
        val width = rows.first().length
        require(rows.all { it.length == width }) { "每一列的長度要一樣" }
        val dots = BooleanArray(width * rows.size)
        rows.forEachIndexed { y, row ->
            row.forEachIndexed { x, mark -> dots[y * width + x] = mark == '#' }
        }
        return MonoBitmap(width = width, height = rows.size, dots = dots)
    }

    private fun bytes(vararg values: Int): ByteArray =
        ByteArray(values.size) { values[it].toByte() }
}
