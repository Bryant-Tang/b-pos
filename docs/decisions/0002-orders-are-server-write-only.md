# 0002 — orders 對客戶端完全唯讀

- 日期：2026-09-20
- 狀態：已決定
- 範圍：取代 `docs/SPEC.md` 第四節〈規則〉裡 `match /orders/{id}` 的 `allow update`

## 背景

SPEC 第四節給店員開了一條直接改訂單的路，用「總額不變」當守門員：

```js
allow update: if isStaff(storeId)
              && request.resource.data.total == resource.data.total;
```

同一節的〈五個容易寫錯的地方〉第三條說明了用意：「店員可以改訂單狀態，
但不該能直接改 `total`」。

## 問題：守總額守不住錢

總額不變不等於帳沒被動。同一條規則底下，一個登入的店員仍然可以：

| 動作 | 後果 |
| --- | --- |
| 把 `status` 直接改成 `'closed'` | 繞過 `closeOrder`，不產生 `receipts`、不寫 `businessDate`、不搬 archive。這張單的營收從當日報表上消失，現金進誰口袋沒有紀錄 |
| 改 `businessDate` | 把營收搬到別天，日結對不起來 |
| 改 `payment` | 現金單改標成行動支付，或竄改收款金額與找零 |
| 改 `lines` 裡的 `name` / `unitPriceDineIn` / `taxMode` | 價格快照是第三節〈三個必須遵守的資料規則〉第二條的基礎，改掉等於昨天的帳今天變了 |
| 改 `lines[].voidedAt` | 作廢一筆已出餐的品項，廚房做好的菜從帳上消失 |

其中大部分連總額都不用動。Rules 沒有迴圈，檢查不了 `lines` 陣列裡每一筆的內容，
所以「守住 total」這條路本質上補不完。

## 決定

**`orders` 對所有客戶端完全唯讀：`allow write: if false`（create、update、delete 全部）。**

店員讀得到（`get` 與 `list`），要改一律走伺服器：

- 建單與加點 → `order_intents`，見 [0001](0001-offline-write-path.md)
- 退點、折扣、拆單、併單、結帳、確認顧客單 → SPEC 第五節列的 callable functions

這不是新增限制，而是把已經存在的事實寫進規則：SPEC 第五節把每一種訂單變更
都指派給了某一支 Function，沒有任何一種是設計給客戶端直接寫的。
欄位白名單（`affectedKeys().hasOnly([...])`）推到底就是空白名單。

## 後果

目前還沒有任何程式碼依賴 orders 的客戶端寫入，所以這條現在的成本是零。
但有兩件事之後會撞上它，要在寫 Android app 之前各自決定，不要臨時放寬規則：

1. **離線現金結帳。** SPEC 第六節寫明「離線時店員仍可正常點餐、出單、現金結帳」，
   而 `closeOrder` 是 callable，離線叫不到。解法多半會長得像 0001：
   一個 `close_intents` collection，由觸發器完成真正的結帳。
2. **標記已列印。** SPEC 第四節要求 `printedAt != null` 的 line 不可再編輯，
   但沒有任何一支 Function 負責寫 `printedAt`——列印發生在平板上，而且斷網也要能印。
   `printedAt` 目前放在 `lines[]` 裡面，Rules 沒辦法只放行陣列中的某個欄位，
   所以這件事很可能要把列印狀態移到訂單的頂層欄位或獨立的子集合。

兩件都不在這次的範圍內，這裡先記下來，避免之後有人為了讓功能動起來
而把 `allow update` 直接開回去。
