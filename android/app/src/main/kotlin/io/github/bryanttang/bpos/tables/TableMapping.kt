package io.github.bryanttang.bpos.tables

/**
 * 把 `tenants/{storeId}/areas` 與 `tenants/{storeId}/tables` 的文件轉成領域模型。
 *
 * 命名對不上是故意的：Firestore 那邊叫 `areas`（SPEC 第三節的資料模型），
 * App 這邊叫 [Zone]。改任何一邊都會動到已經寫好的東西，所以把落差收在這一個檔案裡，
 * 不要讓它散到畫面層。
 *
 * 與 [io.github.bryanttang.bpos.order.toOpenOrder] 同一個原則：**一張桌的欄位壞掉，
 * 不該讓整張平面圖打不開。** 讀不出來的那一張跳過，其餘照常顯示——少一張桌，
 * 店員還能用別的方式點；整頁空白，他就只能重開 App。
 */

/** 沒有 [Table.tableId] 或 [Table.label] 的文件會被跳過：那兩個少了就沒有東西可以顯示或指名。 */
fun toTable(tableId: String, data: Map<String, Any?>): Table? {
    if (tableId.isBlank()) return null
    // 已下架的桌子不該出現在平面圖上。欄位缺了當成沒下架：漏顯示一張還在用的桌子，
    // 比多顯示一張已經收起來的桌子麻煩得多。
    if (data["archived"] == true) return null

    val label = (data["label"] as? String)?.takeIf { it.isNotBlank() } ?: return null

    return Table(
        tableId = tableId,
        label = label,
        zoneId = (data["areaId"] as? String).orEmpty(),
        // 座標超出 0 到 1 就夾回來，而不是丟掉這張桌。
        // 後台存壞了的話，夾住只是讓它貼在平面圖邊緣，店員看得到也按得到；
        // 丟掉則是那張桌從此點不了餐，而且畫面上沒有任何線索。
        x = ratioOf(data["x"]),
        y = ratioOf(data["y"]),
        sort = intOf(data["sort"]),
        seats = intOf(data["seats"]),
    )
}

/** 沒有名字的區域分頁標籤會是空白，不如跳過；反正桌子還是會出現在別的分頁或「未分區」。 */
fun toZone(zoneId: String, data: Map<String, Any?>): Zone? {
    if (zoneId.isBlank()) return null
    val name = (data["name"] as? String)?.takeIf { it.isNotBlank() } ?: return null
    return Zone(zoneId = zoneId, name = name, sort = intOf(data["sort"]))
}

/**
 * 平面圖座標是 0 到 1 的相對值（SPEC 第三節）。
 *
 * Firestore 的數字回 [Long] 或 [Double]，兩種都要吃得下：後台存 `x: 0` 時
 * 回來的是 Long，存 `x: 0.25` 時是 Double。
 */
private fun ratioOf(raw: Any?): Float {
    val value = (raw as? Number)?.toFloat() ?: return 0f
    if (!value.isFinite()) return 0f
    return value.coerceIn(0f, 1f)
}

private fun intOf(raw: Any?): Int = (raw as? Number)?.toInt() ?: 0
