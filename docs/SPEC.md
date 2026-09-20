# 單店餐廳 POS 系統 — 技術規格書

2026-09-20 · @Someone

## 給 Claude Code 的開場指示

這份文件是一套**單店餐廳 POS 系統**的完整技術規格。請依照〈建議開發順序〉分階段實作，不要一次生成整個專案。

**專案一句話**：一間自營小餐廳的自用 POS。店員用 Android 平板點餐結帳並出單到熱感印表機，顧客可掃桌上 QR code 自助點餐，菜單與桌位由店家自行在 Web 後台編輯。

### 三個不可違反的原則

1. **客戶端永遠不送金額。** 任何來自顧客或店員裝置的下單請求，只能包含品項 ID 與數量。價格一律由 Cloud Function 從資料庫查出後計算。違反這條等於開放任意改價。
2. **顧客端不能直接寫入 Firestore。** 顧客只能讀公開菜單，下單必須經過 Cloud Function。`orders` 對顧客的寫入權限一律為 `false`。
3. **離線必須還能點餐出單。** 網路斷線時 POS 不得停擺。本地先寫入、恢復連線後同步。

### 環境前提

- 不上架 App Store 與 Google Play，Android 以側載 APK 發佈
- 不支援 iOS
- 錢櫃由人工開啟，**不需**實作開錢櫃指令
- 熱感印表機型號未知，列印模組必須先做介面偵測（見〈列印模組〉）
- 系統開發者與店家是不同人：本文件中「店家」指客戶，「開發者」指實作與維運者

## 一、專案範圍

### 使用者角色

| 角色 | 裝置 | 認證方式 | 能做什麼 |
| --- | --- | --- | --- |
| **老闆 owner** | Web 後台 / 平板 | Firebase Auth + custom claim `role: owner` | 全部權限：改菜單、改價、改桌位、看報表、管理員工 |
| **店員 staff** | Android 平板 | Firebase Auth + custom claim `role: staff` | 點餐、結帳、出單、改訂單狀態。**不能改價格與菜單** |
| **顧客 guest** | 自己的手機瀏覽器 | Firebase Anonymous Auth | 讀公開菜單、送出點餐請求、查自己的單 |

### 要做的功能

**現場營運（Android App）**

- 桌位總覽：顯示各桌狀態（空桌 / 用餐中 / 待結帳），可依區域切換
- 開桌、點餐、加點、退點、轉桌、併桌
- 結帳：現金 / 行動支付（僅記錄支付方式，不串金流）、折扣、找零計算
- 確認顧客自助點單（預設不直接進廚房，見〈Cloud Functions〉）
- 出單：廚房單、客人明細單
- 離線運作

**顧客點餐（Web，掃 QR）**

- 讀菜單、選規格、加入購物車、送出
- 查看自己這一桌已點的內容
- 不含線上付款

**店家後台（Web）**

- 菜單編輯：分類、品項、價格、規格選項群組、上下架
- 桌位編輯：區域、桌號、座位數、平面圖位置
- 發佈：把草稿菜單推送到顧客端
- 報表：日營收、品項銷售排行、時段分析
- 員工帳號管理

### 明確不做

以下項目**不要實作**，也不要為了它們預留複雜抽象層：

- iOS App
- 電子發票介接（財政部 Turnkey 或加值中心）
- 信用卡 / 行動支付金流串接
- 外送平台（Uber Eats、foodpanda）訂單整合
- 庫存管理、進貨、成本計算
- 會員系統、點數、優惠券
- 開錢櫃指令
- 多分店管理介面

資料模型會為多租戶預留結構（見〈資料模型〉），但**介面只服務單店**。

### 未來擴充的預留決策

上一節列出的「不做」，指的是**不要寫抽象層、介面或 TODO**，不是「不必為它們保護資料」。

判準只有一條：

> 這個決定之後要改，是「加欄位」還是「改歷史資料」？加欄位就現在不做；會遺失資料或要重寫歷史，現在就得做。

### 已經預留的（請勿為了簡化而移除）

下表的規則看起來囉嗦，但每一條都在替某個未來功能保命。**如果在實作時覺得某條多餘想簡化掉，先回來看這張表。**

| 規則（出處） | 實際在替誰鋪路 | 移除的後果 |
| --- | --- | --- |
| `tenants/{storeId}` 路徑前綴（第三節） | 多分店 | 有真實資料後再改，等於資料遷移地獄 |
| 品項軟刪除 `archived`（第三節） | 庫存、成本、銷售統計 | 歷史訂單關聯斷裂，報表算不出來 |
| 訂單保留 `itemId`（第三節） | 品項維度的所有分析 | 只剩名稱字串，無法統計 |
| 訂單價格快照（第三節） | 發票、報稅、對帳 | 調價後歷史帳目全變動 |
| `allow delete: if false`，作廢走 `voidedAt`（第四節） | **電子發票** | 稅法要求作廢須留痕，硬刪除無法補救 |
| `businessDate`（第三節） | 日結、報稅 | 跨午夜的單歸錯日期 |
| `payment.method` 欄位（第三節） | 金流串接 | 無 |
| `source: 'staff' \| 'guest'`（第三節） | 外送平台（多一個列舉值即可） | 無 |

也就是說，**發票與庫存的合規骨架已經在了**。日後要補，只是新增 `invoiceNumber` 欄位和一組 `inventory` collection，都是加法。

### 為什麼不預留抽象層

1. **猜到的介面幾乎必錯。** 外送平台是最好的例子：預留一個 `DeliveryAdapter`，等真的接 Uber Eats 才發現對方是 webhook push 不是 pull、品項規格是巢狀 modifier group、取消流程完全不同——整層要重寫，還不如當初沒有。
2. **電子發票在店家選定加值中心前無從預留。** 綠界、藍新與財政部 Turnkey 的介接方式差異很大，現在做的任何抽象都是瞎猜。
3. **有一半可能永遠用不到。** 月營業額 20 萬以下可申請查定課徵，不必開統一發票；小餐廳的庫存管理常常就是老闆看一眼冰箱。

所以：**不要生成空的 adapter、interface 或標著 TODO 的樁函式。** 沒有使用者、沒有測試，只會在後續 review 時持續消耗注意力。

### 一個現在就必須決定的例外：價格含稅與否

這是唯一「之後補不回來」的欄位設計。

台灣餐飲習慣標含稅價，稅額可由含稅價回推（÷ 1.05），數學上不遺失資訊。但如果店家屬於**免稅**業別，或未來想讓**內用與外帶適用不同稅別**，單一 `price` 欄位就不夠了。

因此 `menu_items` 增加一個欄位：

```
price: number          // 一律視為「含稅價」，整數
taxMode: 'taxable' | 'exempt' | 'zero'    // 預設 'taxable'
```

`orders` 的 `lines` 快照同樣要帶上 `taxMode`。**不要自行假設全部應稅**，這個值要由店家在後台設定。

實作階段若無法向店家確認，一律預設 `'taxable'` 並在後台留一個可切換的設定項即可，但欄位本身現在就要存在。

## 二、技術選型與架構

### 選型

| 層 | 選擇 | 理由 |
| --- | --- | --- |
| 後端 | **Firebase Blaze 方案** | Cloud Functions 需要 Blaze；Blaze 保留免費額度但超量不會斷線 |
| 資料庫 | Cloud Firestore | 原生即時同步與離線快取，正好對應 POS 需求 |
| 伺服器邏輯 | Cloud Functions 2nd gen（Node.js 20、TypeScript） | 只用於必須受信任的運算 |
| Android | Kotlin + Jetpack Compose，minSdk 26 | 單一 target 裝置，不需跨平台框架 |
| 顧客點餐頁 | React + Vite，部署於 Firebase Hosting | 掃 QR 即開，不需安裝 |
| 店家後台 | React + Vite，同一個 Hosting 專案不同路徑 | 改後台不必發新版 APK |
| 本地資料庫 | Room（Android） | 離線佇列與本地快取 |

**不要引入 Redux/MobX 等重型狀態管理。** Firestore 的即時監聽本身就是狀態來源，外加一層只會製造同步問題。Android 用 StateFlow，Web 用 React Context 足夠。

### 三條資料路徑

```
店員平板 ──直連 Firestore──> orders / tables      （有 staff claim，可讀寫）
           └─本地 Room 佇列 ──> 離線時暫存，回線後同步

顧客手機 ──讀取──> Hosting 上的靜態 menu.json    （0 次 Firestore 讀取）
           └─呼叫 Function ──> createOrder        （唯一寫入途徑）

店家後台 ──直連 Firestore──> menu_items / tables  （需 owner claim）
           └─呼叫 Function ──> publishMenu         （產生 menu.json）
```

### 為什麼顧客要走 Cloud Function

Security Rules 沒有迴圈語法，無法逐一驗證長度不定的品項陣列，而且單次規則求值的 `get()` 呼叫上限只有 10 次——一張五樣菜的單就會破功。所以**顧客下單的價格驗證只能放在 Function 裡**。

店員平板則相反：店員已通過認證且擁有 `staff` claim，直連 Firestore 可以拿到即時同步與離線快取，這兩件事走 Function 反而做不到。

### 後端邊界：不要建立傳統 API 站台

**不要建立 Express、NestJS、.NET 或任何自架 API 站台。** 本專案沒有傳統三層架構的中間層，那一層的職責已經拆散到三處：

| 傳統 API 站台裡的東西 | 在本專案跑在哪 |
| --- | --- |
| Controller / Routing | Cloud Functions 的 `onCall` |
| 認證中介層 | Firebase Auth + custom claims |
| 授權（`[Authorize]` 之類） | Security Rules + Function 內的 `assertRole` |
| 商業邏輯 Service | Cloud Functions |
| Repository / ORM | Firestore SDK |
| 即時推播（SignalR 等） | Firestore `onSnapshot` |
| 部署、擴縮、憑證、監控 | Google 代管 |

所謂「後端」就是 `functions/` 底下那十支 function 加上 `firestore.rules`。

