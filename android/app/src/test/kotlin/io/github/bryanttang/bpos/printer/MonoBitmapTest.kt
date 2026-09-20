// 測試方法名一律用 ASCII（可以有空白、包在反引號裡），中文說明寫在註解。
// Kotlin 的反引號方法名會原樣變成 .class 的檔名，而 JVM 寫檔用的是作業系統
// locale 的編碼。locale 是 POSIX 的機器上（容器映像很常見），中文方法名會讓
// Kotlin 編譯器丟 InvalidPathException 內部錯誤——不是測試失敗，是整個測試
// 模組編不起來，而且錯誤訊息完全看不出跟中文有關。
package io.github.bryanttang.bpos.printer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MonoBitmapTest {

    @Test
    fun `pure white is 255 and pure black is 0`() {
        assertEquals(255, luminanceOnWhite(WHITE))
        assertEquals(0, luminanceOnWhite(BLACK))
    }

    @Test
    fun `green is brighter than blue at the same value`() {
        val green = luminanceOnWhite(argb(0, 200, 0))
        val blue = luminanceOnWhite(argb(0, 0, 200))
        assertTrue("綠 $green 應該比藍 $blue 亮", green > blue)
    }

    @Test
    fun `a fully transparent dot counts as white paper`() {
        // 熱感紙本來就是白的，印表機沒有「透明」這個選項。
        assertEquals(255, luminanceOnWhite(0x00000000))
    }

    @Test
    fun `a half transparent black lands in the middle`() {
        val half = luminanceOnWhite(0x80000000.toInt())
        assertTrue("半透明的黑算出來是 $half，應該落在中間調", half in 100..155)
    }

    @Test
    fun `only below the threshold counts as black`() {
        val below = MonoBitmap.fromArgb(intArrayOf(gray(127)), width = 1, height = 1)
        val exactly = MonoBitmap.fromArgb(intArrayOf(gray(128)), width = 1, height = 1)

        assertTrue(below[0, 0])
        assertFalse("正好等於門檻不算黑", exactly[0, 0])
    }

    @Test
    fun `the threshold is adjustable`() {
        val pixels = intArrayOf(gray(200))

        assertFalse(MonoBitmap.fromArgb(pixels, 1, 1, threshold = 128)[0, 0])
        assertTrue(MonoBitmap.fromArgb(pixels, 1, 1, threshold = 255)[0, 0])
    }

    @Test
    fun `pixels map to coordinates in order`() {
        // 左上黑、右上白、左下白、右下黑。
        val bitmap = MonoBitmap.fromArgb(
            argb = intArrayOf(BLACK, WHITE, WHITE, BLACK),
            width = 2,
            height = 2,
        )

        assertTrue(bitmap[0, 0])
        assertFalse(bitmap[1, 0])
        assertFalse(bitmap[0, 1])
        assertTrue(bitmap[1, 1])
    }

    @Test
    fun `a pixel count that does not match the size is rejected`() {
        val failure = runCatching { MonoBitmap.fromArgb(intArrayOf(BLACK, WHITE), 2, 2) }
            .exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
    }

    @Test
    fun `reading outside the bitmap throws`() {
        val bitmap = MonoBitmap.fromArgb(intArrayOf(BLACK), 1, 1)

        assertTrue(runCatching { bitmap[1, 0] }.exceptionOrNull() is IllegalArgumentException)
        assertTrue(runCatching { bitmap[0, -1] }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `dithering prints nothing on an all white image`() {
        val bitmap = solid(gray(255), Dithering.FLOYD_STEINBERG)

        assertEquals(0, blackDots(bitmap))
    }

    @Test
    fun `dithering leaves no gap on an all black image`() {
        val bitmap = solid(gray(0), Dithering.FLOYD_STEINBERG)

        assertEquals(SIDE * SIDE, blackDots(bitmap))
    }

    @Test
    fun `dithering breaks a mid tone into black and white dots`() {
        // 同一張 50% 灰：用門檻會整片變黑（127 < 128），用抖色才會有疏密。
        val mid = gray(127)

        assertEquals(SIDE * SIDE, blackDots(solid(mid, Dithering.THRESHOLD)))

        val dithered = blackDots(solid(mid, Dithering.FLOYD_STEINBERG))
        assertTrue("50% 灰抖色後黑點應該約佔一半，實際 $dithered / ${SIDE * SIDE}",
            dithered in (SIDE * SIDE * 4 / 10)..(SIDE * SIDE * 6 / 10))
    }

    @Test
    fun `dithering is deterministic`() {
        // 同一張圖印兩次要長得一樣，否則重印會和原本那張對不起來。
        val first = solid(gray(100), Dithering.FLOYD_STEINBERG)
        val second = solid(gray(100), Dithering.FLOYD_STEINBERG)

        for (y in 0 until SIDE) {
            for (x in 0 until SIDE) {
                assertEquals("($x, $y)", first[x, y], second[x, y])
            }
        }
    }

    // 均勻灰的黑點比例應該接近 (255 - 灰階) / 255。誤差擴散用整數除法一定會丟餘數，
    // 丟法不對的話餘數累積起來就會讓比例整片偏掉——這條測試壓的是那個偏差，
    // 容許值 0.008 剛好夠嚴：直接截斷或改用 floorDiv 都會超過。
    @Test
    fun `dithering keeps the black dot ratio close to the grey level`() {
        for (value in intArrayOf(32, 64, 127, 160, 192, 224)) {
            val bitmap = MonoBitmap.fromArgb(
                argb = IntArray(WIDE * WIDE) { gray(value) },
                width = WIDE,
                height = WIDE,
                dithering = Dithering.FLOYD_STEINBERG,
            )

            val ratio = blackDots(bitmap).toDouble() / (WIDE * WIDE)
            assertEquals("灰階 $value", (255 - value) / 255.0, ratio, 0.008)
        }
    }

    private fun solid(color: Int, dithering: Dithering) = MonoBitmap.fromArgb(
        argb = IntArray(SIDE * SIDE) { color },
        width = SIDE,
        height = SIDE,
        dithering = dithering,
    )

    private fun blackDots(bitmap: MonoBitmap): Int {
        var count = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                if (bitmap[x, y]) count++
            }
        }
        return count
    }

    private companion object {
        const val SIDE = 16

        /** 比例類的測試用大一點的圖，邊緣丟掉的誤差才不會主導結果。 */
        const val WIDE = 64
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()

        fun argb(red: Int, green: Int, blue: Int): Int =
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue

        fun gray(value: Int): Int = argb(value, value, value)
    }
}
