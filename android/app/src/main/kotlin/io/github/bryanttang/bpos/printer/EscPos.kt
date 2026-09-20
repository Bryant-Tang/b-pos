package io.github.bryanttang.bpos.printer

/**
 * 把一張黑白點陣圖翻成熱感印表機聽得懂的位元組。
 *
 * **整張單子都是圖片，沒有一個字是用文字指令送的。** 這是刻意的：ESC/POS 的中文
 * 字碼頁各家做各家的（Big5、GB18030、UTF-8 都有人用），直接送文字幾乎必定亂碼，
 * 而且字型、字級、對齊全看印表機臉色。改成送圖之後，排版由我們的 Canvas 決定，
 * 印出來長什麼樣在螢幕上就看得到（SPEC 第七節〈中文編碼：一律用圖片化列印〉）。
 *
 * 這一層只負責「點陣圖 → 位元組」，不碰連線。送出去是 transport 的事。
 */
object EscPos {

    /**
     * 每個 `GS v 0` 指令的資料量上限，單位是位元組。
     *
     * 一整張單子會被切成好幾段分批送，不是因為指令本身有長度限制（高度欄位是
     * 16 位元，放得下 65535 列），而是印表機的接收緩衝區通常只有幾 KB。
     * 一次灌一大包，遇到流量控制沒做好的機器就會印到一半開始掉資料，
     * 而掉資料的症狀是「單子印出來少了幾行」——店員不一定會發現。
     *
     * 4 KB 是個保守值：80mm 紙一列 72 位元組，一段約 56 列。
     */
    const val DEFAULT_BAND_BYTES = 4096

    /**
     * 切紙前要先走幾點紙。
     *
     * 切刀在出紙口，印字頭在它後面幾公分處，中間那段紙是還沒被推出去的內容。
     * 不先走紙就切，等於把單子最後幾行留在機器裡，下一張單會頂著它一起印出來。
     *
     * 80 點是常見機型的概略值。拿到店家那台的自我測試頁、確認型號之後要實測修正。
     */
    const val DEFAULT_CUT_FEED = 80

    /**
     * 重設印表機。
     *
     * 每一份列印工作都從這裡開始：上一份單子可能留下了倍高、反白之類的設定，
     * 不重設的話會莫名其妙地影響下一張。
     */
    fun initialize(): ByteArray = byteArrayOf(ESC, INIT)

    /**
     * 走紙後切紙（`GS V 66 n`，ESC/POS 稱為 function B）。
     *
     * 用半切不用全切：全切會讓單子直接掉在地上，半切留一點連著，店員撕下來比較順手。
     */
    fun cut(feedDots: Int = DEFAULT_CUT_FEED): ByteArray {
        require(feedDots in 0..255) { "走紙點數必須介於 0 與 255 之間，收到 $feedDots" }
        return byteArrayOf(GS, CUT, CUT_FUNCTION_B, feedDots.toByte())
    }

    /**
     * 把 [bitmap] 編成一段或多段 `GS v 0` 光柵指令。
     *
     * 格式是 `GS v 0 m xL xH yL yH d1...dk`：
     * - `m` 用 0，原尺寸列印（1 是倍寬、2 是倍高、3 是兩倍大）
     * - `xL xH` 是每一列幾個位元組，小端序
     * - `yL yH` 是這一段有幾列，小端序
     * - 資料每一列 `ceil(width / 8)` 個位元組，每個位元組由**高位元開始**對應左邊的點，
     *   1 是印、0 是不印
     *
     * 寬度不是 8 的倍數時，最後一個位元組右邊補 0（不印），等於右側留白。
     */
    fun raster(bitmap: MonoBitmap, maxBandBytes: Int = DEFAULT_BAND_BYTES): ByteArray {
        require(maxBandBytes > 0) { "每段上限必須大於 0，收到 $maxBandBytes" }

        val rowBytes = (bitmap.width + 7) / 8
        // 一列就超過上限時仍然送一列：切得再細也不可能更小，硬切只會印出半張圖。
        // 上限那一邊夾在 65535，因為高度欄位只有 16 位元。
        val bandHeight = (maxBandBytes / rowBytes).coerceIn(1, MAX_BAND_ROWS)
        val bandCount = (bitmap.height + bandHeight - 1) / bandHeight

        val out = ByteArray(bandCount * HEADER_SIZE + bitmap.height * rowBytes)
        var at = 0
        var top = 0
        while (top < bitmap.height) {
            val rows = minOf(bandHeight, bitmap.height - top)

            out[at++] = GS
            out[at++] = RASTER
            out[at++] = RASTER_MODE_0
            out[at++] = RASTER_NORMAL
            out[at++] = lowByte(rowBytes)
            out[at++] = highByte(rowBytes)
            out[at++] = lowByte(rows)
            out[at++] = highByte(rows)

            for (y in top until top + rows) {
                for (byteIndex in 0 until rowBytes) {
                    var bits = 0
                    for (bit in 0 until 8) {
                        val x = byteIndex * 8 + bit
                        // 補到 8 的倍數的那幾點固定留白，不是沿用左邊的顏色。
                        if (x < bitmap.width && bitmap[x, y]) {
                            bits = bits or (0x80 ushr bit)
                        }
                    }
                    out[at++] = bits.toByte()
                }
            }

            top += rows
        }

        return out
    }

    /**
     * 一份完整的列印工作：重設、印圖、走紙切紙。
     *
     * 排版那一層畫完一張單子之後呼叫這支，拿到的位元組直接丟給 transport 就好。
     */
    fun printJob(bitmap: MonoBitmap): ByteArray =
        initialize() + raster(bitmap) + cut()

    private const val ESC: Byte = 0x1B
    private const val GS: Byte = 0x1D
    private const val INIT: Byte = 0x40 // '@'
    private const val CUT: Byte = 0x56 // 'V'
    private const val CUT_FUNCTION_B: Byte = 66
    private const val RASTER: Byte = 0x76 // 'v'
    private const val RASTER_MODE_0: Byte = 0x30 // 字元 '0'，不是數值 0
    private const val RASTER_NORMAL: Byte = 0 // m = 0，原尺寸

    /** `GS v 0` 的表頭長度：指令 3 個位元組，加上 m 與兩組小端序尺寸。 */
    private const val HEADER_SIZE = 8

    /** 高度欄位是 16 位元，一段最多這麼多列。 */
    private const val MAX_BAND_ROWS = 65535

    private fun lowByte(value: Int): Byte = (value and 0xFF).toByte()

    private fun highByte(value: Int): Byte = ((value ushr 8) and 0xFF).toByte()
}