### 邏輯該放哪

**必須在 Cloud Function（不可信任客戶端）**

- 任何涉及**金額**的計算：算價、折扣、稅額、找零驗證
- 任何涉及**權限**的判斷：角色設定、跨使用者操作
- 任何**跨使用者共享狀態**的變更：結帳、轉桌、併桌、發佈菜單
- 任何**產生機密值**的動作：`qrToken`、邀請碼

**可以留在客戶端**

- 純 UI 狀態（目前選到哪個分類、購物車暫存）
- 顯示層格式化（金額千分位、時間顯示）
- 樂觀更新（先更新畫面再送出，失敗回滾）
- 本地排序與篩選

判準：**這段邏輯被繞過會不會造成損失？** 會 → Function。只是畫面不好看 → 客戶端。

### 因為沒有資料庫 schema，驗證必須自己做

Firestore 沒有 migration、沒有外鍵、沒有 NOT NULL 約束。一個結構錯誤的 `set()` 會安靜地寫進去，不會報錯——這是 BaaS 相對於關聯式資料庫最大的退讓。

**對策：所有 Function 的輸入一律用 `zod` 驗證，集中定義於 `functions/src/schema.ts`。**

```ts
export const CreateOrderInput = z.object({
  storeId: z.string().min(1),
  tableToken: z.string().length(32),
  items: z.array(z.object({
    itemId: z.string().min(1),
    qty: z.number().int().min(1).max(20),
    options: z.array(z.object({
      groupId: z.string(),
      optionId: z.string(),
    })).max(10).default([]),
  })).min(1).max(50),
}).strict();      // .strict() 很重要：多餘欄位直接拒絕
```

`.strict()` 讓客戶端偷塞 `price`、`total` 之類的欄位會直接被擋下，而不是被默默忽略。

寫入 Firestore 前也要用同一組 schema 驗證輸出，確保文件結構一致。

### 這個架構的已知限制（接受，不要試圖繞過）

- **查詢能力弱**：沒有 JOIN、GROUP BY、模糊搜尋。報表一律靠預先彙總（`daily_reports`），不要在前端跑大範圍查詢
- **Transaction 有上限**：最多 500 筆寫入。結帳搬移訂單用 batch write 確保原子性即可
- **廠商綁定**：這套資料層搬不走，換雲等於重寫。這是刻意接受的取捨

若日後出現以下需求，才需要重新評估是否加一個真正的 API 站台：複雜的自由查詢報表、大量第三方系統介接、或資料必須落在自有機房。

### 專案結構

```
/
├── android/                  # Kotlin + Compose
│   ├── app/
│   │   ├── data/             # Room、Firestore repository
│   │   ├── printer/          # ESC/POS 模組（見第七節）
│   │   ├── sync/             # 離線佇列
│   │   └── ui/
├── functions/                # Cloud Functions（TypeScript）
│   ├── src/
│   │   ├── orders/
│   │   ├── menu/
│   │   └── admin/
│   └── test/                 # emulator 測試
├── web/
│   ├── guest/                # 顧客點餐頁
│   └── admin/                # 店家後台
├── firestore.rules
├── firestore.indexes.json
├── firebase.json
└── .github/workflows/deploy.yml
```

## 三、Firestore 資料模型

### 多租戶前綴

所有資料放在 `tenants/{storeId}/` 底下。現在只有一間店，`storeId` 固定；但等有了真實營運資料再改成多租戶結構會非常痛苦，而現在做的成本是零。

`storeId` 寫在 custom claim 裡，Rules 比對 claim 與路徑即可完成隔離。

### 寫入模型（店家編輯用）

```
tenants/{storeId}/categories/{categoryId}
  { name: string, sort: number, archived: boolean }

tenants/{storeId}/menu_items/{itemId}
  { name: string,
    price: number,           // 內用含稅價，整數元
    takeoutPrice?: number,   // 外帶價，未填則同 price
    taxMode: 'taxable' | 'exempt' | 'zero',   // 預設 'taxable'，見第一節
    categoryId: string,
    description?: string,
    imageUrl?: string,
    optionGroupIds: string[],
    available: boolean,      // 今日售完可快速切換
    archived: boolean,       // 軟刪除
    sort: number }

tenants/{storeId}/option_groups/{groupId}
  { name: string,            // 例：辣度、加料
    type: 'single' | 'multi',
    min: number, max: number,
    options: [{ id, name, priceDelta }],   // priceDelta 可為 0 或負數
    archived: boolean }

tenants/{storeId}/areas/{areaId}
  { name: string, sort: number }          // 一樓 / 二樓 / 戶外

tenants/{storeId}/tables/{tableId}
  { areaId: string,
    label: string,           // 顯示用桌號，任意字串，見下方〈桌號命名〉
    sort: number,            // 由 x,y 自動推導，見〈平面圖與排序〉
    seats: number,
    x: number, y: number,    // 相對座標 0–1，不是像素
    shape: 'square' | 'round' | 'rect',
    qrToken: string,         // 由 Function 產生，客戶端不可指定
    archived: boolean }
```

### 桌號命名

`label` 是**任意字串**，店家想叫什麼都可以：`A3`、`包廂一`、`窗邊`、`蘇東坡`、`吧台3`。系統不得限制格式，也不得假設它是數字或英數。

因為真正的鍵是 `tableId`（隨機 ID），`label` 純粹是顯示用。改桌名不影響任何既有訂單——訂單存的是 `tableLabels` 快照。

四條實作要求：

1. **排序用 `sort` 欄位，絕不用 `label` 字串排序。** 中文字串排序結果對店家毫無意義（會變成按 Unicode 碼位），而且 `A10` 會排在 `A2` 前面。`sort` 由後台拖拉決定。
2. **長度上限 10 個字元**，超過在 UI 截斷並顯示完整值於 tooltip。理由是出單紙寬有限（58mm 約 16 個半形字），桌位卡片也會被撐破。後台輸入框要即時提示剩餘字數。
3. **允許重複命名，但要警告。** 兩桌都叫「窗邊」是店家的自由，但新增時要提示「已有同名桌位」，避免誤建。
4. **不支援 emoji。** 熱感印表機是單色點陣，emoji 印出來是黑塊。後台輸入時過濾掉，或明確提示不支援。

中文與任意符號在出單上沒有問題——本專案採圖片化列印（見第七節），字型完全可控。

店員端要能用桌名搜尋（`包廂` 就能找到所有包廂），因為桌位一多時視覺掃描會很慢。

### 平面圖與排序

**店家只維護一張平面圖，`sort` 由系統自動推導。** 不要讓店家同時維護座標和排序，那是重複勞動，而且兩者遲早會不一致。

存檔時依閱讀順序（由上而下、由左而右）計算：

```ts
tables.sort((a, b) => (a.y - b.y) || (a.x - b.x))
      .forEach((t, i) => { t.sort = i; });
```

`sort` 欄位仍保留在資料中，因為列表場景（搜尋結果、報表、小螢幕）需要一個確定的順序，不能每次現算。自動推導出的順序也正好符合店員在店內走動的動線。

**座標一律用 0–1 的相對值，不可用絕對像素。** 後台在筆電編輯、平板在店裡顯示，螢幕尺寸不同；存相對座標、渲染時乘上容器大小，兩邊才會一致。

### 平面圖編輯器（Web 後台）

- 拖曳定位，吸附到網格，避免歪斜
- 點選一桌 → 右側面板編輯桌名、座位數、形狀
- 雙擊直接改名（快速路徑）
- 拖放新增、選取後 Delete 刪除
- 可多選批次移動

### 平板也要能拖曳（第八節「編輯一律在 Web」的例外）

用手指拖桌子比滑鼠自然，而且店家調整座位時人就在現場，站在店裡拖比回辦公室對著螢幕猜準確得多。

平板提供一個「編輯排列」模式，**僅老闆可進入**，且只能拖曳位置：

| 動作 | 平板 | Web 後台 |
| --- | --- | --- |
| 點桌子開單（店員日常） | ✅ | — |
| 拖曳調整位置 | ✅ 僅老闆 | ✅ |
| 新增／刪除桌子、改桌名 | ❌ | ✅ |
| 區域管理、列印 QR | ❌ | ✅ |

限制只能拖曳，是為了避免店員誤觸把桌子刪掉。

### 改動桌位不得影響進行中的訂單

訂單存的是 `tableIds` 與 `tableLabels` 快照，所以移動或改名在技術上安全。但 UI 必須擋住「刪除仍有 active session 的桌位」，並提示「本桌仍有未結帳訂單」。

### 發佈模型（顧客讀取用）

```
tenants/{storeId}/published/menu        // 單一文件
  { version: number,                     // 用 Date.now()
    categories: [...],
    items: [...],
    optionGroups: [...] }
```

同時由 `publishMenu` 寫出一份 `menu.json` 到 Firebase Hosting，顧客端優先讀靜態檔，Firestore 文件作為備援。

### 營運資料

