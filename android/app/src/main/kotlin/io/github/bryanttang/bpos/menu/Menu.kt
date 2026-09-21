package io.github.bryanttang.bpos.menu

import io.github.bryanttang.bpos.sync.OrderType

/**
 * 平板手上的菜單快照，對應 Firestore 的 `published/menu`（SPEC 第三節〈發佈模型〉）。
 *
 * 菜單**不用 listener**：開店時讀一次整份文件（1 次讀取），存進 Room，
 * 之後靠 [version] 比對決定要不要更新（SPEC 第六節〈監聽器範圍〉）。
 * 平板整天不關機，掛一個監聽器在菜單上一天可以燒掉幾萬次讀取。
 */
data class Menu(
    /** 發佈時的 `Date.now()`。比這個數字大才算新版本。 */
    val version: Long,
    val categories: List<MenuCategory>,
    val items: List<MenuItem>,
    val optionGroups: List<OptionGroup>,
) {
    private val itemsById = items.associateBy { it.itemId }
    private val groupsById = optionGroups.associateBy { it.groupId }

    fun item(itemId: String): MenuItem? = itemsById[itemId]

    fun optionGroup(groupId: String): OptionGroup? = groupsById[groupId]

    /**
     * 某個分類底下、現在點得到的品項，依 `sort` 排序。
     *
     * 已下架（archived）的不會出現在發佈檔裡，所以這裡只需要濾掉今日售完的。
     */
    fun availableItemsIn(categoryId: String): List<MenuItem> =
        items.filter { it.categoryId == categoryId && it.available }
            .sortedBy { it.sort }

    /** 這個品項要顯示／估算的規格選項群組，順序照品項自己指定的。 */
    fun optionGroupsOf(item: MenuItem): List<OptionGroup> =
        item.optionGroupIds.mapNotNull { groupsById[it] }
}

data class MenuCategory(
    val categoryId: String,
    val name: String,
    val sort: Int,
)

/**
 * 一個品項。
 *
 * [price] 是**內用含稅價**，整數元（SPEC 第一節已經把含稅與否定下來了）。
 * [takeoutPrice] 沒填就跟內用同價。
 *
 * 這些價格在平板上**只拿來顯示給店員看**，送出下單意圖時一個金額欄位都不會帶，
 * 實際金額一律由伺服器重算（CLAUDE.md 第二節第一條）。
 */
data class MenuItem(
    val itemId: String,
    val name: String,
    val price: Int,
    val takeoutPrice: Int? = null,
    val categoryId: String,
    val optionGroupIds: List<String> = emptyList(),
    val available: Boolean = true,
    val sort: Int = 0,
) {
    init {
        require(price >= 0) { "價格不可為負，收到 $price" }
        require(takeoutPrice == null || takeoutPrice >= 0) {
            "外帶價不可為負，收到 $takeoutPrice"
        }
    }
}

/**
 * 一組規格選項，例如「辣度」（單選）或「加料」（複選）。
 *
 * [min] 與 [max] 是**這一組**可以選幾個。`min = 0` 表示可以整組不選。
 */
data class OptionGroup(
    val groupId: String,
    val name: String,
    val type: OptionGroupType,
    val min: Int,
    val max: Int,
    val options: List<MenuOption>,
) {
    init {
        require(min >= 0) { "min 不可為負，收到 $min" }
        require(max >= min) { "max（$max）不可小於 min（$min）" }
        // 單選組選超過一個在語意上就不成立了，讓後台的錯誤設定在這裡就被擋下來，
        // 而不是等店員點了兩個口味才發現。
        require(type != OptionGroupType.SINGLE || max <= 1) {
            "單選組的 max 不可大於 1，收到 $max"
        }
    }

    fun option(optionId: String): MenuOption? = options.firstOrNull { it.optionId == optionId }
}

enum class OptionGroupType(val wireName: String) {
    SINGLE("single"),
    MULTI("multi"),
    ;

    companion object {
        fun fromWireName(value: String): OptionGroupType =
            entries.firstOrNull { it.wireName == value }
                ?: throw IllegalArgumentException("未知的選項組型態：$value")
    }
}

/**
 * 一個選項。
 *
 * [priceDelta] 可以是 0 或負數（例如「不要飯」折 10 元），所以不要在這裡擋負值。
 */
data class MenuOption(
    val optionId: String,
    val name: String,
    val priceDelta: Int = 0,
)

/**
 * 這張單的型態底下，這個品項的單價。
 *
 * 外帶用 [MenuItem.takeoutPrice]，沒設就退回內用價。候位單之後會轉成內用，所以照內用價。
 *
 * 購物車的預估金額與點餐畫面的格子都走這一個函式：兩邊各寫一次的話，
 * 遲早會出現「格子上寫 170、車上加成 180」這種對不起來的畫面。
 */
fun MenuItem.priceFor(orderType: OrderType): Int =
    if (orderType == OrderType.TAKEOUT) takeoutPrice ?: price else price
