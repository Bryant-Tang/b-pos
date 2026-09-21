# 0004 — 顧客自助單的 session 怎麼開，以及那把鎖放哪

- 日期：2026-09-21
- 狀態：已決定
- 範圍：實作 `createOrder`（`docs/SPEC.md` 第五節）時，SPEC 的範例程式沒有交代清楚的幾件事

## 背景：SPEC 的範例擋不住它自己要擋的東西

SPEC 第五節把整支 `createOrder` 包在 transaction 裡，理由寫得很明白：

> 整段放在 transaction 裡，是為了避免兩位客人同時按送出時開出兩個 session。

但範例裡找 session 的方式是查詢：

```ts
let session = await findActiveSession(tx, storeId, table.id);
if (!session) { session = newSession(storeId, table.id); }
```

**Firestore 的 transaction 鎖得住讀到的文件，鎖不住「還不存在的文件」。**
兩位客人同時按送出時，兩邊的查詢都會回空集合、都判斷「沒有 active session」，
然後各自建一個——正是這段 transaction 想避免的結果。這不是 Firestore 的 bug，
是它沒有 predicate lock（關聯式資料庫用來擋幻讀的那種鎖）的必然結果。

## 決定

**在桌位文件上放一個 `activeSessionId` 指標，當作那把鎖。**

```
tenants/{storeId}/tables/{tableId}
  { ...,
    activeSessionId: string | null }   // 由伺服器寫，客戶端一律不可改
```

`createOrder` 的 transaction 讀這份文件、開新 session 時寫回去。兩位客人同時送出，
就是兩筆對同一份文件的寫入，Firestore 會讓其中一筆重試；重試時讀到前一筆寫下的
指標，於是第二位客人的品項被加到同一張單上。這是實打實的寫入衝突，不靠查詢的時序。

### 為什麼不是新開一個 collection 專門放鎖

放在桌位文件上不只是鎖，它同時回答了「這張桌現在是哪一個場次」，而那是
`createOrder` 本來就要知道的事。另開一個 `table_locks` 只會多一份要維護的狀態，
而且它會和桌位文件不同步。

### 規則要跟著改

`firestore.rules` 的 `tables` update 原本只擋 `qrToken`，現在要一起擋
`activeSessionId`：

```
allow update: if isOwner(storeId)
              && !changedKeys().hasAny(['qrToken', 'activeSessionId']);
```

老闆在平板上拖曳桌位走的是同一條 update。少了這行，任何有 owner claim 的人
都能把一張桌的指標指到別組客人的 session 上，新坐下的客人就會把餐點加到
陌生人的帳單裡。

### 誰負責把指標清掉

**`closeOrder` 結帳時要在同一個 transaction 裡把 `activeSessionId` 設回 `null`。**
那支還沒實作，所以這裡先做了防呆：指標指到的 session 如果已經不是 `active`，
就當成沒有 active session、開一張新單。這符合 SPEC 第六節〈狀態轉換規則〉的
「已結帳 → 用餐中：新客人下單，直接開新 session 與新訂單」，
而且萬一哪天清指標那一步漏了，結果是多開一張單，不是這張桌從此點不了餐。

`releaseTables` 排程（結帳滿 3 小時釋放桌位）也一樣，它清的是同一個欄位。

## 冪等鍵：requestId

SPEC 第五節的 `createOrder` 範例沒有冪等鍵，這裡補上了一個。

沒有它的話，**同一次送出打進來兩次就會真的點兩份**：網路慢的時候客人會連按兩下送出，
前端收不到回應時也會重試，而第二次呼叫會讀到桌位上已經有 active session，
於是走加點那條路，把同一批品項再加一次。客人點一份牛肉麵、帳上變兩份，
店員在平板上還看不出那是重複還是客人真的多點了一份。

`activeSessionId` 那把鎖解決的是另一件事——「兩位**不同**客人的第一次送出互相打架」，
它不會、也不該擋掉同一位客人的重送。

