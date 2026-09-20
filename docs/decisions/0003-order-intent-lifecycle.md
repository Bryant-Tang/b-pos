# 0003 — order_intents 的生命週期與幾個 SPEC 沒寫到的欄位

- 日期：2026-09-20
- 狀態：已決定
- 範圍：實作 [0001](0001-offline-write-path.md) 的觸發器時需要、但 `docs/SPEC.md` 第三節沒有列出的欄位與集合

## 意圖的三種結局

店員平板寫進 `order_intents` 之後，意圖文件只會停在三種狀態之一，
平板靠這個判斷一筆單到底怎麼了：

| 狀態 | 文件上看得到 | 意思 |
| --- | --- | --- |
| 已套用 | `appliedAt`、`orderId` | 訂單已經建好或已經加點上去 |
| 已拒絕 | `rejectedAt`、`rejectReason`、`rejectDetail` | 重試幾次都不會成功，原因寫在上面 |
| 還在路上 | 兩者皆無 | 還沒送到伺服器，或伺服器暫時性失敗 |

**拒絕一定要寫回文件，不能用丟例外了事。** 觸發器丟出去之後，店員只會看到單沒出現，
不知道是品項被下架、桌號打錯，還是菜單根本還沒發佈。

`rejectReason` 目前有七種：`invalid_intent`、`menu_not_published`、`unknown_table`、
`order_closed`、`order_type_mismatch`、`table_mismatch`、`pricing_error`。

### 沒有開 retry

`onDocumentCreated` 可以設 `retry: true`，這裡刻意不開。所有「重試也不會成功」的失敗
都已經走上面的拒絕路徑了，開 retry 只會讓一支固定失敗的程式對每一張單重試 24 小時，
那是 SPEC 第九節最不想看到的帳單形狀。代價是暫時性失敗要靠平板自己補送——
而平板本來就要維護「還在路上」這份清單。

## 新增的欄位與集合

SPEC 第三節的訂單結構沒有這幾個，但實作上需要：

### `orders.appliedIntentIds: string[]`

冪等鍵的落腳處。觸發器是 at-least-once，同一份意圖可能被送進來兩次以上；
在 transaction 裡檢查 `intentId` 在不在這個陣列裡，是整套離線設計能成立的前提
（[0001](0001-offline-write-path.md) 的〈後果〉第一條）。順便也是加點的判斷依據：
同一個 `orderId`、不同的 `intentId`，就是加點。

### `orders.menuVersion: number | null`

這張單是用哪一版 `published/menu` 算出來的。價格快照已經存在 `lines` 裡了，
這個欄位是為了對帳時回答「這張單為什麼是這個價」——翻 `published/menu` 的版本就好，
不必去猜當時菜單長什麼樣。

### `tenants/{storeId}/counters/{businessDate}`

外帶與候位的 4 碼發號（SPEC 第十四節〈訂單號規則〉：每日歸零、當日不重複）。
`{ nextPickupCode: number }`，在同一個 transaction 裡遞增。

**鍵是營業日不是日曆日。** 用日曆日的話，開到凌晨的店會在午夜把號碼歸零，
同一個晚上出現兩張 #0412。

這個 collection 沒有寫進 `firestore.rules`，所以客戶端一律讀不到寫不到——
發號是伺服器的事，這是對的預設。

### `tenants/{storeId}/settings/business`

`{ dayCloseHour: number, timeZone: string }`，預設凌晨 5 點與 `Asia/Taipei`。
SPEC 第三節說 `businessDate` 「由 Function 依店家設定的換日時間（預設凌晨 5 點）計算」，
但沒有指名這個設定放哪裡；`settings/pricing` 已經是既有慣例（SPEC 第八、十五節），
所以換日設定也放在 `settings/` 底下。

設定讀不到或格式不對時退回預設值而不是拒絕下單：設定壞掉是老闆可以自己修好的事，
不該變成整間店點不了餐的理由。同理，`settings/pricing.dineInServiceCharge` 不合法時退回 0——
退回 0 的方向是少收不是多收。

## 還沒做的

- `settings/{docId}` 還沒有 Rules，目前被預設全關擋著。等店家後台（SPEC 第十一節階段 3）
  要編輯服務費與換日時間時再一起加，那時才知道該讓誰讀、誰寫。
- `businessDate` 建單時寫 `null`，由 `closeOrder` 在結帳當下寫入，營收歸給結帳那一天
  （SPEC 第五節的 Function 清單）。`closeOrder` 還沒實作。
- 意圖套用後要清除或設 TTL（[0001](0001-offline-write-path.md) 的〈後果〉第三條），還沒做。
