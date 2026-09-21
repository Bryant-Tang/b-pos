package io.github.bryanttang.bpos.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 記住上一次登入到底是哪家店的哪個人。
 *
 * ## 為什麼不是「Firebase 自己就會記住登入狀態」就好
 *
 * Firebase Auth 確實會把登入狀態存在裝置上，App 重開之後 `currentUser` 還在。
 * 但我們要的不只是「有人登入」，是 [StaffSession.SignedIn.storeId]——
 * 所有 Firestore 路徑的第一段。那個值只存在於 ID token 的 custom claims 裡，
 * 而 token 有效期一小時，過期後要連網才換得到新的。
 *
 * 於是會發生這件事：平板整晚關著，隔天早上開店時 Wi-Fi 還沒好，
 * token 早就過期、換不到新的，claims 讀不出來。這時候如果把店員踢回登入畫面，
 * 他也登不進去（登入一樣要連網），平板就變成一塊磚——而這正是
 * SPEC 第零節第三條「離線必須還能點餐出單」要擋下來的情境。
 *
 * 所以 storeId 與角色要自己存一份。離線開機時沿用這一份，單照樣寫進本地佇列，
 * 網路回來後再送（那時 token 也換得到了）。
 */
interface SessionStore {
    fun read(): StaffSession
    fun write(session: StaffSession.SignedIn)
    fun clear()
}

/**
 * 序列化成一行 JSON 存起來。
 *
 * 讀回來時任何解不開的東西一律當成沒登入：這份快取不是真相的來源，
 * 只是省掉一次連網。寧可要求重新登入，也不要拿半個湊出來的 session
 * 去組 Firestore 路徑。
 */
object StaffSessionCodec {

    private val json = Json { ignoreUnknownKeys = true }

    fun encode(session: StaffSession.SignedIn): String = json.encodeToString(
        StoredSession.serializer(),
        StoredSession(
            uid = session.uid,
            email = session.email,
            storeId = session.storeId,
            role = session.role.name,
        ),
    )

    fun decode(raw: String?): StaffSession {
        if (raw.isNullOrBlank()) return StaffSession.SignedOut

        val stored = runCatching { json.decodeFromString(StoredSession.serializer(), raw) }
            .getOrNull()
            ?: return StaffSession.SignedOut

        val role = runCatching { StaffRole.valueOf(stored.role) }.getOrNull()
        if (stored.uid.isBlank() || stored.storeId.isBlank() || role == null) {
            return StaffSession.SignedOut
        }

        return StaffSession.SignedIn(
            uid = stored.uid,
            email = stored.email,
            storeId = stored.storeId,
            role = role,
        )
    }

    @Serializable
    private data class StoredSession(
        val uid: String,
        val email: String,
        val storeId: String,
        val role: String,
    )
}