做法與店員端 `order_intents` 的 `intentId` 相同（[0001](0001-offline-write-path.md)）：

```
CreateOrderInput.requestId: string        // 客戶端 crypto.randomUUID()
tenants/{storeId}/orders/{orderId}
  { appliedRequestIds: string[] }         // 用過的 requestId，在 transaction 內比對
```

三個細節：

- **必填不是選填。** 選填等於讓忘了帶的客戶端安靜地失去保護。
- **比對一定要在 transaction 裡。** 兩次呼叫同時進來時，在外面比對會兩邊都讀到
  「還沒用過」，等於沒做。
- **重播已結帳的單要回得到那張單，不是回「本桌已結帳」。** 那次送出早就成功了，
  客人只是沒收到回應。所以冪等檢查排在狀態檢查之前。

`appliedIntentIds`（店員端）與 `appliedRequestIds`（顧客端）刻意分成兩個欄位：
兩邊是不同的 ID 空間，混在一起之後沒有人分得出一筆紀錄是誰留下的。

## 其他與 SPEC 範例不同的地方

### 限流排在查桌號之前

SPEC 的範例是「先驗 token、再限流」。這裡對調：限流要擋的就是「一直打這支函式」
本身，排在讀取之後等於每一次被擋下的呼叫仍然花掉一次 Firestore 查詢
（SPEC 第九節〈讀取優化守則〉）。代價是 token 打錯也會計次，
而實務上 token 是從 QR code 掃來的，不會打錯。

限流是**固定視窗**（`rate_limits/{uid}` 存 `count` 與 `windowStart`），
所以跨視窗邊界最壞情況可以在很短時間內送出兩倍的量。這是刻意的取捨：
滑動視窗要存每一次的時間戳，文件會隨上限線性變大，而真正的門檻是
App Check（SPEC 第四節），限流擋的是手滑連按與隨手寫的腳本。

另外，匿名帳號可以無限申請，所以**按 uid 的限流本來就擋不住有心人**，
這一點不要誤會成安全機制。

### 加點時用訂單自己的 orderType 重算

顧客掃碼點的一定是內用（外帶與候位沒有桌上的 QR code 可以掃）。但如果店員
已經把這張單轉成外帶（SPEC 第十五節），加點就該用外帶價，
所以附加品項時是拿訂單文件上的 `orderType` 去 `repriceLines`，
而不是固定用內用價。

### 顧客加點到已確認的單，狀態維持 `open`

SPEC 第五節的範例寫「附加到現有訂單，狀態維持不變」，這裡照做。
效果是：第一張單停在 `pending_confirm` 等店員確認，之後店員按了確認變成 `open`，
客人再加點的品項不會讓整張單退回待確認，店員在訂單明細裡看得到新品項。

`pending_confirm` 擋的是「憑空冒出一千張假單」，而要加點得先有一張被店員
確認過的單，那時候人已經在店裡了。如果實際營運上店家希望每次加點都要確認，
改成把狀態設回 `pending_confirm` 只有一行，但那會讓已經送進廚房的單
又回到待確認清單，得先想清楚店員要怎麼分辨。

## 順帶定下的回傳格式

`createOrder` 回傳整張單目前的樣子（品項、數量、選項名稱與加價、各行小計、總額），
不是只回 `{ orderId, sessionId }`。顧客端因此不必再讀一次就能顯示「已點項目」，
而 `orders` 對顧客是唯讀不可讀的（Rules 裡 `get` 要 staff），本來也讀不到。

回傳的東西只有客人本來就看得到的：沒有 `createdBy`、沒有 uid、沒有內部狀態欄位。
作廢的行（`voidedAt` 不是 null）不回傳。

**顧客重新整理頁面後要看回已點項目，還需要一支讀取用的 function**，
這裡沒有做——做顧客網頁那一段時一起處理，才知道頁面真正需要哪些欄位。
