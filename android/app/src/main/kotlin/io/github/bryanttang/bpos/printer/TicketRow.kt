package io.github.bryanttang.bpos.printer

/**
 * 排好版的一張單子，一列一列往下。
 *
 * 排版分成兩段：先算出要印哪些列（這裡，純 Kotlin，測得到），再把列畫成圖
 * （[TicketRenderer]，要 Canvas）。切在這裡是因為容易出錯的是前半段——
 * 中文字寬、折行、數量對齊——而那些錯誤從一張出單照片上很難看出是哪裡算錯的。
 */
sealed interface TicketRow {

    /**
     * 一行字。
     *
     * @param scale 放大倍率。廚房單的桌號用 2，因為那是廚師隔著幾公尺瞄一眼要看到的
     *   唯一一件事；品項用 1。放大倍率會同時放大字高與字寬，所以一行放得下的字數
     *   變成原本的一半。
     */
    data class Text(
        val text: String,
        val scale: Int = 1,
        val align: Align = Align.START,
    ) : TicketRow {
        init {
            require(scale in 1..MAX_SCALE) { "放大倍率只支援 1 到 $MAX_SCALE，收到 $scale" }
        }
    }

    /** 一條橫線，用來分隔標頭與品項。 */
    data object Rule : TicketRow

    /**
     * 留白。
     *
     * @param units 幾個單位高。一個單位是一行未放大的字高。
     */
    data class Gap(val units: Int = 1) : TicketRow {
        init {
            require(units >= 1) { "留白至少一個單位，收到 $units" }
        }
    }

    enum class Align { START, CENTER }

    companion object {
        const val MAX_SCALE = 2
    }
}