```
tenants/{storeId}/sessions/{sessionId}
  { tableId: string,
    status: 'active' | 'closed',
    orderId: string,
    openedAt: Timestamp,
    closedAt: Timestamp | null,
    readableUntil: Timestamp | null }   // closedAt + 3h，TTL policy 自動清除

tenants/{storeId}/receipts/{sessionId}     // 結帳時產生的唯讀快照
  { lookupCode: string,     // 4 碼數字，供客人查詢
    tableLabel: string,
    lines: [...],           // 與 order 相同結構的快照
    subtotal, discount, total, taxSummary,
    paidAt: Timestamp,
    expiresAt: Timestamp }  // paidAt + 3h

tenants/{storeId}/orders/{orderId}
  { orderType: 'dine_in' | 'takeout' | 'waitlist',
    pickupCode: string | null,   // 4 碼，外帶與候位單共用發號
    tableIds: string[],      // 陣列：併桌時多桌共用；外帶為空
    tableLabels: string[],   // 顯示用快照
    sessionIds: string[],    // 可綁多個顧客場次
    status: 'pending_confirm' | 'open' | 'closed' | 'voided',
    source: 'staff' | 'guest',
    lines: [{
      lineId: string,
      itemId: string,        // 保留供統計關聯
      name: string,          // 快照
      unitPriceDineIn: number,    // 快照
      unitPriceTakeout: number,   // 快照，轉換型態時直接切換
      taxMode: 'taxable' | 'exempt' | 'zero',   // 快照
      qty: number,
      options: [{ groupId, optionId, name, priceDelta }],  // 快照
      subtotal: number,
      printedAt: Timestamp | null,   // 已送廚房的不可再改
      voidedAt: Timestamp | null,
      voidReason?: string
    }],
    subtotal: number, serviceCharge: number, discount: number, total: number,
    typeChangedAt?: Timestamp, typeChangedBy?: string,   // 型態轉換留痕
    splitFrom?: string,      // 由哪張單拆出
    mergedFrom?: string[],   // 由哪幾張單合併而來
    payment?: { method: 'cash'|'mobile'|'card', received?: number, change?: number },
    createdAt, updatedAt, closedAt,
    createdBy: string,       // uid
    businessDate: string }   // 'YYYY-MM-DD'，見下方

tenants/{storeId}/orders_archive/{orderId}     // 結帳後搬移
tenants/{storeId}/daily_reports/{YYYY-MM-DD}   // 由排程 Function 產生
tenants/{storeId}/staff/{uid}
  { displayName, role: 'owner'|'staff', active: boolean }
```

**同一張桌可以同時存在一個已結帳但仍可讀的 session，和一個進行中的 active session。** 不要在 `tableId` 上加唯一約束——前一組客人在門口查帳單時，新客人可能已經坐下點餐了。

### 三個必須遵守的資料規則

**1. 品項絕不硬刪除。** 一律 `archived: true`。硬刪會讓歷史訂單的關聯斷裂，報表直接算不出來。

**2. 訂單必須存價格快照。** 明天調價，昨天的帳不能跟著變。`itemId` 保留做統計，但 `name`、`unitPrice`、選項的 `priceDelta` 都要在下單當下複製進 `lines`。

**3. 用 `businessDate` 而不是 `createdAt` 做日結。** 營業到凌晨兩點的單屬於前一天。`businessDate` 由 Function 依店家設定的換日時間（預設凌晨 5 點）計算後寫入，報表一律用這個欄位分組。

### 索引

`firestore.indexes.json` 至少需要：

- `orders`: `status` ASC + `updatedAt` DESC（平板監聽未結帳訂單）
- `orders_archive`: `businessDate` ASC + `createdAt` ASC（報表）
- `menu_items`: `categoryId` ASC + `sort` ASC（後台列表）

## 四、Security Rules

### 威脅模型

Firebase 的 `apiKey` 與 `projectId` 放在前端是設計如此，不是洩漏。但這代表任何人都能複製你的 config，在 Node.js 裡直接 import Firestore SDK 發送任意請求——**完全繞過你的網頁與所有前端驗證**。

純直連架構下 Rules 是唯一防線，沒有第二層。實作時請假設攻擊者會嘗試：改價、把自己的單標記成已付、撈走整個 `orders`、迴圈建立假訂單灌爆出單機與帳單。

### 角色 claim

登入後由 `setStaffRole` Function 寫入 custom claims：

```js
{ storeId: 'store_abc', role: 'owner' | 'staff' }
```

顧客走 Anonymous Auth，沒有 claim。

### 規則

```js
rules_version = '2';
service cloud.firestore {
  match /databases/{db}/documents {

    function signedIn()  { return request.auth != null; }
    function myStore(s)  { return signedIn() && request.auth.token.storeId == s; }
    function isStaff(s)  { return myStore(s) && request.auth.token.role in ['staff','owner']; }
    function isOwner(s)  { return myStore(s) && request.auth.token.role == 'owner'; }

    match /tenants/{storeId} {

      // 公開菜單：任何人可讀，只有 Function 能寫
      match /published/{docId} {
        allow read: if true;
        allow write: if false;
      }

      // 菜單編輯：只有老闆
      match /menu_items/{id}   { allow read: if isStaff(storeId); allow write: if isOwner(storeId); }
      match /categories/{id}   { allow read: if isStaff(storeId); allow write: if isOwner(storeId); }
      match /option_groups/{id}{ allow read: if isStaff(storeId); allow write: if isOwner(storeId); }
      match /areas/{id}        { allow read: if isStaff(storeId); allow write: if isOwner(storeId); }

      // 桌位：qrToken 是公開的（印在桌上），不可當憑證用
      match /tables/{id} {
        allow read:   if isStaff(storeId);
        allow update: if isOwner(storeId);
        allow create, delete: if false;    // 一律走 createTable Function
      }

      // 場次：sessionId 本身就是能力憑證（32 hex，存於客人 localStorage）
      match /sessions/{sessionId} {
        allow get:    if true;             // 猜不到 id 就讀不到
        allow list:   if isStaff(storeId); // 顧客禁止列舉
        allow write:  if false;            // 只有 Function 能寫
      }

      // 結帳快照：同上，且過期後不可讀
      match /receipts/{sessionId} {
        allow get:    if isStaff(storeId)
                      || resource.data.expiresAt > request.time;
        allow list:   if isStaff(storeId);
        allow write:  if false;
      }

      // 訂單
      match /orders/{id} {
        allow get:    if isStaff(storeId);  // 顧客一律透過 sessions/receipts 讀
        allow list:   if isStaff(storeId);
        allow create: if false;             // 一律走 createOrder
        allow update: if isStaff(storeId)
                      && request.resource.data.total == resource.data.total;  // 店員不可直接改金額
        allow delete: if false;             // 只能作廢，不能刪
      }

      match /orders_archive/{id}  { allow read: if isStaff(storeId); allow write: if false; }
      match /daily_reports/{id}   { allow read: if isOwner(storeId); allow write: if false; }
      match /staff/{uid}          { allow read: if isStaff(storeId); allow write: if false; }
    }

    match /{document=**} { allow read, write: if false; }   // 預設全關
  }
}
```

### 五個容易寫錯的地方

1. **`allow read` 等於同時開放 `get` 和 `list`。** 只寫 `allow read` 就等於允許把整包 collection 撈走。顧客只該有 `get`，務必分開寫。
2. **結尾一定要有 `match /{document=**} { allow read, write: if false; }`。** 沒有這行，未來新增的 collection 預設是沒有規則保護的。
3. **店員的 `update` 要擋金額。** 店員可以改訂單狀態，但不該能直接改 `total`。真正要調整金額（折扣、退點）走 Function。
4. **已列印的品項不可修改。** `printedAt != null` 的 line 只能作廢不能編輯，否則廚房做好的菜會從帳上消失。這條在 Rules 裡難完整表達，主要靠 Function 把關，Rules 做粗略防線。
5. **不要在 Rules 裡塞商業邏輯。** Rules 沒有迴圈、`get()` 上限 10 次。複雜驗證一律放 Function。

### App Check

Firestore 與 Cloud Functions 都要設成 **enforce** 模式：

- Android：Play Integrity
- Web：reCAPTCHA Enterprise

這擋不住極度執著的攻擊者（他可以開真瀏覽器手動點），但能把「隨手寫腳本刷單」的成本抬高好幾個數量級。

### 測試要求

Rules 是立刻生效的——一條寫錯，中午十二點全店同時開始跳 permission denied，店家只能拿紙筆抄單。

因此 `functions/test/` 必須包含 `@firebase/rules-unit-testing` 的測試，至少涵蓋：

- 顧客嘗試 `list` orders → 拒絕
- 顧客嘗試寫 orders → 拒絕
- 店員嘗試改 `menu_items` 價格 → 拒絕
- 店員嘗試改 order 的 `total` → 拒絕
- A 店的 uid 讀 B 店資料 → 拒絕

CI 必須跑 `firebase emulators:exec "npm test"`，**測試不過就不准部署**。

## 五、Cloud Functions

全部使用 2nd gen，`region: 'asia-east1'`（台灣），`enforceAppCheck: true`。

### 清單

| Function | 觸發 | 呼叫者 | 用途 |
| --- | --- | --- | --- |
| `createOrder` | callable | 顧客 | 顧客自助下單。伺服器算價，並在無 active session 時自動開桌 |
| `addOrderLines` | callable | 店員 | 店員點餐 / 加點，伺服器算價 |
| `voidOrderLine` | callable | 店員 | 退點（已列印的只能作廢不能刪） |
| `applyDiscount` | callable | 老闆 | 折扣，重算總額 |
| `splitOrder` | callable | 店員 | 把指定的 lines 拆到新單（結帳前） |
| `mergeOrders` | callable | 店員 | 合併多張單，`tableIds` 取聯集 |
| `closeOrder` | callable | 店員 | 結帳、寫 `businessDate`、產生 `receipts` 與 `lookupCode`、搬進 archive、關閉 session |
| `confirmGuestOrder` | callable | 店員 | 把 `pending_confirm` 轉為 `open` |
| `lookupReceipt` | callable | 顧客 | 用 `tableToken` + 4 碼 `lookupCode` 查帳單，需限流 |
| `openTable` | callable | 店員 | 手動開桌，僅用於口頭點餐、外帶單、併桌前置 |
| `publishMenu` | callable | 老闆 | 產生 `published/menu` 與 `menu.json` |
| `createTable` | callable | 老闆 | 建桌並產生 `qrToken` |
| `setStaffRole` | callable | 老闆 | 設定員工 custom claim |
| `releaseTables` | scheduled | — | 每 15 分鐘釋放結帳滿 3 小時的桌位 |
| `rollDailyReport` | scheduled | — | 每日換日後產生報表 |

### createOrder（最關鍵的一支）

