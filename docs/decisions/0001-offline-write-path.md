# 0001 — 離線時店員訂單的寫入路徑

- 日期：2026-09-20
- 狀態：已決定
- 範圍：補充 `docs/SPEC.md` 第二、四、六節，衝突處以本文為準

## 背景：SPEC 原文三處互相矛盾

| 出處 | 說法 |
| --- | --- |
| 第二節〈三條資料路徑〉 | 店員平板「直連 Firestore 讀寫 `orders` / `tables`」 |
| 第四節〈規則〉 | `match /orders/{id}` 的 `allow create: if false`，一律走 `createOrder` |
| 第六節〈離線策略〉 | orderId 由客戶端產生 UUID，回線後用 `set()` 而非 `add()` |

這三條不能同時成立。Rules 把 `create` 擋死，離線產生的單就 `set()` 不進去；
而 Firestore 的離線持久化只能排隊**直連寫入**，沒有辦法排隊 callable function 呼叫，
所以「離線先寫、回線同步」與「一律走 Function」在原文的寫法下是互斥的。

另外澄清一個不是取捨點的事：**斷網期間三台平板看不到彼此的單，是離線的固有限制。**
Firestore 的離線快取是每台裝置各自的，沒有伺服器就沒有裝置間同步，
任何方案都一樣，不能拿來當選型依據。

## 決定

**新增 `order_intents` collection 作為店員端的唯一寫入點，由觸發器在伺服器端算價並產生真正的 order。**

```
tenants/{storeId}/order_intents/{intentId}
  { intentId: string,        // 客戶端產生的 UUID，冪等鍵
    orderId: string,         // 客戶端產生的 UUID，離線時就有穩定識別
    orderType: 'dine_in' | 'takeout' | 'waitlist',
    tableId: string | null,
    lines: [{ itemId, qty, options: [{ groupId, optionId }] }],   // 不含任何金額
    createdBy: string,       // uid
    clientCreatedAt: Timestamp }
```

資料流：

```
店員平板 ──set()──> order_intents      （Firestore SDK 離線時自動排隊，回線自動送出）
                        │
                        └─ onDocumentCreated 觸發器
                             ├─ zod 驗證（.strict()）
                             ├─ 從 published/menu 查價，呼叫 calcOrderLines
                             └─ 依 intentId 冪等地建立／附加 orders/{orderId}
```

Rules：

- `order_intents`：`allow create: if isStaff(storeId)`，且欄位白名單不得含任何金額欄位；
  `allow update, delete: if false`
- `orders`：`allow create: if false` **維持不變**

## 理由

三條不可違反的原則全部保住：客戶端不送金額（intent 只有 itemId 與數量）、
顧客仍不能寫入、離線仍能點餐出單。

而同步這一段交給 Firestore SDK 自己的離線佇列，不必手刻 outbox 的網路重試與指數退避。
SPEC 第六節的 Room outbox 仍然需要，但職責縮小成「本機 UI 狀態與列印佇列」，
不再負責跟伺服器之間的重試。

被否決的替代方案是「outbox 存意圖，回線後逐筆呼叫 `addOrderLines`」：同樣守得住三條原則，
但要自己實作 WorkManager 重試、退避與冪等，而這些 Firestore SDK 已經做好了。

## 後果

- **觸發器是 at-least-once，必須冪等。** 用 `intentId` 當鍵，在 transaction 裡檢查是否已套用。
  這件事在任一方案都躲不掉。
- **UI 要合併兩個來源**：已由伺服器確認的 `orders`，與本機尚未送出的 intents。
  這點兩個方案相同。
- `order_intents` 會持續累積，需要 TTL policy 或在套用後由觸發器清除。
