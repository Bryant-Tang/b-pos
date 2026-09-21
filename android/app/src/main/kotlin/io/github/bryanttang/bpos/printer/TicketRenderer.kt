package io.github.bryanttang.bpos.printer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/**
 * 把排好的列畫成一張黑白圖。
 *
 * 整張單子都是圖，沒有一個字走 ESC/POS 的文字指令（SPEC 第七節）：各家印表機的
 * 中文字碼頁做法不同，直接送文字幾乎必定亂碼。
 *
 * ## 為什麼一個字一個字畫
 *
 * 每個字都畫在自己那一格的正中間，而不是把整行交給 `drawText`。
 * 系統的等寬字型只保證英數等寬，中文字的前進量各家字型不一樣，
 * 整行交出去的話「牛肉麵」後面的空白補得再準，數量那一欄還是會歪。
 * 一格一格畫，對齊就完全由我們自己的格線決定，跟字型無關。
 */
class TicketRenderer(private val geometry: TicketGeometry = TicketGeometry()) {

    fun render(rows: List<TicketRow>): Bitmap {
        val placement = geometry.place(rows)
        val bitmap = Bitmap.createBitmap(geometry.widthPx, placement.heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // 明確填白底。`ARGB_8888` 的預設是全透明，而 [MonoBitmap] 會把半透明的點
        // 當成疊在白紙上，所以走目前這條路徑時結果剛好一樣。寫出來是為了不要
        // 依賴那個巧合：這張圖也可能被存成 PNG 給人看、或換一套轉換方式，
        // 到時候「沒有底色」跟「白色底色」就不是同一件事了。
        canvas.drawColor(Color.WHITE)

        for (placed in placement.rows) {
            when (val row = placed.row) {
                is TicketRow.Text -> drawText(canvas, row, placed)
                TicketRow.Rule -> drawRule(canvas, placed)
                is TicketRow.Gap -> Unit
            }
        }
        return bitmap
    }

    private fun drawText(canvas: Canvas, row: TicketRow.Text, placed: TicketGeometry.PlacedRow) {
        val cell = geometry.cellWidth * row.scale
        val paint = paintFor(cell)
        val width = displayWidth(row.text)
        val startCell = when (row.align) {
            TicketRow.Align.START -> 0
            TicketRow.Align.CENTER -> ((geometry.columns / row.scale) - width).coerceAtLeast(0) / 2
        }

        // 基線取在方框偏下的位置：中文字沒有下伸部，靠 fontMetrics 的 descent
        // 置中會讓整行看起來偏高。
        val baseline = placed.top + placed.height * BASELINE_RATIO

        var x = startCell * cell
        var index = 0
        while (index < row.text.length) {
            val code = row.text.codePointAt(index)
            val glyph = String(Character.toChars(code))
            val span = displayWidth(glyph) * cell
            // 置中而不是靠左：字型量出來的前進量通常比格子窄一點（見上面的註解），
            // 靠左畫會讓每個字都貼著左邊，整行看起來鬆一格緊一格。
            canvas.drawText(glyph, x + (span - paint.measureText(glyph)) / 2f, baseline, paint)
            x += span
            index += Character.charCount(code)
        }
    }

    private fun drawRule(canvas: Canvas, placed: TicketGeometry.PlacedRow) {
        val thickness = (geometry.unit / RULE_THICKNESS_DIVISOR).coerceAtLeast(1)
        val top = placed.top + (placed.height - thickness) / 2f
        canvas.drawRect(
            0f,
            top,
            geometry.widthPx.toFloat(),
            top + thickness,
            Paint().apply { color = Color.BLACK },
        )
    }

    /**
     * 字級由「半形字要剛好填滿一格」回推，而不是寫死一個比例。
     *
     * 等寬字型的英數前進量大約是字級的 0.6 倍，但那是慣例不是保證。量一次再回推，
     * 換字型也不會突然爆出格線。量不到（某些測試環境的 Canvas 是空殼，
     * `measureText` 回 0）時退回慣用比例，寧可字級估得不準，也不要除以零。
     */
    private fun paintFor(cellWidth: Int): Paint {
        val probe = Paint().apply {
            typeface = Typeface.MONOSPACE
            textSize = PROBE_TEXT_SIZE
        }
        val ratio = probe.measureText("M") / PROBE_TEXT_SIZE
        val usable = if (ratio > 0f) ratio else FALLBACK_ASCII_RATIO
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.MONOSPACE
            color = Color.BLACK
            textSize = cellWidth / usable
        }
    }

    private companion object {
        const val BASELINE_RATIO = 0.78f
        const val RULE_THICKNESS_DIVISOR = 12
        const val PROBE_TEXT_SIZE = 100f
        const val FALLBACK_ASCII_RATIO = 0.6f
    }
}

/**
 * 把畫好的圖轉成點陣資料。
 *
 * 單據預設用門檻不用抖色：單子上幾乎都是文字，抖色會把抗鋸齒的灰邊打散成雜點，
 * 小字看起來像糊掉（見 [MonoBitmap]）。
 */
fun Bitmap.toMonoBitmap(
    dithering: Dithering = Dithering.THRESHOLD,
    threshold: Int = MonoBitmap.DEFAULT_THRESHOLD,
): MonoBitmap {
    val pixels = IntArray(width * height)
    getPixels(pixels, 0, width, 0, 0, width, height)
    return MonoBitmap.fromArgb(pixels, width, height, dithering, threshold)
}