```ts
export const createOrder = onCall(
  { region: 'asia-east1', enforceAppCheck: true },
  async (req) => {
    if (!req.auth) throw new HttpsError('unauthenticated', '請重新整理頁面');

    // 0. zod 驗證，.strict() 擋掉客戶端偷塞的 price / total
    const { storeId, tableToken, items } = CreateOrderInput.parse(req.data);

    // 1. 驗桌號 token（不是 ?table=5）
    const table = await findTableByToken(storeId, tableToken);
    if (!table) throw new HttpsError('permission-denied', '無效的桌號');

    // 2. 限流：每 uid 每分鐘上限，防灌單
    await rateLimit(req.auth.uid, { max: 5, windowSec: 60 });

    const menu = await db.doc(`tenants/${storeId}/published/menu`).get();

    return db.runTransaction(async (tx) => {
      // 3. 自動開桌：無 active session 就建一個（掃碼不開桌，送出才開桌）
      let session = await findActiveSession(tx, storeId, table.id);
      let order;

      if (!session) {
        session = newSession(storeId, table.id);      // status: 'active'
        order = null;
      } else {
        order = await tx.get(db.doc(`tenants/${storeId}/orders/${session.orderId}`));
        if (order.data().status === 'closed') {
          throw new HttpsError('failed-precondition', '本桌已結帳，如需加點請洽服務人員');
        }
      }

      // 4. 價格「只」從資料庫來，計算交給純函式（見第十二節）
      const { lines, subtotal } = calcOrderLines(menu.data(), items);

      if (order) {
        // 附加到現有訂單，狀態維持不變
        tx.update(order.ref, {
          lines: [...order.data().lines, ...lines],
          subtotal: order.data().subtotal + subtotal,
          total: order.data().total + subtotal,
          updatedAt: FieldValue.serverTimestamp(),
        });
        return { orderId: order.id, sessionId: session.id };
      }

      // 5. 新單狀態一定是 pending_confirm，不直接進廚房
      const orderRef = db.collection(`tenants/${storeId}/orders`).doc();
      tx.set(orderRef, {
        tableIds: [table.id],
        tableLabels: [table.label],
        sessionIds: [session.id],
        status: 'pending_confirm',
        source: 'guest',
        lines, subtotal, discount: 0, total: subtotal,
        createdAt: FieldValue.serverTimestamp(),
        updatedAt: FieldValue.serverTimestamp(),
      });
      tx.set(sessionRef(storeId, session.id), { ...session, orderId: orderRef.id });
      return { orderId: orderRef.id, sessionId: session.id };
    });
  }
);
```

整段放在 transaction 裡，是為了避免兩位客人同時按送出時開出兩個 session。

**`req.data` 裡絕對不可以出現 `price`、`unitPrice`、`subtotal`、`total`。** 如果客戶端送了，直接忽略，不要用來比對——比對邏輯本身就是多餘的攻擊面。

### 為什麼顧客單要 pending\_confirm

就算限流被繞過、真的被灌進 1000 張假單，浪費的是店員滑掉的三秒，而不是 1000 張紙、1000 份食材和一個崩潰的廚房。

這是整套設計裡成本最低、效果最好的一道防線。**不要為了「流暢體驗」把它拿掉。**

### publishMenu

```ts
export const publishMenu = onCall(async (req) => {
  assertRole(req, 'owner');
  const { storeId } = req.data;

  const [items, cats, groups] = await Promise.all([...]);
  validateMenu(items, cats, groups);   // 價格非負整數、名稱非空、分類存在、選項群組 min<=max

  const payload = { version: Date.now(), categories: cats, items, optionGroups: groups };
  await db.doc(`tenants/${storeId}/published/menu`).set(payload);
  await uploadToHosting(`menu-${storeId}.json`, payload);   // 讓顧客端 0 次 Firestore 讀取
});
```

**不要用 `onDocumentWritten` 自動同步。** 兩個原因：店家批次改 20 個品項會觸發 20 次重建；更糟的是老闆中午改菜單改到一半，客人就看到半成品——品項改名改了一半、新品項還沒填價格。

後台要顯示「有 N 項未發佈的變更」，讓老闆按下按鈕才生效。

### createTable

`qrToken` 必須由伺服器產生，客戶端不可指定：

```ts
qrToken: crypto.randomBytes(16).toString('hex')
```

用 `?table=5` 這種可預測的參數，等於讓任何人在家就能對任意桌下單。

### 限流實作

用 Firestore 文件做簡易計數即可，不必引入 Redis：

```
tenants/{storeId}/rate_limits/{uid}
  { count: number, windowStart: Timestamp }
```

在 transaction 裡遞增，超過上限拋 `resource-exhausted`。另外在 `closeOrder` 時順手刪掉該桌顧客的限流文件。

## 六、Android App

### 畫面

| 畫面 | 內容 |
| --- | --- |
| 登入 | Email/密碼，記住登入狀態（平板不該每天登入） |
| 桌位總覽 | 依區域分頁，桌位依 `x,y` 排列，顏色標示狀態，右上角顯示待確認的顧客單數量 |
| 點餐 | 左側分類、中間品項格狀、右側購物車。規格選項用 bottom sheet |
| 訂單明細 | 已點項目、加點、退點、轉桌、併桌 |
| 結帳 | 金額、折扣、支付方式、收款金額與找零計算 |
| 待確認 | 顧客自助單列表，逐單確認或退回 |
| 設定 | 印表機連線設定、測試列印、同步狀態、登出 |

### 桌位狀態機

桌位總覽的顏色狀態，與 session 的對應：

| 顯示狀態 | 條件 | 店員可做的動作 |
| --- | --- | --- |
| **空桌**（淺色） | 無 active session，且無未過期的已結帳 session | 手動開桌（僅口頭點餐／外帶用） |
| **用餐中**（主色） | 有 active session | 點餐、加點、結帳、轉桌、併桌、分單 |
| **待確認**（強調色＋角標） | active session 且訂單為 `pending_confirm` | 確認或退回顧客自助單 |
| **已結帳**（淡色／半透明） | session 已 closed，但未滿 3 小時 | 無需動作。點擊可查看該桌今日所有單 |

### 狀態轉換規則

- **空桌 → 用餐中**：由 `createOrder` 自動觸發（客人送出訂單時），或店員手動 `openTable`。**店員日常不需按開桌。**
- **用餐中 → 已結帳**：`closeOrder`
- **已結帳 → 空桌**：滿 3 小時由 `releaseTables` 排程自動釋放，或該桌出現新的 active session 時立即釋放
- **已結帳 → 用餐中**：新客人下單，直接開新 session 與新訂單（已結訂單不可變更）

### 必須允許的並存狀態

同一張桌可以同時有：一個已結帳但仍在可讀期的 session（前一組客人正在門口看帳單），和一個 active session（新客人已經坐下）。

**不要在資料層或 UI 層假設「一桌一 session」。** 桌位顯示以 active session 為準，已結帳的 session 只影響 `receipts` 的可讀性。

### 同桌多單

結帳後加點會產生新單。桌位卡片要顯示「本桌今日 N 張單」，點進去看得到全部（含已結帳的）。加點按鈕直接開新單並掛上同一 `tableId`，店員的操作步驟不變。

### 離線策略（local-first）

這是整個專案工時最大的一塊，請優先把架構打對。

**原則：所有現場操作先寫本地 Room，UI 立刻反應，再非同步同步到 Firestore。**

```
UI 動作 → Room（立即） → UI 更新
                ↓
          OutboxEntry → WorkManager → Cloud Function / Firestore
                ↓
          成功 → 標記 synced；失敗 → 指數退避重試
```

實作要點：

- **開啟 Firestore 離線持久化**：`setPersistenceEnabled(true)`，並設定 `cacheSizeBytes` 上限
- **orderId 由客戶端產生** UUID，不要等伺服器回傳。這樣離線建立的單有穩定識別，回線後用 `set()` 而非 `add()`
- **所有寫入操作設計成冪等**：重試不可以造成重複扣款或重複出單
- **列印不依賴網路**：印表機在同一區網，斷外網時仍可出單
- UI 常駐顯示同步狀態（已同步 / 待同步 N 筆 / 離線中）

**離線時的限制**（要明確告知店員，UI 上直接禁用）：

- 顧客自助點餐不可用（需要 Function）
- 折扣不可用（需要 Function 重算）
- 報表不可用

離線時店員仍可正常點餐、出單、現金結帳，這是最低可營運集合。

### 監聽器範圍（直接影響帳單）

`onSnapshot` 會對初始快照的每份文件計讀取，之後每次文件變動也計。而 POS 平板是**整天不關機的**——一般 App 使用者五分鐘就離開，你的平板會掛 14 小時。

強制規則：

1. **訂單監聽必須加範圍條件**，絕不監聽整個 collection：

   ```kotlin
   .whereIn("status", listOf("pending_confirm", "open"))
   ```

   結帳後的單搬去 `orders_archive`，讓 `orders` 永遠只有數十筆。
2. **每個 listener 都要有對應的 remove。** Compose 用 `DisposableEffect`，離開畫面就取消。殘留的監聽器在 14 小時裡可以輕鬆吃掉幾萬次讀取。
3. **菜單不用 listener。** 啟動時讀一次 `published/menu`（單一文件 = 1 次讀），存進 Room，比對 `version` 決定是否更新。
4. 報表畫面用 `get()` 不用 `onSnapshot`。

### 平板設定（交付時的檢查清單）

- 螢幕永不休眠、亮度鎖定
- 關閉系統自動更新（避免尖峰時段跳出重開機）
- 開啟螢幕固定 / Kiosk 模式，防止店員誤觸離開 App
- 固定 Wi-Fi，與印表機同一網段
- 設定靜態 IP 或 DHCP 保留（印表機 IP 不可變動）

## 七、熱感印表機列印模組

### 前提：型號未知

開發者尚未確認手上印表機的型號與介面。**第一步是請店家做印表機自我測試**：按住 FEED 鍵再開機，會印出一張含型號、韌體版本、介面設定（若有 LAN 會印出 IP）的測試頁。

