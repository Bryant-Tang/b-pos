package io.github.bryanttang.bpos.auth

import android.content.Context

/**
 * [SessionStore] 的實作，存在 SharedPreferences。
 *
 * 存的是 uid、email、storeId 與角色，沒有密碼也沒有 token——token 由
 * Firebase SDK 自己管。所以這裡不需要 EncryptedSharedPreferences：
 * 能讀到這個檔案的人早就能讀 Firebase SDK 存在同一個沙箱裡的 token 了，
 * 加密只會多一個依賴與一組會在換裝置時解不開的金鑰。
 */
class SharedPreferencesSessionStore(context: Context) : SessionStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(): StaffSession = StaffSessionCodec.decode(prefs.getString(KEY_SESSION, null))

    override fun write(session: StaffSession.SignedIn) {
        prefs.edit().putString(KEY_SESSION, StaffSessionCodec.encode(session)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_SESSION).apply()
    }

    private companion object {
        const val FILE_NAME = "staff_session"
        const val KEY_SESSION = "session"
    }
}
