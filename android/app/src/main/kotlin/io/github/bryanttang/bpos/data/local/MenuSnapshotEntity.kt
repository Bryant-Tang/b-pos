package io.github.bryanttang.bpos.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 這台平板手上那份菜單，一間店一列。
 *
 * 主鍵用 `storeId` 而不是固定的單列，是因為同一台平板可能先登入測試的店、
 * 再登入正式的店（SPEC 第九節：測試專案與店家專案是兩個 Firebase 專案）。
 * 混到的話畫面上會是另一間店的價格，而價格是這整套系統最不能出錯的東西。
 *
 * 整份菜單存成一格 JSON，沒有拆成分類／品項／選項三張表。理由跟 outbox 一樣：
 * 這份東西是**整份換掉**的快照，永遠整份讀出來用，從來不單獨查某一個品項，
 * 拆表只會換來三組 join 和三份要維護的升級腳本。
 */
@Entity(tableName = "menu_snapshot")
data class MenuSnapshotEntity(
    @PrimaryKey
    @ColumnInfo(name = "store_id") val storeId: String,
    /** 發佈時的 `Date.now()`，用來跟伺服器上那份比對是不是同一版。 */
    @ColumnInfo(name = "version") val version: Long,
    /** [io.github.bryanttang.bpos.menu.Menu] 的 JSON。 */
    @ColumnInfo(name = "menu_json") val menuJson: String,
    /** 這份快照是什麼時候從伺服器抓下來的，給「菜單是幾點的」這種提示用。 */
    @ColumnInfo(name = "fetched_at") val fetchedAtMillis: Long,
)