在取得這張資訊前，列印模組請設計成可抽換的介面：

```kotlin
interface PrinterTransport {
    suspend fun connect(): Result<Unit>
    suspend fun write(bytes: ByteArray): Result<Unit>
    suspend fun disconnect()
}

class NetworkTransport(val ip: String, val port: Int = 9100) : PrinterTransport
class BluetoothTransport(val macAddress: String) : PrinterTransport
class UsbTransport(val device: UsbDevice) : PrinterTransport
```

### 沒有真機時的開發方式

**開發初期不會有實體印表機。** 因此必須先實作一個假的 transport，讓單據排版可以完整開發與測試：

```kotlin
class FakePrinterTransport(private val ctx: Context) : PrinterTransport {
    override suspend fun connect() = Result.success(Unit)
    override suspend fun write(bytes: ByteArray): Result<Unit> {
        // 把要送出的內容還原成 PNG，存到 App 內的預覽畫面
        // 設定頁提供「檢視最近 10 張列印預覽」
        return Result.success(Unit)
    }
    override suspend fun disconnect() {}
}
```

這樣「訂單 → 排版 → bitmap」整條可以先做完做對，等拿到真機只剩「bitmap → 位元組 → 送出」那一小段要驗。

設定頁要有一個 transport 切換器（Fake / Network / Bluetooth / USB），方便現場除錯。

**取得真機資訊的方法**：按住印表機 FEED 鍵再開機，會印出自我測試頁，含型號、韌體版本與介面設定（有 LAN 則印出 IP）。拿到這張之後再決定實作哪個 transport。

**優先實作 `NetworkTransport`**（TCP socket 打 9100 port），這是最穩定也最簡單的路徑。其餘兩種等確認硬體後再補。

### 中文編碼：一律用圖片化列印

ESC/POS 的中文字碼頁在不同廠牌之間極度混亂（Big5、GB18030、UTF-8 各行其是），直接送文字幾乎必定遇到亂碼。

**解法：用 Android Canvas 把整張單子畫成點陣圖，再用 `GS v 0` 指令送出。**

```kotlin
fun renderReceipt(order: Order, widthPx: Int = 576): Bitmap {
    // 58mm 紙 = 384px，80mm 紙 = 576px
    val bitmap = Bitmap.createBitmap(widthPx, calcHeight(order), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    // 用 Canvas 排版：店名、桌號、品項、金額、時間
    return bitmap
}

fun bitmapToEscPos(bmp: Bitmap): ByteArray {
    // 1. 轉單色（Floyd–Steinberg 或簡單閾值）
    // 2. 打包成 GS v 0 raster 格式
}
```

好處不只解決編碼：字型完全可控、可排版對齊、可印 logo 與 QR code、可自由調整字級。速度只慢一點點，單店規模完全無感。

### 單據類型

| 單據 | 內容 | 觸發 |
| --- | --- | --- |
| 廚房單 | 桌號、品項、數量、規格、備註。**不印金額** | 點餐確認後 |
| 加點單 | 只印新增的項目，標明「加點」 | 加點確認後 |
| 客人明細單 | 完整品項、單價、小計、折扣、總計、時間 | 結帳時 |
| 作廢單 | 標明「取消」與被取消的項目 | 退點已列印的項目時 |

### 列印佇列與失敗處理

列印**必須**走佇列，不可以在 UI 執行緒直接送：

```
PrintJob(id, type, payload, retryCount, status)
  → PrintQueue（Room 持久化）
  → 背景 worker 逐筆送出
```

失敗處理：

- 連線失敗 → 指數退避重試 3 次
- 仍失敗 → UI 顯示明顯警示（不是 toast，要是持續可見的 banner），並提供「重印」按鈕
- **缺紙、卡紙必須被偵測**：讀取印表機狀態回應（`DLE EOT` 指令），不要假設送出就是印出來了
- 佇列要持久化：App 被殺掉重開後，未完成的列印工作仍在

### 絕對不能發生的事

**重複出單。** 每個 `PrintJob` 有唯一 id，送出成功後標記完成。重試邏輯必須檢查狀態，否則廚房會做出兩份一樣的菜。

這比列印失敗嚴重得多——失敗店員看得到，重複出單要到出菜時才發現。

## 八、店家後台（Web）

### 為什麼不放進 App

兩個理由，都很實際：

1. **在平板上編輯 80 個品項是酷刑。** 老闆需要鍵盤、大螢幕、複製貼上。
2. **後台改版不需要發新 APK。** 側載更新本來就麻煩，能不發版就不發版。

### 功能

**菜單編輯**

- 分類的新增、排序（拖拉）、封存
- 品項的新增、編輯、排序、封存；「今日售完」快速切換（改 `available`，這個不需發佈即生效）
- 規格選項群組的管理，可被多個品項共用
- 批次調價（例如全品項 +5 元）
- **封存不是刪除**：UI 文案要寫「下架」而不是「刪除」，並提供「顯示已下架品項」的篩選

品項編輯表單中，**價格區塊要同時放出「內用價」與「外帶價」兩個欄位**，外帶價留空即表示同內用價（顯示 placeholder「同內用價」）。

這個欄位現在就要出現在介面上，不要等有需求才加。否則店家想對外帶差別定價時，會自己在菜單上另建一個「外帶牛肉麵」品項——那會讓品項統計、庫存關聯與報表全部亂掉。

服務費設定（`settings/pricing`）同樣放在後台，預設 0。

**桌位編輯**

- 區域管理
- 平面圖拖拉排列桌位（canvas 或 CSS grid 皆可，不需要做到精美）
- 每桌可列印專屬 QR code（`qrToken` 由 Function 產生）
- 換桌、加桌店家可自行處理

**發佈**

- 頂部常駐顯示「有 N 項未發佈的變更」
- 按下發佈前顯示 diff 摘要（新增 3 項、改價 2 項、下架 1 項）
- 發佈後記錄 `version` 與時間，可查看發佈歷史

**報表**

- 日營收、時段分布、品項銷售排行
- 資料來源是 `daily_reports`（預先彙總），不要在前端跑 `orders_archive` 的全表查詢——那會是讀取量爆炸的主因

**員工管理**

- 新增員工帳號、設定 `role`、停用

### 權限

現實中老闆常會把平板丟給工讀生，所以權限分層不是理論需求：

- 後台整站需要 `owner` claim
- 店員的帳號登入後台應直接導向「無權限」頁
- 報表只有 `owner` 能看

## 九、成本與額度控管

### 方案與額度

必須使用 **Blaze**——Cloud Functions 需要它。但 Blaze 保留 Spark 的免費額度，只是超量後改為計費而非斷線。

Firestore 免費額度：**每日 5 萬次讀、2 萬次寫、2 萬次刪除**，1 GiB 儲存，每月 10 GiB 流量。Cloud Functions 每月 200 萬次呼叫。Hosting 10 GB 儲存與每月 10 GB 流量。

**為什麼不能用 Spark**：Spark 超額後會直接停止服務，直到太平洋時間午夜重置——換算台灣時間是**下午三點（夏令）或四點（冬令）**。中午尖峰爆額度的話，POS 會癱瘓到下午。這是最糟的重置時機。

### 單店預估用量（一天 200 單）

| 項目 | 估算 |
| --- | --- |
| 訂單寫入 | 200 × 20 ≈ 4,000 寫 |
| 平板即時監聽（3 台） | ≈ 12,000 讀 |
| 顧客讀菜單 | **0 讀**（走 Hosting 靜態 JSON） |
| Function 呼叫 | < 3,000 / 日 |

遠低於免費額度。**正常營運下月成本趨近於 NT$0。**

### 讀取優化守則（違反就會爆）

1. **菜單走 Hosting 靜態 JSON。** 若改用 Firestore 直讀且每品項一份文件，80 品項 × 200 位顧客 = 16,000 次讀，再加上重整就逼近上限。
2. **已結帳訂單搬去 `orders_archive`。** 否則 `orders` 逐日累積，每次平板重連的初始快照都越來越貴。
3. **報表讀預先彙總的 `daily_reports`**，不跑全表查詢。
4. **所有 listener 都要 unsubscribe。**

### 預算熔斷（必做，不是選項）

Firebase 沒有內建消費上限。一個無限迴圈的讀取 bug 確實可能在一天內產生四位數美金帳單。

實作：

```
GCP Budget（設 US$1 / 5 / 20 三段警示）
   → Pub/Sub topic
   → Cloud Function：收到超過 US$20 就呼叫 Cloud Billing API 解除專案帳單綁定
```

聽起來很激烈，但總比早上起來看到五位數美金帳單好。US$1 與 US$5 只發信通知，讓你在燒起來前就收到訊號。

### Billing 歸屬（重要）

**GCP 專案與 billing account 開在店家名下，開發者只取得 IAM 權限。**

兩個理由：

1. 萬一被惡意刷爆，或哪天與這間店結束合作，開發者不會想收到一張不屬於自己餐廳的帳單。
2. 顧客的訂單與聯絡資訊，法律上的持有者是店家而非開發者。

這件事在開專案第一天處理最簡單，事後搬移非常麻煩。

## 十、CI/CD 與部署

### 認證：用 WIF，不要放金鑰

`firebase login:ci` 產生的 token 已被 Google 標示為淘汰方向；service account 的 JSON 金鑰放在 GitHub Secrets 是永久有效的憑證，外洩等於專案被接管。

改用 **Workload Identity Federation（OIDC）**：GitHub Actions 拿短效 token 換 GCP 權限，Secrets 裡完全不存金鑰。設定一次，之後不用管。

### workflow

