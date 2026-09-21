package io.github.bryanttang.bpos.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 平板的本地資料庫。
 *
 * SPEC 第六節〈離線策略〉：現場操作先寫這裡、UI 立刻反應，再非同步同步上去。
 * 斷網時店員仍要能點餐出單，所以「寫得進 Room」就算操作成功，
 * 不等 Firestore 回應。
 */
@Database(
    entities = [OrderIntentOutboxEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class BposDatabase : RoomDatabase() {

    abstract fun orderIntentOutboxDao(): OrderIntentOutboxDao

    companion object {
        private const val NAME = "b-pos.db"

        @Volatile
        private var instance: BposDatabase? = null

        fun get(context: Context): BposDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): BposDatabase =
            Room.databaseBuilder(context, BposDatabase::class.java, NAME)
                // 刻意不呼叫 fallbackToDestructiveMigration()：那會在 schema 變動時
                // 直接砍掉重建，而這張表裡躺的是還沒送出去的單。寧可升級失敗當掉，
                // 也不要安靜地把店家的單刪掉。
                .build()
    }
}
