package io.github.bryanttang.bpos.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MenuSnapshotDao {

    @Query("SELECT * FROM menu_snapshot WHERE store_id = :storeId")
    suspend fun find(storeId: String): MenuSnapshotEntity?

    /**
     * 存下新的一份。
     *
     * 這裡 REPLACE 是對的（跟 outbox 的 IGNORE 相反）：菜單快照沒有「正在處理中」
     * 的狀態要保護，新的一版就是要蓋掉舊的那一版。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(snapshot: MenuSnapshotEntity)

    @Query("DELETE FROM menu_snapshot WHERE store_id = :storeId")
    suspend fun delete(storeId: String)
}