```yaml
name: deploy
on:
  workflow_dispatch:          # 預設手動觸發
  schedule:
    - cron: '0 18 * * *'      # UTC 18:00 = 台灣凌晨 02:00

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - run: npm ci --prefix functions
      - run: npx firebase emulators:exec "npm test --prefix functions"

  backend:
    needs: test               # 測試不過不部署
    runs-on: ubuntu-latest
    permissions:
      contents: read
      id-token: write         # WIF 必需
    steps:
      - uses: actions/checkout@v4
      - uses: google-github-actions/auth@v2
        with:
          workload_identity_provider: projects/<num>/locations/global/workloadIdentityPools/gh/providers/gh
          service_account: deployer@<project>.iam.gserviceaccount.com
      - run: npm i -g firebase-tools
      - run: firebase deploy --only firestore:rules,firestore:indexes,functions,hosting --project <project>

  android:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with: { distribution: temurin, java-version: '17' }
      - run: ./gradlew assembleRelease      # keystore 由 secrets 還原
      - uses: wzieba/Firebase-Distribution-Github-Action@v1
        with:
          appId: ${{ secrets.FIREBASE_APP_ID }}
          groups: store
          file: app/build/outputs/apk/release/app-release.apk
```

私人 repo 的 Actions 每月有 2000 分鐘免費額度，這個規模一個月約用 50 分鐘。

### 營業時間閘門

**不要設 `on: push` 自動部署。** POS 壞掉的時候，店家正在收錢。

- Firestore rules 部署是立刻生效的：一條規則寫錯，中午十二點全店同時跳 permission denied
- 後端改了、平板還是舊版 APK：欄位從 `price` 改成 `unitPrice`，平板沒更新就直接閃退

所以預設 `workflow_dispatch`（手動按了才上）加上打烊後的排程。單店自用這樣完全夠。

### 後端必須向後相容

新欄位用「新增」不用「改名」。舊欄位保留，讓舊版 APK 仍能運作，等確認平板都更新完再清掉。

APK 更新是**要平板收到通知並點安裝才生效**的，這個時間差必須設計進去。

### keystore 保管

**簽名檔遺失後，你再也無法更新那個 App**，只能移除重裝，而重裝會清空本地資料（含未同步的離線訂單）。

今天就把 keystore 與密碼備份到兩個不同的地方，並寫進交接文件。這是整個專案最不可逆的單點風險。

### App 更新發佈

用 **Firebase App Distribution**（免費）。推版本後平板收到通知直接更新，比 adb 傳檔案人性化很多。加入 `store` 測試群組即可。

### 環境與帳號分離

三層環境，從第一天就建立，不要等到要搬家才想：

| 環境 | 位置 | 用途 |
| --- | --- | --- |
| **local** | Firebase Emulator Suite（本機） | 日常開發與所有自動化測試。完全免費，不碰雲端 |
| **dev** | 開發者的 Firebase 專案，Blaze | 整合測試、真機測印表機。預算熔斷設 US$1 |
| **prod** | **店家的** Firebase 專案，Blaze | 正式營運。預算熔斷設 US$20 |

**注意：Spark 方案無法部署 Cloud Functions**，所以 dev 專案也必須是 Blaze。把熔斷設在 US$1，效果等同「到量就停」，但能真的跑 Function。

### 實作要求

- `.firebaserc` **不進版控**：裡面是真實的 Firebase 專案 ID，而這是 public repo（`CLAUDE.md`
  第一節把專案 ID 列在禁止進版控的清單裡）。每個人在自己的工作目錄跑一次
  `firebase use --add`，建立 `dev` / `prod` 兩個別名
- 部署一律帶 `-P <alias>`，不要依賴 current project
- Android 用 build flavor 分開 `google-services.json`：`app/src/dev/` 與 `app/src/prod/`
- CI 的 `workflow_dispatch` 要有 environment 參數，預設 `dev`
- **prod 專案在階段 3 之前就要建好**，每個階段結束做一次部署演練

### 搬到 prod 時需要人工處理的項目

這些不會隨 `firebase deploy` 自動過去，做成檢查清單：

- App Check 的 Play Integrity 重新註冊 SHA-256 指紋
- Functions 的 secrets（`firebase functions:secrets:set`）重新設定
- 自訂網域重新綁定與 DNS
- 預算警示與熔斷 Function 重新建立
- 第一組 owner 帳號與 custom claim

第一次部到 prod 才發現 App Check 沒註冊，會是在店家等著開幕的那天。所以要演練。

## 十一、建議開發順序

每個階段都要能實際跑給店家看，不要等全部做完才驗收。

### 階段 0：地基（先做完再寫功能）

- Firebase 專案建立（**開在店家名下**）、Blaze 啟用、預算警示與熔斷
- GitHub repo、WIF 設定、CI 跑得起來
- Firestore 資料模型 + Rules + rules 測試
- Android 空殼專案 + 登入
- **驗收**：CI 綠燈，平板能登入，Rules 測試全過

### 階段 1：最小可營運（MVP）

只做店員手動流程，**不含顧客點餐、不含後台**。菜單先用 seed script 灌測試資料。

- 桌位總覽、開桌、點餐、結帳（現金）
- 列印模組：先做 `NetworkTransport` + 圖片化列印 + 廚房單
- **驗收**：能在真實印表機上印出一張正確的中文廚房單

這個階段的驗收是整個專案的第一個真實風險點——印表機能不能通、中文會不會亂碼，在這裡就會見真章。

### 階段 2：離線

- Room 本地資料庫、Outbox 佇列、WorkManager 同步
- 同步狀態 UI
- 列印佇列持久化與失敗重試
- **驗收**：拔掉 Wi-Fi，仍能完成點餐、出單、現金結帳；插回後資料正確同步，且不重複

### 階段 3：店家後台

- 菜單編輯、選項群組、發佈流程
- 桌位編輯與 QR 列印
- `publishMenu` Function
- **驗收**：店家自己改一個品項的價格並發佈，平板上看到新價格

### 階段 4：顧客自助點餐

- 顧客點餐網頁、Anonymous Auth、App Check
- `createOrder` Function、限流、`qrToken` 驗證
- 平板的「待確認」畫面
- **驗收**：掃 QR 下單，店員確認後才出單；用 curl 直接呼叫並帶入假價格，必須被拒絕

### 階段 5：報表與收尾

- `rollDailyReport` 排程 Function
- 後台報表頁
- 員工帳號管理
- 平板 Kiosk 設定、交接文件

### 實作提醒

每個階段結束時請確認：

- Rules 測試有涵蓋這階段新增的 collection
- 沒有任何客戶端送金額的路徑被打開
- 新增的 listener 都有對應的 unsubscribe
- 相關的 Firestore 索引已加進 `firestore.indexes.json`

## 十二、工程實務

這一節的原則：**這是一人維運、無法在營業中止損的系統。** 選型標準不是「業界最佳實踐」，而是「出事時能多快知道、多快救回來」。

### 測試：只對會賠錢的地方嚴格

不要追求覆蓋率數字。按這個優先序投入：

| 層級 | 做法 | 嚴格程度 |
| --- | --- | --- |
| **Security Rules** | `@firebase/rules-unit-testing`，涵蓋第四節列出的拒絕案例 | **必做，CI 擋門** |
| **金額計算** | 把算價邏輯抽成純函式（`calcOrderTotal`），與 Firestore 完全解耦，用 TDD 寫 | **必做，先寫測試** |
| **列印位元組編碼** | 給定訂單 → 產出 bitmap → 比對 hash（golden test） | 必做 |
| **離線佇列狀態機** | 單元測試：重試、冪等、衝突 | 必做 |
| Function 整合流程 | Emulator 跑 createOrder → confirm → close | 主要路徑即可 |
| Compose UI | 只測複雜互動（購物車、折扣輸入） | 選擇性 |
| 顧客點餐頁 E2E | 一條 smoke path | 選擇性 |

**TDD 只用在金額計算這一塊。** 那裡規格精確（單價 × 數量 + 選項加價 − 折扣 = 總額）、邊界明確（四捨五入、負數、超量），是 TDD 最划算的場景。UI 和列印用先寫後補測試即可。

算價純函式的關鍵在於**不依賴 Firestore**：輸入是菜單資料與品項請求，輸出是 lines 與總額。這樣本機毫秒級跑完上百個 case。

**算價純函式至少要涵蓋的案例**（TDD 先寫這些）：

- 單品 × 數量、含多個選項加價、`priceDelta` 為負數
- 折扣後總額不得為負
- **分單均分的餘數**：100 元分 3 人 = 34 / 33 / 33，餘數歸第一張單，全程整數運算
- 按品項拆單後，兩張單的總和必須等於原單
- 作廢的 line（`voidedAt` 非 null）不計入總額
- `taxMode` 為 `exempt` / `zero` 的品項在 `taxSummary` 中分開計算

除了算價，還要有 Emulator 整合測試涵蓋：**同一張桌兩位客人同時送出訂單，必須只開出一個 session**（第十三節的 transaction）。

### 不要做的事

- 不追求 100% 覆蓋率
- 不對 Compose 畫面寫大量快照測試（改版就全紅，維護成本高於價值）
- 不引入 DDD 分層、Clean Architecture 四層目錄、hexagonal ports/adapters。單店 POS 的規模撐不起那些抽象
- 不寫 mock 掛 mock 的測試——用 Emulator，它比 mock 準

### 監控比測試更重要

測試防的是你想得到的錯；監控接的是你想不到的。對一個無法 SSH 進去的系統，後者更關鍵。

**必裝**

- **Firebase Crashlytics**（Android）：閃退即時回報
- **Cloud Error Reporting**（Functions）：錯誤聚合
- **結構化日誌**：每筆 log 帶 `orderId`、`storeId`、`uid` 與部署的 commit sha，才能追一整條流程，並知道是哪一包程式產生的

**告警（送到你的手機，不是 email）**

| 條件 | 意義 |
| --- | --- |
| Function 錯誤率 > 5%（5 分鐘） | 後端壞了 |
| 列印失敗率 > 10%（15 分鐘） | 印表機或網路有問題 |
| Firestore 讀取 > 3 萬 / 日 | 有迴圈 bug 或被刷 |
| 預算超過 US$1 / 5 / 20 | 見第九節 |
| **營業時段內 2 小時無任何訂單** | 死人開關：系統可能已癱瘓而店家在用紙筆 |

