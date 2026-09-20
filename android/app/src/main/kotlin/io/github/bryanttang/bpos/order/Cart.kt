package io.github.bryanttang.bpos.order

import io.github.bryanttang.bpos.menu.Menu
import io.github.bryanttang.bpos.menu.MenuItem
import io.github.bryanttang.bpos.menu.priceFor
import io.github.bryanttang.bpos.sync.IntentLine
import io.github.bryanttang.bpos.sync.IntentLineOption
import io.github.bryanttang.bpos.sync.OrderIntent
import io.github.bryanttang.bpos.sync.OrderType

/**
 * 點餐畫面右側的購物車。
 *
 * 不可變：每個動作回傳一台新的購物車。點餐畫面同時只有一個店員在操作，
 * 但 Compose 需要能看出「狀態換了」才會重畫，可變的清單改到一半不會觸發重組。
 *
 * **購物車裡的金額只給店員看。** [estimatedTotal] 是拿平板手上的菜單快照算的預估值，
 * 真正的金額由伺服器算（CLAUDE.md 第二節第一條）。兩邊可能不一樣——後台在店員點餐的
 * 途中改了價格就會。這是可以接受的：伺服器算出來的才作數，而店員手上有個數字可以
 * 先跟客人講，比沒有好。
 */
data class Cart(val lines: List<CartLine> = emptyList()) {

    val isEmpty: Boolean get() = lines.isEmpty()

    /** 車上總共幾份（不是幾列）。畫面上的「共 N 份」用這個。 */
    val totalQty: Int get() = lines.sumOf { it.qty }

    /**
     * 加一個品項進來。
     *
     * 同一個品項、規格完全一樣的，會**併進既有那一列**加數量，而不是多開一列。
     * 店員連點三次牛肉麵，看到的該是「牛肉麵 ×3」，不是三行牛肉麵。
     */
    fun add(item: MenuItem, selection: OptionSelection = emptyMap(), qty: Int = 1): CartChange {
        require(qty >= 1) { "加入的數量至少為 1，收到 $qty" }

        val normalized = normalize(selection)
        val existingIndex = lines.indexOfFirst {
            it.itemId == item.itemId && it.selection == normalized
        }

        if (existingIndex >= 0) {
            val existing = lines[existingIndex]
            val newQty = existing.qty + qty
            if (newQty > IntentLine.MAX_QTY) {
                return CartChange.Rejected(
                    "「${item.name}」單列最多 ${IntentLine.MAX_QTY} 份，要更多請分兩張單",
                )
            }
            return CartChange.Updated(
                copy(lines = lines.toMutableList().also { it[existingIndex] = existing.copy(qty = newQty) }),
            )
        }

        if (lines.size >= OrderIntent.MAX_LINES) {
            return CartChange.Rejected("一張單最多 ${OrderIntent.MAX_LINES} 列，要更多請分兩張單")
        }
        if (qty > IntentLine.MAX_QTY) {
            return CartChange.Rejected(
                "「${item.name}」單列最多 ${IntentLine.MAX_QTY} 份，要更多請分兩張單",
            )
        }

        val line = CartLine(
            itemId = item.itemId,
            name = item.name,
            selection = normalized,
            qty = qty,
        )
        return CartChange.Updated(copy(lines = lines + line))
    }

    /** 改某一列的數量。數量歸零等於把那一列拿掉。 */
    fun setQty(index: Int, qty: Int): CartChange {
        val line = lines.getOrNull(index)
            ?: return CartChange.Rejected("這一列已經不在車上了")

        if (qty <= 0) return CartChange.Updated(removeAt(index))
        if (qty > IntentLine.MAX_QTY) {
            return CartChange.Rejected(
                "「${line.name}」單列最多 ${IntentLine.MAX_QTY} 份，要更多請分兩張單",
            )
        }

        return CartChange.Updated(
            copy(lines = lines.toMutableList().also { it[index] = line.copy(qty = qty) }),
        )
    }

    fun removeAt(index: Int): Cart =
        if (index !in lines.indices) this
        else copy(lines = lines.filterIndexed { i, _ -> i != index })

    fun clear(): Cart = Cart()

    /**
     * 給店員看的預估總額，整數元。
     *
     * 外帶用 `takeoutPrice`（沒填就同內用價）。菜單上查不到的品項當 0——
     * 那表示平板的菜單快照過期了，這種時候少算錢也比讓畫面整個炸掉好，
     * 而且送出時伺服器本來就會重算。
     */
    fun estimatedTotal(menu: Menu, orderType: OrderType): Int =
        lines.sumOf { line ->
            val item = menu.item(line.itemId) ?: return@sumOf 0
            (item.priceFor(orderType) + priceDeltaOf(menu, item, line.selection)) * line.qty
        }

    /**
     * 轉成送出去的意圖內容。
     *
     * 這裡**只留 itemId、數量與選到的選項 id**，名字與價格全部留在平板上。
     * 伺服器的 schema 是 `.strict()`，多帶一個欄位會讓整筆被拒絕。
     */
    fun toIntentLines(): List<IntentLine> = lines.map { line ->
        IntentLine(
            itemId = line.itemId,
            qty = line.qty,
            // 排序過才有確定的結果：同樣的選擇不該因為 Set 的迭代順序而送出不同內容，
            // 否則重送同一筆單時很難看出「這兩筆其實一樣」。
            options = line.selection.entries
                .sortedBy { it.key }
                .flatMap { (groupId, optionIds) ->
                    optionIds.sorted().map { IntentLineOption(groupId = groupId, optionId = it) }
                },
        )
    }

    /** 整組空掉的選項組直接拿掉，這樣「沒選辣度」與「辣度選了空集合」會併成同一列。 */
    private fun normalize(selection: OptionSelection): OptionSelection =
        selection.filterValues { it.isNotEmpty() }
}

/**
 * 購物車裡的一列。
 *
 * [name] 是加入當下的品項名稱快照，只為了畫面好看；真正的鍵是 [itemId]。
 */
data class CartLine(
    val itemId: String,
    val name: String,
    val selection: OptionSelection,
    val qty: Int,
) {
    init {
        require(qty in 1..IntentLine.MAX_QTY) { "數量必須介於 1 與 ${IntentLine.MAX_QTY} 之間，收到 $qty" }
    }
}

/**
 * 一次購物車操作的結果。
 *
 * 用回傳值而不是丟例外，是因為「超過上限」是店員按出來的正常情況，
 * 畫面要把原因顯示出來，不是崩掉。
 */
sealed interface CartChange {
    data class Updated(val cart: Cart) : CartChange

    data class Rejected(val reason: String) : CartChange
}
