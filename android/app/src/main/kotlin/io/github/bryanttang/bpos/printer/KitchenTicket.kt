package io.github.bryanttang.bpos.printer

/**
 * 廚房單要印的東西（SPEC 第七節〈單據類型〉）。
 *
 * **不印金額，一個數字都不印。** 廚房需要知道做什麼、做幾份、送到哪一桌，
 * 金額只會讓那張紙更難掃。這不是省墨水的問題：出餐台上那張紙被瞄到的時間
 * 大概兩秒，多一欄就是少看到一樣東西。
 */
data class KitchenTicket(
    val kind: Kind,
    /** 內用的桌號；外帶與候位是 null。 */
    val tableLabel: String?,
    /** 外帶與候位的取餐號；內用是 null。 */
    val pickupCode: String?,
    /** 印在單子上的時間，已經格式化好（例如 `19:05`）。格式化屬於畫面那一層的事。 */
    val orderedAt: String,
    val lines: List<Line>,
) {
    init {
        require(lines.isNotEmpty()) { "廚房單至少要有一個品項" }
    }

    enum class Kind {
        /** 第一次出的單。 */
        NEW,

        /** 加點單：只印新增的項目，標明「加點」（SPEC 第七節）。 */
        ADDITION,
    }

    /**
     * @param options 規格選項的名稱，例如「大辣」「不要蔥」。
     * @param note 這一列的備註。
     */
    data class Line(
        val name: String,
        val qty: Int,
        val options: List<String> = emptyList(),
        val note: String? = null,
    ) {
        init {
            require(qty >= 1) { "數量至少為 1，收到 $qty" }
        }
    }
}

/**
 * 廚房單的排版。
 *
 * @param columns 一行放得下幾個半形格。
 */
fun layoutKitchenTicket(ticket: KitchenTicket, columns: Int = DEFAULT_COLUMNS): List<TicketRow> {
    require(columns >= MIN_COLUMNS) { "單據至少要 $MIN_COLUMNS 個半形格，收到 $columns" }

    val rows = mutableListOf<TicketRow>()

    if (ticket.kind == KitchenTicket.Kind.ADDITION) {
        // 加點單一定要在最上面講清楚，而且要大。廚師看到一張跟剛剛很像的單子時，
        // 第一個要排除的疑問就是「這是不是重印」——分不出來就會做出兩份。
        rows += bannerRows("＊ 加 點 ＊", columns)
    }

    rows += bannerRows(headline(ticket), columns)
    rows += TicketRow.Text(ticket.orderedAt, align = TicketRow.Align.CENTER)
    rows += TicketRow.Rule

    ticket.lines.forEachIndexed { index, line ->
        if (index > 0) rows += TicketRow.Gap()
        rows += itemRows(line, columns)
    }

    rows += TicketRow.Rule
    return rows
}

/**
 * 最上面那一行：內用印桌號，外帶印取餐號。
 *
 * 兩個都沒有時印「外帶」而不是留白：一張沒有標頭的單子，廚師無從判斷那是漏印
 * 還是真的沒有桌號。實際上候位單在綁桌之前就是這個樣子。
 */
/**
 * 放大的置中列。太長就折行，不讓它畫出紙外。
 *
 * 放大一倍的字一格佔兩格，所以這一列放得下的字數只有 `columns` 的一半——
 * 32 格的紙上是 8 個中文字。桌號是老闆在後台自己打的文字，不是兩位數的編號
 * （見測試裡的「窗邊」），打長一點就會超過。超過時 startCell 會被夾到 0，
 * 字直接畫超出紙寬被裁掉，不會有任何錯誤——廚房收到的是一張桌號少了尾巴的單子，
 * 而那張單子會被送到別桌去。折行醜一點，總比送錯桌好。
 */
private fun bannerRows(text: String, columns: Int): List<TicketRow> =
    wrapToWidth(text, columns / BANNER_SCALE).map {
        TicketRow.Text(it, scale = BANNER_SCALE, align = TicketRow.Align.CENTER)
    }

private fun headline(ticket: KitchenTicket): String = when {
    ticket.tableLabel != null -> ticket.tableLabel
    ticket.pickupCode != null -> "外帶 ${ticket.pickupCode}"
    else -> "外帶"
}

/**
 * 一個品項佔的幾列：名稱與數量一列，規格與備註各自縮排在下面。
 *
 * 數量放在最右邊、名稱靠左，中間用空白撐開。名稱太長時先折行，數量跟著最後一行——
 * 數量印在第一行而名稱折到第二行的話，掃過去會看成兩個不同的品項。
 */
private fun itemRows(line: KitchenTicket.Line, columns: Int): List<TicketRow> {
    val qty = "×${line.qty}"
    val nameColumns = columns - displayWidth(qty) - 1
    val wrapped = wrapToWidth(line.name, nameColumns)

    val rows = mutableListOf<TicketRow>()
    wrapped.forEachIndexed { index, part ->
        val isLast = index == wrapped.lastIndex
        rows += TicketRow.Text(if (isLast) pad(part, nameColumns) + " " + qty else part)
    }

    // 規格與備註縮排兩格，視覺上掛在品項底下。它們是「這一份要怎麼做」，
    // 跟「做什麼」分開，廚師才不會把規格看成另一個品項。
    val details = line.options + listOfNotNull(line.note?.takeIf { it.isNotBlank() }?.let { "備註：$it" })
    for (detail in details) {
        for (part in wrapToWidth(detail, columns - DETAIL_INDENT)) {
            rows += TicketRow.Text(" ".repeat(DETAIL_INDENT) + part)
        }
    }
    return rows
}

private fun pad(text: String, columns: Int): String {
    val gap = columns - displayWidth(text)
    return if (gap > 0) text + " ".repeat(gap) else text
}

/**
 * 80mm 紙、576 點，一行 32 個半形格。
 *
 * 也就是全形字寬 36 點。203 dpi 的熱感印表機一點約 0.125mm，所以中文字大約 4.5mm 高。
 * 比一般收據大一號是故意的：這張紙是在爐火旁邊、隔著出餐台看的，
 * 不是拿在手上讀的。58mm 紙（384 點）要另外指定欄數。
 */
const val DEFAULT_COLUMNS = 32

/** 放得下「品項 ×99」再加一點規格的最低限度，再窄就不是排版問題而是紙不對。 */
private const val MIN_COLUMNS = 16

private const val DETAIL_INDENT = 2

/** 標頭放大的倍率。與 [TicketRow.MAX_SCALE] 一致，再大就一行放不下四個中文字。 */
private const val BANNER_SCALE = 2
