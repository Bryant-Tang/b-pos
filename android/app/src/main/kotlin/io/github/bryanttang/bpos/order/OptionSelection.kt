package io.github.bryanttang.bpos.order

import io.github.bryanttang.bpos.menu.Menu
import io.github.bryanttang.bpos.menu.MenuItem
import io.github.bryanttang.bpos.menu.OptionGroup
import io.github.bryanttang.bpos.menu.OptionGroupType

/** 店員在 bottom sheet 裡替某個品項選好的規格，key 是選項組 id。 */
typealias OptionSelection = Map<String, Set<String>>

/** 規格選得對不對。不合法時帶著要顯示給店員看的原因。 */
sealed interface SelectionCheck {
    data object Ok : SelectionCheck

    data class Invalid(val reason: String) : SelectionCheck
}

/**
 * 檢查一組規格選擇對這個品項合不合法。
 *
 * **為什麼平板要自己檢查一次**：伺服器當然會再擋一次，但那是單送出去幾秒後才回來的
 * 一則拒絕訊息，那時候客人已經走了、店員也忘了剛剛按了什麼。在按下「加入」的當下
 * 就擋，店員才有機會補選。這是刻意的重複，不是多餘的。
 *
 * 檢查的是三件事：
 * 1. 選的組別與選項真的存在於這個品項的菜單裡（後台改過菜單、平板還拿著舊快照時會踩到）
 * 2. 每組選的個數在 `min..max` 之間
 * 3. 單選組不能選超過一個
 */
fun checkSelection(menu: Menu, item: MenuItem, selection: OptionSelection): SelectionCheck {
    val groups = menu.optionGroupsOf(item)
    val groupIds = groups.map { it.groupId }.toSet()

    // 先擋「選了一個這個品項根本沒有的組」。這通常表示平板的菜單快照過期了，
    // 照送的話伺服器會整筆拒絕，而店員看到的會是一個跟畫面對不起來的錯誤。
    val unknownGroup = selection.keys.firstOrNull { it !in groupIds }
    if (unknownGroup != null) {
        return SelectionCheck.Invalid("「${item.name}」沒有這組規格，菜單可能已經更新，請重新整理")
    }

    for (group in groups) {
        val chosen = selection[group.groupId].orEmpty()

        val unknownOption = chosen.firstOrNull { group.option(it) == null }
        if (unknownOption != null) {
            return SelectionCheck.Invalid("「${group.name}」沒有這個選項，菜單可能已經更新，請重新整理")
        }

        if (group.type == OptionGroupType.SINGLE && chosen.size > 1) {
            return SelectionCheck.Invalid("「${group.name}」只能選一個")
        }

        if (chosen.size < group.min) {
            return SelectionCheck.Invalid("「${group.name}」至少要選 ${group.min} 個")
        }

        if (chosen.size > group.max) {
            return SelectionCheck.Invalid("「${group.name}」最多只能選 ${group.max} 個")
        }
    }

    return SelectionCheck.Ok
}

/**
 * 這組規格選擇會讓單價加減多少（顯示用）。
 *
 * 找不到的組或選項一律當 0，因為這個函式只負責算給店員看的預估金額，
 * 合不合法是 [checkSelection] 的事；在這裡再丟一次例外只會讓畫面炸掉。
 */
fun priceDeltaOf(menu: Menu, item: MenuItem, selection: OptionSelection): Int =
    menu.optionGroupsOf(item).sumOf { group -> groupDelta(group, selection[group.groupId]) }

private fun groupDelta(group: OptionGroup, chosen: Set<String>?): Int =
    chosen.orEmpty().sumOf { optionId -> group.option(optionId)?.priceDelta ?: 0 }

/**
 * 店員點了某個選項之後，整組規格選擇會變成什麼樣子。
 *
 * **單選組是「換」不是「加」**：已經選了小辣、再點大辣，結果是大辣，不是兩個都選。
 * 這條規則寫在這裡而不是交給畫面的呼叫端，是因為它是規格本身的語意——
 * 交出去的話，每個用到 bottom sheet 的地方都要自己記得實作一次，
 * 而漏掉的那次會讓畫面停在一個 [checkSelection] 一定會擋下來的狀態：
 * 店員看得到兩個都被勾起來，卻按不下「加入」，也看不出為什麼。
 *
 * 再點一次已經選起來的選項是取消選取，單選複選都一樣。可選的單選組
 * （`min = 0`）因此可以被取消成沒選，必選的組取消之後 [checkSelection] 會擋住送出，
 * 這是對的：店員要有辦法改變心意，而不是點錯一次就只能關掉重來。
 */
fun toggleOption(
    group: OptionGroup,
    selection: OptionSelection,
    optionId: String,
): OptionSelection {
    val chosen = selection[group.groupId].orEmpty()

    val next = when {
        optionId in chosen -> chosen - optionId
        group.type == OptionGroupType.SINGLE -> setOf(optionId)
        else -> chosen + optionId
    }

    return if (next.isEmpty()) {
        // 空的組直接拿掉，跟購物車的正規化規則一致：「沒選這組」與「這組選了空集合」
        // 是同一件事，留著空集合會讓兩台看起來一樣的購物車併不起來。
        selection - group.groupId
    } else {
        selection + (group.groupId to next)
    }
}
