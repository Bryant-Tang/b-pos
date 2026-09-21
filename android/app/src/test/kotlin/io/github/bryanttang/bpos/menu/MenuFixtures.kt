package io.github.bryanttang.bpos.menu

/**
 * 測試用的假菜單。
 *
 * 店名、品項、價格全部是虛構的（CLAUDE.md 第一節：真實資料一律不進版控）。
 * 要加新的品項請沿用「牛肉麵」「珍珠奶茶」這種一看就知道是範例的名字。
 */
object MenuFixtures {

    const val CATEGORY_NOODLE = "cat_noodle"
    const val CATEGORY_DRINK = "cat_drink"

    const val ITEM_BEEF_NOODLE = "item_beef_noodle"
    const val ITEM_BUBBLE_TEA = "item_bubble_tea"

    const val GROUP_SPICE = "group_spice"
    const val GROUP_TOPPING = "group_topping"

    const val OPTION_MILD = "opt_mild"
    const val OPTION_HOT = "opt_hot"
    const val OPTION_EXTRA_NOODLE = "opt_extra_noodle"
    const val OPTION_NO_RICE = "opt_no_rice"

    /** 辣度：單選，一定要選一個。 */
    val spiceGroup = OptionGroup(
        groupId = GROUP_SPICE,
        name = "辣度",
        type = OptionGroupType.SINGLE,
        min = 1,
        max = 1,
        options = listOf(
            MenuOption(optionId = OPTION_MILD, name = "小辣", priceDelta = 0),
            MenuOption(optionId = OPTION_HOT, name = "大辣", priceDelta = 0),
        ),
    )

    /** 加料：複選，可以不選，最多兩個。其中一個是負的價差。 */
    val toppingGroup = OptionGroup(
        groupId = GROUP_TOPPING,
        name = "加料",
        type = OptionGroupType.MULTI,
        min = 0,
        max = 2,
        options = listOf(
            MenuOption(optionId = OPTION_EXTRA_NOODLE, name = "加麵", priceDelta = 20),
            MenuOption(optionId = OPTION_NO_RICE, name = "不要飯", priceDelta = -10),
        ),
    )

    val beefNoodle = MenuItem(
        itemId = ITEM_BEEF_NOODLE,
        name = "牛肉麵",
        price = 180,
        takeoutPrice = 170,
        categoryId = CATEGORY_NOODLE,
        optionGroupIds = listOf(GROUP_SPICE, GROUP_TOPPING),
        sort = 1,
    )

    /** 沒有外帶價，外帶時應該退回內用價。 */
    val bubbleTea = MenuItem(
        itemId = ITEM_BUBBLE_TEA,
        name = "珍珠奶茶",
        price = 60,
        categoryId = CATEGORY_DRINK,
        sort = 1,
    )

    val menu = Menu(
        version = 1_700_000_000_000,
        categories = listOf(
            MenuCategory(categoryId = CATEGORY_NOODLE, name = "麵食", sort = 1),
            MenuCategory(categoryId = CATEGORY_DRINK, name = "飲料", sort = 2),
        ),
        items = listOf(beefNoodle, bubbleTea),
        optionGroups = listOf(spiceGroup, toppingGroup),
    )
}
