package io.github.bryanttang.bpos.printer

/**
 * 單據上的文字寬度，以「半形格」為單位。
 *
 * 單據是等寬排版：數量要對齊在同一欄，品項太長要折行，而這兩件事都需要知道
 * 一段字佔幾格。中文、全形標點、日文假名這些字在等寬字型裡佔兩格，
 * 英數與半形標點佔一格——用 `String.length` 算出來的「長度」跟印出來的寬度
 * 完全是兩回事，「牛肉麵」是 3 個字但佔 6 格。
 *
 * 判斷依據是 Unicode 的 East Asian Width。這裡不引外部函式庫，直接列出
 * 實際會出現在單據上的那幾段區間：品項名稱、規格、桌號、備註，內容來自
 * 店家自己打的菜單，範圍就是中日文與全形標點。列不到的字一律當成一格——
 * 猜窄的後果是那一行稍微超出紙寬被截掉，猜寬的後果是每一行都莫名其妙地短。
 */
internal fun displayWidth(text: String): Int {
    var width = 0
    var index = 0
    while (index < text.length) {
        val code = text.codePointAt(index)
        width += if (isFullWidth(code)) 2 else 1
        index += Character.charCount(code)
    }
    return width
}

private fun isFullWidth(code: Int): Boolean = when (code) {
    in 0x1100..0x115F -> true // 韓文字母
    in 0x2E80..0x303E -> true // 部首補充、康熙部首、中日韓符號與標點（含全形句號、頓號）
    in 0x3041..0x33FF -> true // 平假名、片假名、注音、中日韓相容符號
    in 0x3400..0x4DBF -> true // 中日韓擴充 A
    in 0x4E00..0x9FFF -> true // 中日韓統一表意文字
    in 0xA000..0xA4CF -> true // 彝文
    in 0xF900..0xFAFF -> true // 中日韓相容表意文字
    in 0xFE30..0xFE4F -> true // 中日韓相容形式
    in 0xFF00..0xFF60 -> true // 全形英數與標點
    in 0xFFE0..0xFFE6 -> true // 全形貨幣符號
    in 0x20000..0x2FA1F -> true // 中日韓擴充 B 以後（含罕用字，例如姓氏用字）
    else -> false
}

/**
 * 折行，折在字的邊界上。
 *
 * **不可以折在半個字上。** 中文字在 Kotlin 的 String 裡是一到兩個 char，
 * 用 `substring(0, n)` 按 char 數切，切到一半會產生一個孤立的代理對，
 * 印出來是一個方塊。折行的單位因此是 code point，不是 char。
 *
 * 空字串回傳一行空字串而不是空清單：呼叫端要的是「這段文字佔幾行」，
 * 一段空白的備註仍然是一行，回空清單會讓那一行整個消失。
 */
internal fun wrapToWidth(text: String, columns: Int): List<String> {
    require(columns >= 2) { "欄寬至少要放得下一個全形字，收到 $columns" }
    if (displayWidth(text) <= columns) return listOf(text)

    val lines = mutableListOf<String>()
    val current = StringBuilder()
    var width = 0
    var index = 0

    while (index < text.length) {
        val code = text.codePointAt(index)
        val charWidth = if (isFullWidth(code)) 2 else 1
        if (width + charWidth > columns) {
            lines += current.toString()
            current.setLength(0)
            width = 0
        }
        current.appendCodePoint(code)
        width += charWidth
        index += Character.charCount(code)
    }

    if (current.isNotEmpty()) lines += current.toString()
    return lines
}
