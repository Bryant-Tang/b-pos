package io.github.bryanttang.bpos.printer

/**
 * 單據的尺寸計算：一格多寬、一列多高、整張多高、每一列從哪一個 y 開始。
 *
 * 跟 [TicketRenderer] 分開是因為這一段完全不需要 Canvas，而它正是會出錯的地方：
 * 算矮了最後一列被切掉，算高了每張單子都多吐一截白紙。兩種錯誤都只有印出來
 * 才看得到，所以這裡要測得到。
 *
 * @param widthPx 紙寬的點數。80mm 紙是 576 點，58mm 是 384 點（SPEC 第七節）。
 * @param columns 一行放得下幾個半形格。
 */
class TicketGeometry(
    val widthPx: Int = DEFAULT_WIDTH_PX,
    val columns: Int = DEFAULT_COLUMNS,
) {
    init {
        require(widthPx > 0) { "紙寬必須是正數，收到 $widthPx" }
        require(columns > 0) { "欄數必須是正數，收到 $columns" }
        require(widthPx / columns >= 2) { "紙寬 $widthPx 點放不下 $columns 欄" }
    }

    /**
     * 一個半形格的寬度，無條件捨去。
     *
     * 捨去的餘數留在右邊當邊界，不要平均分攤回每一格：那會讓格寬變成小數，
     * 而每一格都差零點幾點，累積到行尾就是一兩格的偏移，數量那一欄會對不齊。
     */
    val cellWidth: Int = widthPx / columns

    /** 一個全形字的方框邊長，也是未放大的一列的高度。 */
    val unit: Int = cellWidth * 2

    fun heightOf(row: TicketRow): Int = when (row) {
        is TicketRow.Text -> unit * row.scale
        is TicketRow.Gap -> unit * row.units
        TicketRow.Rule -> unit
    }

    /**
     * 算出每一列的 y 位置。
     *
     * 上下各留一個單位的邊界：切刀切在出紙口，最後一行緊貼著邊緣時，
     * 走紙的誤差會直接吃掉一行字。
     */
    fun place(rows: List<TicketRow>): Placement {
        var top = unit
        val placed = rows.map { row ->
            PlacedRow(row = row, top = top, height = heightOf(row)).also { top += it.height }
        }
        return Placement(rows = placed, heightPx = top + unit)
    }

    data class PlacedRow(val row: TicketRow, val top: Int, val height: Int)

    data class Placement(val rows: List<PlacedRow>, val heightPx: Int)
}

/** 80mm 熱感紙的點數（SPEC 第七節）。58mm 是 384。 */
const val DEFAULT_WIDTH_PX = 576
