package io.github.bryanttang.bpos.printer

/**
 * 一張只有黑與白的點陣圖，熱感印表機真正吃得下的東西。
 *
 * 熱感頭沒有灰階：每個點只有「燒」與「不燒」兩種狀態。所以送出前一定要先把
 * 彩色或灰階的畫面壓成純黑白，這個型別就是壓完之後的結果。
 *
 * 這裡刻意**不碰 `android.graphics.Bitmap`**。排版那一層會用 Canvas 畫圖，
 * 畫完呼叫 `Bitmap.getPixels()` 拿到一個 ARGB 的 IntArray 再交給 [fromArgb]。
 * 中間切在 IntArray 這一刀，換來的是整個轉換與編碼都能用純 JVM 單元測試驗，
 * 不必開模擬器也不必 Robolectric——而這段（哪個點該黑）正是最容易寫錯、
 * 又最難用肉眼從一張出單照片看出錯在哪的地方。
 *
 * @param dots 由左到右、由上到下逐點排列，`true` 表示這一點要印黑。
 */
class MonoBitmap(
    val width: Int,
    val height: Int,
    private val dots: BooleanArray,
) {
    init {
        require(width > 0) { "寬度必須大於 0，收到 $width" }
        require(height > 0) { "高度必須大於 0，收到 $height" }
        require(dots.size == width * height) {
            "點數與尺寸對不上：${width}×${height} 應有 ${width * height} 點，收到 ${dots.size}"
        }
    }

    /** 第 [x] 行第 [y] 列要不要印黑點。 */
    operator fun get(x: Int, y: Int): Boolean {
        require(x in 0 until width) { "x 超出範圍：$x 不在 0 到 ${width - 1} 之間" }
        require(y in 0 until height) { "y 超出範圍：$y 不在 0 到 ${height - 1} 之間" }
        return dots[y * width + x]
    }

    companion object {
        /**
         * 灰階低於這個值就當成黑點。
         *
         * 取中間值 128。單據上的字是純黑配純白，離這條線兩邊都很遠，
         * 所以這個值怎麼調都不影響文字，真正會被影響的是 logo 這類中間調的圖。
         */
        const val DEFAULT_THRESHOLD = 128

        /**
         * 把一張 ARGB 圖壓成黑白。
         *
         * [argb] 的排列方式與 `Bitmap.getPixels()` 一致：由左到右、由上到下。
         *
         * @param dithering 中間調的處理方式，預設 [Dithering.THRESHOLD]。為什麼不是
         *   抖色請見 [Dithering] 的說明。
         * @param threshold 判定為黑的灰階門檻，見 [DEFAULT_THRESHOLD]。
         */
        fun fromArgb(
            argb: IntArray,
            width: Int,
            height: Int,
            dithering: Dithering = Dithering.THRESHOLD,
            threshold: Int = DEFAULT_THRESHOLD,
        ): MonoBitmap {
            require(width > 0) { "寬度必須大於 0，收到 $width" }
            require(height > 0) { "高度必須大於 0，收到 $height" }
            require(argb.size == width * height) {
                "像素數與尺寸對不上：${width}×${height} 應有 ${width * height} 點，收到 ${argb.size}"
            }
            require(threshold in 1..255) { "門檻必須介於 1 與 255 之間，收到 $threshold" }

            val gray = IntArray(argb.size) { luminanceOnWhite(argb[it]) }
            return when (dithering) {
                Dithering.THRESHOLD -> MonoBitmap(
                    width = width,
                    height = height,
                    dots = BooleanArray(gray.size) { gray[it] < threshold },
                )

                Dithering.FLOYD_STEINBERG -> floydSteinberg(gray, width, height, threshold)
            }
        }
    }
}

/** 中間調（既不夠黑也不夠白的灰）要怎麼處理。 */
enum class Dithering {
    /**
     * 比門檻暗就印黑，其餘留白。**單據的預設值。**
     *
     * 單據上絕大多數是文字，而文字本來就只有黑白兩色，抖色對它毫無幫助，
     * 反而會把抗鋸齒產生的邊緣灰階打散成雜點，小字看起來像糊掉。
     */
    THRESHOLD,

    /**
     * Floyd–Steinberg 誤差擴散：把每一點四捨五入丟掉的誤差分給右邊與下面的鄰居，
     * 用黑點的疏密去模擬灰階。
     *
     * 適合 logo、照片這類真的有中間調的圖。代價是輸出有顆粒感，不要拿來印字。
     */
    FLOYD_STEINBERG,
}

/**
 * 把一個 ARGB 像素換算成 0（黑）到 255（白）的灰階。
 *
 * 權重用 Rec. 601 的 0.299 / 0.587 / 0.114——人眼對綠色最敏感、藍色最遲鈍，
 * 直接平均三個色版會讓藍字印得比實際看起來淡。
 *
 * 半透明的點一律當成疊在白紙上：熱感紙本來就是白的，印表機也沒有「不印」以外的
 * 方式表現透明。少了這一步，Canvas 抗鋸齒產生的半透明邊緣會被當成純黑，
 * 字看起來會比螢幕上肥一圈。
 */
internal fun luminanceOnWhite(argb: Int): Int {
    val alpha = (argb ushr 24) and 0xFF
    val red = (argb ushr 16) and 0xFF
    val green = (argb ushr 8) and 0xFF
    val blue = argb and 0xFF
    val gray = (red * 299 + green * 587 + blue * 114) / 1000
    return if (alpha == 255) gray else (gray * alpha + 255 * (255 - alpha)) / 255
}

/**
 * Floyd–Steinberg 誤差擴散。
 *
 * 誤差分配的比例是這個演算法的定義，不要改：
 * ```
 *        目前點  7/16
 *  3/16   5/16   1/16
 * ```
 * [gray] 會被就地改寫（累加誤差後可能超出 0 到 255，比較時不必夾回來，
 * 因為只比大小），所以呼叫端傳進來的必須是可以丟棄的副本。
 */
private fun floydSteinberg(gray: IntArray, width: Int, height: Int, threshold: Int): MonoBitmap {
    val dots = BooleanArray(gray.size)

    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            val old = gray[index]
            val black = old < threshold
            dots[index] = black
            val error = old - if (black) 0 else 255

            // 誤差只往「還沒處理到」的方向送，否則會蓋掉已經定案的點。
            spread(gray, width, height, x + 1, y, error * 7 / 16)
            spread(gray, width, height, x - 1, y + 1, error * 3 / 16)
            spread(gray, width, height, x, y + 1, error * 5 / 16)
            spread(gray, width, height, x + 1, y + 1, error * 1 / 16)
        }
    }

    return MonoBitmap(width = width, height = height, dots = dots)
}

/** 把誤差加到 ([x], [y])，超出圖外就丟掉——邊緣的誤差無處可去，這是正常的。 */
private fun spread(gray: IntArray, width: Int, height: Int, x: Int, y: Int, error: Int) {
    if (x !in 0 until width || y !in 0 until height) return
    gray[y * width + x] += error
}
