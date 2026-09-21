package io.github.bryanttang.bpos.ui.order

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.bryanttang.bpos.menu.Menu
import io.github.bryanttang.bpos.menu.MenuItem
import io.github.bryanttang.bpos.menu.MenuOption
import io.github.bryanttang.bpos.menu.OptionGroup
import io.github.bryanttang.bpos.menu.OptionGroupType
import io.github.bryanttang.bpos.order.OptionSelection
import io.github.bryanttang.bpos.order.SelectionCheck
import io.github.bryanttang.bpos.order.checkSelection
import io.github.bryanttang.bpos.order.toggleOption
import io.github.bryanttang.bpos.ui.theme.BPosTheme

/**
 * 規格選項的內容（SPEC 第六節：規格選項用 bottom sheet）。
 *
 * 這裡只畫內容，不畫 sheet 本身的殼——殼由呼叫端用 `ModalBottomSheet` 套上去。
 * 分開的好處是這塊可以在 `@Preview` 裡直接看，不用真的把 sheet 拉起來。
 *
 * 一樣不持有狀態：[selection] 與展開與否都由呼叫端管。店員選到一半切去看別桌
 * 再切回來，選過的東西不該不見。
 */
@Composable
fun OptionSheetContent(
    menu: Menu,
    item: MenuItem,
    selection: OptionSelection,
    onSelectionChange: (OptionSelection) -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val check = checkSelection(menu, item, selection)

    Column(modifier = modifier.fillMaxWidth().padding(16.dp)) {
        Text(text = item.name, style = MaterialTheme.typography.headlineSmall)

        menu.optionGroupsOf(item).forEach { group ->
            OptionGroupSection(
                group = group,
                chosen = selection[group.groupId].orEmpty(),
                // 這裡算好「按下去之後整組會變成什麼」再往外傳，而不是傳「誰被按了」。
                // 單選組要換掉舊選項這條規則屬於規格本身的語意，交給呼叫端的話，
                // 每個用到這個 sheet 的地方都得自己記得實作一次。
                onToggle = { optionId ->
                    onSelectionChange(toggleOption(group, selection, optionId))
                },
            )
        }

        // 不合法時把原因寫出來並鎖住按鈕，而不是讓店員按下去之後才被伺服器退回。
        // 那時候客人已經走了，店員也忘了剛剛選了什麼。
        if (check is SelectionCheck.Invalid) {
            Text(
                text = check.reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 12.dp),
            )
        }

        Button(
            onClick = onConfirm,
            enabled = check is SelectionCheck.Ok,
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text("加入")
        }
    }
}

@Composable
private fun OptionGroupSection(
    group: OptionGroup,
    chosen: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = group.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = requirementLabel(group),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }

        group.options.forEach { option ->
            OptionRow(
                group = group,
                option = option,
                selected = option.optionId in chosen,
                onToggle = { onToggle(option.optionId) },
            )
        }
    }
}

@Composable
private fun OptionRow(
    group: OptionGroup,
    option: MenuOption,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            // 整列都可以點，不是只有那個小圓圈。店員站著單手操作，點擊目標越大越好。
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 控制項本身不再是一個獨立的點擊目標，否則讀螢幕會把同一列讀成兩個項目。
        val controlModifier = Modifier.clearAndSetSemantics { }
        if (group.type == OptionGroupType.SINGLE) {
            RadioButton(selected = selected, onClick = null, modifier = controlModifier)
        } else {
            Checkbox(checked = selected, onCheckedChange = null, modifier = controlModifier)
        }

        Text(
            text = option.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        if (option.priceDelta != 0) {
            Text(
                text = priceDeltaLabel(option.priceDelta),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 「必選」「最多 2 個」這種提示，讓店員在選之前就知道規則。 */
internal fun requirementLabel(group: OptionGroup): String = when {
    group.min > 0 && group.min == group.max -> "必選 ${group.min} 個"
    group.min > 0 -> "至少 ${group.min} 個，最多 ${group.max} 個"
    group.max == 1 -> "可選 1 個"
    else -> "最多 ${group.max} 個"
}

/** 價差的顯示。負的價差要看得出來是減的，所以正值才補上加號。 */
internal fun priceDeltaLabel(priceDelta: Int): String =
    if (priceDelta > 0) "+$$priceDelta" else "-$${-priceDelta}"

/*
 * 預覽。品項、規格、價差全部是虛構的（CLAUDE.md 第一節）。
 */

private val previewSpiceGroup = OptionGroup(
    groupId = "group_spice",
    name = "辣度",
    type = OptionGroupType.SINGLE,
    min = 1,
    max = 1,
    options = listOf(
        MenuOption(optionId = "opt_none", name = "不辣"),
        MenuOption(optionId = "opt_mild", name = "小辣"),
        MenuOption(optionId = "opt_hot", name = "大辣"),
    ),
)

private val previewToppingGroup = OptionGroup(
    groupId = "group_topping",
    name = "加料",
    type = OptionGroupType.MULTI,
    min = 0,
    max = 2,
    options = listOf(
        MenuOption(optionId = "opt_extra_noodle", name = "加麵", priceDelta = 20),
        MenuOption(optionId = "opt_extra_beef", name = "加牛肉", priceDelta = 60),
        MenuOption(optionId = "opt_no_veg", name = "不要青菜", priceDelta = -10),
    ),
)

private val previewItem = MenuItem(
    itemId = "item_beef_noodle",
    name = "牛肉麵",
    price = 180,
    categoryId = "cat_noodle",
    optionGroupIds = listOf("group_spice", "group_topping"),
)

private val previewMenuForSheet = Menu(
    version = 1,
    categories = emptyList(),
    items = listOf(previewItem),
    optionGroups = listOf(previewSpiceGroup, previewToppingGroup),
)

/** 必選的辣度還沒選：底下要出現紅字，「加入」是鎖住的。 */
@Preview(name = "規格（還沒選必選項）", showBackground = true, widthDp = 600)
@Composable
private fun OptionSheetIncompletePreview() {
    BPosTheme {
        OptionSheetContent(
            menu = previewMenuForSheet,
            item = previewItem,
            selection = emptyMap(),
            onSelectionChange = {},
            onConfirm = {},
        )
    }
}

/** 選滿了：紅字消失，「加入」可以按。 */
@Preview(name = "規格（選好了）", showBackground = true, widthDp = 600)
@Composable
private fun OptionSheetCompletePreview() {
    BPosTheme {
        OptionSheetContent(
            menu = previewMenuForSheet,
            item = previewItem,
            selection = mapOf(
                "group_spice" to setOf("opt_mild"),
                "group_topping" to setOf("opt_extra_noodle", "opt_no_veg"),
            ),
            onSelectionChange = {},
            onConfirm = {},
        )
    }
}