最後一條特別重要。店家通常不會主動回報「系統怪怪的」，他們會先想辦法撐過這一餐。

### Remote Config 當作止血閥

APK 更新要等平板點安裝才生效，這個時間差在出事時會要命。用 Firebase Remote Config 做兩件事：

1. **功能開關**：顧客自助點餐、折扣功能等可遠端關閉，不必發版
2. **最低支援版本**：`minAppVersion`，低於此版的 APK 啟動時強制提示更新。後端做破壞性變更時靠這個把舊版擋掉

### 開發流程：Trunk-Based + 功能開關

**新功能做完直接併進 `main`，用開關控制是否啟用。** 不開長命 feature branch。

單人開發加上側載 APK 的情境下，長命分支特別痛：分支開兩週，期間 `main` 改了 Firestore 結構，合併時衝突一堆。小步提交、一天內合併、未完成的功能關著出貨，可以避開。

目前規劃的開關：

```
feature.guestOrdering        # 顧客自助點餐
feature.waitlist             # 候位單
feature.splitOrder           # 分單併單
feature.kitchenAutoPrint     # 候位單自動進廚房
minAppVersion                # 強制更新門檻
```

Android 與 Web 讀同一組 Remote Config，開關預設 `false`。

### 三條必須遵守的開關規則

**1. 伺服器端也要檢查。** 只關前端按鈕沒用——有人直接呼叫 Cloud Function 照樣能用。每支受開關控制的 Function 在進入點檢查 Remote Config（Admin SDK 讀取，快取 5 分鐘）。

**2. 新欄位一律 optional。** 開關擋不住資料庫結構變化：功能關著，程式碼寫進去的欄位仍然存在。舊版 APK 讀到未知欄位必須能安全忽略，不可拋錯。

**3. 開關要有移除期限。** 建立開關時在程式碼註解寫明預計移除日期（通常是上線穩定後兩週）。不清理的話，半年後會有十五個開關而沒人記得哪個還有用。

### 分支保護

即使單人開發也走 PR，`main` 需 CI 通過才能合併。目的不是 code review，是強迫測試跑過。這與 trunk-based 不衝突——PR 的生命週期應該以小時計，不是以週計。

### 版本與發佈

**不編版本號，用 commit 追蹤。** 這是一套自己部署給單一店家使用的系統，不是發佈給別人安裝的套件：後端沒有下載者，APK 只推給店裡的平板。語意化版號與 CHANGELOG 的讀者是「裝了你的東西的人」，這個專案沒有這種人，所以不導入 `release-please`。

真正要能回答的問題是反向的：**出事的當下，店裡正在跑的這包是哪一份程式碼。** 第十節的部署刻意不跟合併綁定（`workflow_dispatch` 加打烊後排程），所以合併時產生的版號跟線上實際在跑的東西對不起來，中間可能夾著好幾次部署，也可能一次都沒部署過。改成在部署當下把 commit 記進成品：

- **Conventional Commits 照舊。** 這是 commit 歷史可讀的前提，與要不要編版號無關
- **functions**：部署時把 `GITHUB_SHA` 帶成 Function 的環境變數，並寫進結構化日誌（見本節〈監控比測試更重要〉）。這樣 Cloud Error Reporting 的每一筆錯誤自己就帶著「是哪一包程式產生的」
- **Android**：`versionCode` 用 CI 的 run number——`minAppVersion` 比對的是這個整數，不要拿字串比 semver。`versionName` 用 `0.<run_number>+<short sha>`，平板的關於頁面看一眼就知道對應哪個 commit；Crashlytics 也是照 app 版本分組，閃退能直接對回程式碼
- **每次部署打一個輕量 tag**（例如 `deploy/functions/2026-09-20.17`），由 deploy workflow 成功後自動打。**部署才是這個專案真正的發佈單位，不是合併**
- 想知道「這次上線了什麼」，用 `git log <上一個 deploy tag>..HEAD` 產生後貼進該次的 GitHub Release，不必維護一份 `CHANGELOG.md`
- **即使單人開發也走 PR**：分支保護要求 CI 通過才能合併 `main`。目的不是 code review，是強迫測試跑過
- `main` 永遠可部署

以上在階段 0 尾聲寫 `deploy.yml` 時一起做，不要更早——在 `functions/` 與 `android/` 存在之前沒有東西可以掛。

日後若 App 要上架 Google Play，或系統要給第二間店用，版本號才會開始有外部讀者，那時再導入 `release-please`（manifest 模式，`functions` 與 `android` 各自獨立版號，**不要綁在一起**：兩邊的發佈節奏本來就脫鉤，共用版號會謊報一包從未建出來的成品）。現在不裝，不會造成之後的遷移成本。

### 相依套件

- 開 **Dependabot 或 Renovate**，但設成**每週一次 PR、不自動合併**
- 安全性更新可自動合併；功能版本升級一律人工
- 理由：POS 不該因為某個套件半夜自動升級而在隔天中午壞掉

### 環境變數與機密

- 一律用 `firebase functions:secrets:set`，不要放進程式碼或 `.env` 進版控
- GitHub 端用 WIF，Secrets 裡不存長效金鑰（見第十節）
- keystore 與其密碼另外備份兩份（見第十節）

### 交接文件（`docs/RUNBOOK.md`）

這是唯一比測試更能決定長期維運成敗的東西。至少包含：

- 架構一頁圖
- 常見故障排除：印表機不出單、平板同步卡住、顧客掃碼無反應
- 如何回滾一次部署
- 如何新增員工帳號、重設密碼
- 帳號與權限清單（誰持有 GCP owner、keystore 在哪、網域註冊在誰名下）
- 緊急聯絡與降級流程：系統全掛時，店家該怎麼用紙筆撐過這一餐

最後一項請認真寫。**系統一定會有掛掉的一天**，那天店家需要的不是修復，是能繼續營業。

## 十三、訂單生命週期與顧客端

本節補充第三至第六節，凡與前面衝突處**以本節為準**。

### 顧客端不得有登入介面

顧客使用 Firebase Anonymous Auth，這是**技術上的識別碼，不是帳號**。客人的體驗必須是：掃 QR → 直接看到菜單 → 點餐 → 送出。

**不得出現任何註冊、登入、手機驗證、email 輸入或會員綁定介面。** 匿名 UID 由 SDK 在背景靜默取得，客人完全無感。

### 三個憑證，權限完全不同

| 憑證 | 存在哪 | 能證明什麼 |
| --- | --- | --- |
| `qrToken` | **印在桌上，公開** | 只能證明「我在這張桌旁」。任何走進店裡的人都拿得到，甚至可以拍照帶走 |
| `sessionId` | 客人瀏覽器 localStorage，32 hex | 「我是這個場次的當事人」，可讀該場次訂單 |
| `lookupCode` | 結帳時顯示給客人，4 碼數字 | 「我是這筆消費的當事人」，供 localStorage 遺失時查詢 |

**`qrToken` 絕對不可以當作讀取帳單的憑證。** 用它顯示前一組客人的消費明細，等於讓新坐下的客人看到陌生人的帳單。

### 掃碼後的分流

```
開啟網頁
  ├─ localStorage 有 sessionId
  │    ├─ session active        → 進入該場次，顯示已點項目
  │    └─ 已結帳且未過期        → 顯示帳單明細
  └─ 無 localStorage
       → 一律視為新客人，直接顯示菜單（不顯示任何帳單）
       → 頁尾放一個不顯眼的入口：「剛在這桌用餐過？查看帳單」
            → 要求輸入 4 碼 lookupCode → 驗證通過才顯示
```

`lookupCode` 由 `closeOrder` 產生，同時顯示在結帳頁與紙本明細單上。結帳頁要提示「離開前建議截圖」，並提供「儲存明細」按鈕（前端存成圖片，不要延長伺服器保存期）。

查詢要走 Function（`lookupReceipt`）並限流，不可讓客戶端直接暴力嘗試 4 碼。

### 自動開桌：掃碼不開桌，下單才開桌

**店員不需要按「開桌」。** 內場很忙，這個動作不會被執行。

但觸發點必須放在「送出訂單」而不是「掃碼」——否則結完帳想看帳單的客人重掃一次，就憑空開出一張空單，桌位總覽跳出假的「用餐中」。

```ts
// createOrder 內，用 transaction 避免兩人同時送出開出兩個 session
await db.runTransaction(async (tx) => {
  const active = await findActiveSession(tx, storeId, table.id);
  const session = active ?? createSession(tx, storeId, table.id);
  // ...驗價、建立或附加訂單
});
```

店員端的「開桌」按鈕仍保留，但只用於：口頭點餐（客人不用手機）、外帶單（無桌號）、併桌前建立目標桌。

### 自動清桌

既然店員沒空開桌，也不會按清桌。桌位釋放採兩個自動條件：

- 結帳後滿 **3 小時**（與 `receipts` 可讀期一致）
- 或該桌出現新的 active session 時立即釋放

桌位總覽上，已結帳未釋放的桌顯示為淡色「已結帳」，店員看得到但不需要動作。

### 結帳是硬分界線

`closeOrder` 執行後，該訂單鎖定總額、寫入 `businessDate`、搬進 `orders_archive`、產生 `receipts` 快照並計入當日營收。**已結訂單一律不可變更。**

結帳後要加點 = 開新的一張單，靠 `tableIds` 關聯，報表合併呈現。

桌位總覽要顯示「本桌今日 N 張單」，點進去看得到全部，加點按鈕直接開新單並自動掛上同一桌。店員操作不變，只是背後多一張單。

### 分單與併單

店家常用，必須實作。新增兩支 Function：

```ts
splitOrder(orderId, lineIds[])   // 把指定的 lines 移到新單
mergeOrders(orderIds[])          // 合併多張單，tableIds 取聯集
```

四個要求：

