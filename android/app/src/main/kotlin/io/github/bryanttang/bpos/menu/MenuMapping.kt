package io.github.bryanttang.bpos.menu

/**
 * 把 Firestore 的 `tenants/{storeId}/published/menu` 文件轉成 [Menu]。
 *
 * ## 這裡的取捨跟訂單、桌位那邊不一樣
 *
 * 桌位那邊壞掉的欄位是「夾回合理值」或「跳過那一張桌」，因為少一張桌店員還有別的路可以走。
 * **菜單上壞掉的是價格**，所以規則只有一條：**寧可點不到，不可以點到錯的價格。**
 *
 * 一個品項只要有任何一個必要欄位讀不出來就整筆跳過，不套預設值。
 * 價格特別要小心：`price` 讀不出來時若退回 0，那道菜就會變成免費賣出去，
 * 而且畫面上完全看不出異常——店員按下去、單出了、錢收不到。
 *
 * 整份文件只有 [Menu.version] 是不能少的：少了它就沒辦法判斷新舊，
 * 這時回 `null`，讓 [MenuRepository] 繼續用上一份快取，而不是把菜單清空。
 */
fun toMenu(data: Map<String, Any?>): Menu? {
    val version = (data["version"] as? Number)?.toLong() ?: return null

    val categories = listOfMaps(data["categories"]).mapNotNull(::toCategory)
    val optionGroups = listOfMaps(data["optionGroups"]).mapNotNull(::toOptionGroup)
    val knownGroupIds = optionGroups.mapTo(mutableSetOf()) { it.groupId }
    val items = listOfMaps(data["items"]).mapNotNull { toItem(it, knownGroupIds) }

    return Menu(
        version = version,
        categories = categories,
        items = items,
        optionGroups = optionGroups,
    )
}

private fun toCategory(data: Map<String, Any?>): MenuCategory? {
    val categoryId = nonBlank(data["categoryId"]) ?: return null
    val name = nonBlank(data["name"]) ?: return null
    return MenuCategory(
        categoryId = categoryId,
        name = name,
        sort = intOrZero(data["sort"]),
    )
}

/**
 * [knownGroupIds] 是這份文件裡真的讀得出來的選項組。
 *
 * 品項指到一個沒讀出來的組就整筆跳過，理由是**跳過比少一組選項安全**：
 * 「辣度」是必選組，組不見了的話店員會在完全沒有被問到辣度的情況下把這碗麵送進廚房，
 * 而且畫面上沒有任何線索說少問了一件事。伺服器那邊算價時會再擋一次，
 * 但那時店員已經按下送出、客人也已經走了。
 */
private fun toItem(data: Map<String, Any?>, knownGroupIds: Set<String>): MenuItem? {
    val itemId = nonBlank(data["itemId"]) ?: return null
    val name = nonBlank(data["name"]) ?: return null
    val categoryId = nonBlank(data["categoryId"]) ?: return null
    val price = nonNegativeInt(data["price"]) ?: return null

    // 沒有 takeoutPrice 是正常的（跟內用同價）；有但讀不出來或是負的就不對了，
    // 那代表後台存進了壞資料，這時不能退回內用價假裝沒事——外帶會被多收錢。
    val takeoutPrice = if (data["takeoutPrice"] == null) {
        null
    } else {
        nonNegativeInt(data["takeoutPrice"]) ?: return null
    }

    val optionGroupIds = listOfStrings(data["optionGroupIds"])
    if (optionGroupIds.any { it !in knownGroupIds }) return null

    return MenuItem(
        itemId = itemId,
        name = name,
        price = price,
        takeoutPrice = takeoutPrice,
        categoryId = categoryId,
        optionGroupIds = optionGroupIds,
        available = data["available"] as? Boolean ?: true,
        sort = intOrZero(data["sort"]),
    )
}

private fun toOptionGroup(data: Map<String, Any?>): OptionGroup? {
    val groupId = nonBlank(data["groupId"]) ?: return null
    val name = nonBlank(data["name"]) ?: return null
    val type = (data["type"] as? String)?.let { raw ->
        OptionGroupType.entries.firstOrNull { it.wireName == raw }
    } ?: return null
    val min = nonNegativeInt(data["min"]) ?: return null
    val max = nonNegativeInt(data["max"]) ?: return null

    val options = listOfMaps(data["options"]).mapNotNull(::toOption)
    if (options.isEmpty()) return null

    // min/max 互相矛盾（max < min、單選組 max > 1）時 OptionGroup 的 init 會丟例外。
    // 那是後台存進了壞設定，不該讓整份菜單載不起來，所以在這裡收掉、跳過這一組。
    return try {
        OptionGroup(
            groupId = groupId,
            name = name,
            type = type,
            min = min,
            max = max,
            options = options,
        )
    } catch (e: IllegalArgumentException) {
        null
    }
}

private fun toOption(data: Map<String, Any?>): MenuOption? {
    val optionId = nonBlank(data["optionId"]) ?: return null
    val name = nonBlank(data["name"]) ?: return null
    // priceDelta 可以是負的（「不要飯」折 10 元），所以這裡不能用 nonNegativeInt。
    // 但「讀不出來」跟「沒填」要分開：沒填是 0，型別不對就是壞資料，跳過。
    val priceDelta = when (val raw = data["priceDelta"]) {
        null -> 0
        is Number -> raw.toInt()
        else -> return null
    }
    return MenuOption(optionId = optionId, name = name, priceDelta = priceDelta)
}

private fun nonBlank(raw: Any?): String? = (raw as? String)?.takeIf { it.isNotBlank() }

private fun nonNegativeInt(raw: Any?): Int? = (raw as? Number)?.toInt()?.takeIf { it >= 0 }

/** `sort` 排錯順序只是難看，不值得把品項丟掉。 */
private fun intOrZero(raw: Any?): Int = (raw as? Number)?.toInt() ?: 0

@Suppress("UNCHECKED_CAST")
private fun listOfMaps(raw: Any?): List<Map<String, Any?>> =
    (raw as? List<*>)?.filterIsInstance<Map<*, *>>()?.map { it as Map<String, Any?> } ?: emptyList()

private fun listOfStrings(raw: Any?): List<String> =
    (raw as? List<*>)?.filterIsInstance<String>()?.filter { it.isNotBlank() } ?: emptyList()
