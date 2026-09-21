package io.github.bryanttang.bpos.tables

/**
 * 一張桌子（SPEC 第三節〈桌號命名〉〈平面圖與排序〉）。
 *
 * 真正的鍵是 [tableId]，[label] 純粹是顯示用。改桌名不影響任何既有訂單，
 * 因為訂單存的是 `tableLabels` 快照。
 */
data class Table(
    val tableId: String,
    val label: String,
    val zoneId: String,
    /** 平面圖上的相對座標，0 到 1。 */
    val x: Float,
    val y: Float,
    /**
     * 列表場景（搜尋結果、報表、小螢幕）用的確定順序。
     *
     * 由後台依閱讀順序（由上而下、由左而右）自動推導後存下來。
     * **不要用 [label] 排序**：中文會變成按 Unicode 碼位排，而且 `A10` 會排在 `A2` 前面。
     */
    val sort: Int,
    val seats: Int,
) {
    init {
        require(x in 0f..1f) { "x 必須是 0 到 1 的相對座標，收到 $x" }
        require(y in 0f..1f) { "y 必須是 0 到 1 的相對座標，收到 $y" }
    }
}

/** 區域，桌位總覽依它分頁。 */
data class Zone(
    val zoneId: String,
    val name: String,
    val sort: Int,
)

/**
 * 桌名在卡片上要顯示的樣子。
 *
 * SPEC 訂的上限是 10 個字元，超過就截斷。這裡回傳截斷後的字串，
 * 完整值仍留在 [Table.label]，長按或 tooltip 用得到。
 *
 * 用**字元數**而不是位元組數：店家可能取「窗邊包廂一號」這種全中文名，
 * 按位元組算的話三個中文字就爆了。
 */
fun displayLabel(label: String, maxChars: Int = MAX_LABEL_CHARS): String {
    require(maxChars > 0) { "maxChars 必須是正整數" }
    // codePointCount 而不是 length：Kotlin 的 String.length 算的是 UTF-16 code unit，
    // 罕用字（例如「𠮷」）會佔兩個，用 length 判斷會提前截斷，甚至把一個字切成兩半。
    val codePoints = label.codePoints().toArray()
    if (codePoints.size <= maxChars) return label
    val kept = codePoints.take(maxChars)
    return buildString {
        kept.forEach { appendCodePoint(it) }
        append('…')
    }
}

const val MAX_LABEL_CHARS = 10