1. **要能按品項拆，不只按人數均分。** 常見需求是「這幾樣算我的」，UI 做成勾選 lines。
2. **均分的餘數一律算到第一張單，且全程整數運算。** 100 元分三人不可用浮點數除法。這要進第十二節的測試案例。
3. **已結的單不能再拆。** 拆單必須在 `closeOrder` 之前完成，結帳畫面要提供「分開結帳」入口。
4. **併桌後兩張 QR 都指向同一張單。** 作法是把 B 桌的 session 指到 A 桌的 order，客人無感。

### 邊界情況

**同桌有人先走、有人還在。** A 結完帳在門口看帳單（sessionId 仍在可讀期），B 還坐著想加點——B 會收到「本桌已結帳」。這是正確行為，但錯誤訊息要明確寫「如需加點請洽服務人員」，不可只丟「操作失敗」。

**客人切去別的 App 再切回來。** localStorage 還在，但 Firebase 匿名 UID 可能已被回收換新。所以**權限判斷一律掛在 `sessionId` 上，不要掛在 UID 上**。

**無痕視窗與 iOS Safari 的 7 天 localStorage 上限。** 當餐不受影響，隔天查詢會失效——這正是 `lookupCode` 存在的理由。

## 十四、候位點餐

受 `feature.waitlist` 開關控制。

店外候位的客人可以先點餐，入座後綁定桌號。這是店家實際營運中已在使用的流程。

### 流程

```
店外
 └─ 掃候位 QR（店門口，固定一張）
     → 填尊稱（例：陳先生）→ 點餐 → 送出
     → 建立訂單，status: 'waiting'，取得 4 碼訂單號
     → 印候位憑條（前場留存）
     → 是否同時印廚房單，依店家設定（見下）
     → 客人手機顯示訂單號與尊稱，提示截圖

入座
 └─ 掃桌邊 QR
     ├─ 手機有未綁桌的 waiting session
     │    → 顯示「陳先生，您的訂單要綁定『窗邊』嗎？」→ 一鍵確認
     └─ 無 session（換人掃、清快取、手機沒電）
          → 手動輸入 4 碼訂單號 + 尊稱前兩字驗證
     → bindTableToOrder：status 轉為 'open'，掛上 tableIds
     → 此時印廚房單（若先前未印）
     → 並印一張桌位對照單：訂單號 + 尊稱 + 桌號，無品項
```

**優先用 session 自動綁定，手動輸入只是備援。** 尖峰時讓客人自己打四位數，打錯就是兩桌的餐送反。

### 內場是否先做：交給店家決定

店家信任客人、內場有空就先做，這是他們對自己客群的判斷，系統不得代為決定。而且這個判斷**每個時段都會變**——平日晚上人少就先做，週末爆滿就不做。

因此做成**平板上隨時可切的三段設定**，放在桌位總覽頁，一鍵切換，不要藏進設定頁：

| 模式 | 廚房單時機 | 適用 |
| --- | --- | --- |
| **自動進廚房** | 候位單成立時立刻印 | 離峰、店家信任客人 |
| **詢問**（預設） | 印候位憑條，平板跳通知讓店員決定 | 一般時段 |
| **不進廚房** | 綁桌後才印 | 尖峰、生客多 |

對應 Remote Config 的 `feature.kitchenAutoPrint` 只控制這個功能是否存在；三段模式的選擇存在 `tenants/{storeId}/settings/waitlist`，由店家即時切換。

### 單據設計

**候位憑條**（一律印，給前場）：訂單號、尊稱、品項、時間、等候順位。

**廚房單**：在「自動」與「詢問」模式下提前列印時，必須明確標示 **「候位 #0412 陳先生 — 未入座」**，內場才知道做好要先放著。綁桌後印的桌位對照單則標示 **「窗邊 #0412 陳先生」**。

### 訂單號規則

- 4 碼數字，**每日歸零重用**，只保證當日不重複
- 太長客人記不住，太短會撞號，4 碼加上尊稱兩道驗證足夠
- 與第十三節的 `lookupCode` 是不同用途的兩個碼，不要混用

### 候位清單頁（平板）

- 顯示目前所有 `waiting` 訂單：訂單號、尊稱、等候時間、是否已下廚房
- 超過 30 分鐘的用顏色標示
- 可手動「取消候位」→ status 轉 `voided`，`voidReason: 'no_show'`
- 可直接對候位單結帳（客人改外帶時不必先綁桌）

### 逾時處理

候位單 90 分鐘未綁桌自動過期（排程 Function 處理），status 轉 `voided`，`voidReason: 'expired'`。過期前 15 分鐘在平板上提示店員。

### 未到率報表

`daily_reports` 要統計：候位單總數、綁桌數、未到數、未到的損失金額（已下廚房者才計損失）。

**這個數字是店家調整策略的依據。** 系統不替他們決定要不要先做，但要給他們判斷用的數據——一個月下來損失不多就繼續信任客人，開始痛了他們自己會改設定。

## 十五、外帶單與訂單型態轉換

### 三種訂單型態

訂單一律帶 `orderType`，**不要建一張叫「外帶」的虛擬桌**——假桌會污染桌位總覽、翻桌率與平均消費統計。

| 型態 | 桌號 | 號碼 | 最終歸宿 |
| --- | --- | --- | --- |
| `dine_in` | 有 | 無 | 結帳 |
| `takeout` | 無（`tableIds: []`） | 取餐號 | 結帳取餐 |
| `waitlist` | 尚未綁定 | 訂單號 | 綁桌後轉 `dine_in` |

`takeout` 與 `waitlist` 共用同一套發號機制：當日 4 碼、每日歸零、不重複。

### 內用與外帶的價差（最容易漏掉的一項）

台灣不少店**內用加一成服務費、外帶不加**，也有店外帶價不同。這必須在資料層支援：

```
menu_items: { price, takeoutPrice? }          // 未填則外帶同內用價
settings/pricing: { dineInServiceCharge: 0.1 } // 0 表示不收
```

**算價純函式要吃 `orderType` 當參數。** 這件事若一開始沒設計，之後補會動到整條算價邏輯與全部測試案例。

### 訂單行存雙價，不存單一價

```
lines: [{ unitPriceDineIn, unitPriceTakeout, ... }]
```

建立訂單時兩種價格都寫進快照。轉換型態時**直接切換使用哪一個，不重新查菜單**——否則期間店家調了價，轉換後金額會莫名其妙變動。

### 廚房單必須大字標示

外帶的包裝、醬料、餐具都不同。單上要大字「**外帶**」，不可只是小字備註。這是實際會出錯的地方。

（內用依規定須提供可重複使用餐具，外帶才給免洗。系統不管這件事，但單據標清楚能避免內場拿錯。）

### 外帶清單頁（平板）

外帶不佔桌位，需要獨立頁面，與候位清單並列：取餐號、品項、下單時間、狀態（製作中／可取餐／已取餐）。做好後店員按「完成」，平板叫號。

### 型態轉換

**結帳前：客人可自助轉換**

| 從 | 到 | 觸發方式 |
| --- | --- | --- |
| `waitlist` | `dine_in` | 掃桌邊 QR（第十四節） |
| `waitlist` | `takeout` | 客人在手機上按「改為外帶」 |
| `takeout` | `dine_in` | **掃桌邊 QR**，與候位單同一條路徑 |
| `dine_in` | `takeout` | 客人在手機上按「改為外帶」，或店員代操作 |

`takeout` → `dine_in` 走的就是 `bindTableToOrder`，與候位單共用同一支 Function 與同一套 UI，客人體驗一致。

**轉換時必須處理的四件事：**

1. **重算金額。** 切換 `unitPriceDineIn` / `unitPriceTakeout`，並重算 `serviceCharge`。轉換後要在客人手機與店員平板上**明確顯示金額變動**，不可默默改。
2. **已列印的品項要印變更通知單。** 若 `printedAt` 非 null，內場已經在做了——包裝方式不同，必須印一張「**#0412 改為外帶**」給廚房，不要假設他們會發現。
3. **桌位狀態要跟著動。** 轉成 `takeout` 要立刻釋放桌位（座位讓出來了）；轉成 `dine_in` 要綁定並佔用。
4. **候位順位要取消。** `waitlist` → `takeout` 時把該單移出候位清單。

**結帳後：只有店員可以改**

已結訂單依第十三節原則不可變更，所以結帳後的轉換是**更正作業**，不是一般編輯：

- 金額不變（無服務費、內外帶同價）→ 直接更正 `orderType`，寫入 `typeChangedAt` / `typeChangedBy` 留痕
- **金額有變**→ 不可直接改總額。必須作廢原單並重開，或產生一張差額單（補收／退款），兩者都要記入 `daily_reports` 的調整項

這支 Function 命名為 `amendOrderType`，僅 `staff` 以上可呼叫，且一律寫稽核記錄。日後若介接電子發票，**已開立發票的訂單一律禁止更正**——這條現在就要預留判斷點。

### 目前店家不收服務費，但規則仍完整實作

現況：`dineInServiceCharge` 預設 `0`，`takeoutPrice` 預設不填（外帶同內用價）。因此**現階段型態轉換的金額恆等**，店員操作就是一鍵完成，不會走到差額流程。

**但差額與作廢重開的邏輯必須真的寫出來，不可留 TODO 或拋 `NotImplemented`。** 理由：這條路徑會在最沒準備的時候被觸發——老闆某天自己到後台把服務費設成 10%，而且不會告訴開發者。

第十二節的測試要同時涵蓋兩條路徑：

- `dineInServiceCharge = 0` → 轉換一鍵完成，總額不變
- `dineInServiceCharge = 0.1` → 轉換觸發差額單，補收金額正確，`daily_reports` 調整項有記錄

這樣日後預設值一變動，CI 會替你確認另一條沒有壞掉。

### 報表要按型態分組

內用、外帶、候位的客單價與毛利結構完全不同。`daily_reports` 按 `orderType` 分組，並單獨列出型態轉換次數與調整金額。不分開的話，店家看不出外帶到底賺不賺。
