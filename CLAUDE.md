# b-pos — 給 Claude Code 的專案守則

單店餐廳自用 POS 系統。完整技術規格在 `docs/SPEC.md`，動手前先讀相關章節。

**這是 public repo。** 推上去的任何東西全世界都看得到，而且 git history 刪不乾淨——
即使之後補一個 commit 移除，舊 commit 仍可被讀取。所以下面第一條規則沒有例外。

## 一、真實資料一律不進版控（最高優先，無例外）

**禁止把任何真實營運資料寫進這個 repo**，包含但不限於：

- 店家的**真實店名、地址、電話、統一編號、營業時間、負責人姓名**
- **真實菜單**：品項名稱、售價、成本、進貨資訊
- **真實訂單、營收、報表**，以及任何 Firestore / BigQuery 匯出檔
- **顧客資料**：姓名、尊稱、電話、匿名 UID、sessionId、lookupCode、訂單號
- **員工資料**：姓名、email、uid、帳號密碼
- 真實的 `qrToken`、API key、service account 金鑰、Firebase 專案 ID、印表機 IP 或 MAC
- 含上述內容的**截圖**（平板畫面、後台畫面、出單照片）

`.gitignore` 已擋掉常見的落點（`/data/`、`*.firestore-export`、`**/seed.real.*`、
`/screenshots/`、`**/serviceAccount*.json`、`google-services.json`、`.env*`、`*.jks`、`*.keystore`），
但 **.gitignore 是最後一道防線，不是許可證**。沒被 ignore 不代表可以放。

### 該怎麼做

- 測試資料、seed script、範例、文件、commit message、PR 描述**一律用虛構資料**。
  店名用「範例餐廳」，人名用「陳先生」「王小姐」，品項用「牛肉麵」「珍珠奶茶」，
  價格用整數假值。不要用「看起來像真的」的資料。
- 需要真實資料才能重現的問題，把資料留在本機（`/data/`，已 ignore），
  commit 裡只描述現象與結構，不貼內容。
- 真實設定值走 `firebase functions:secrets:set` 與 GitHub Secrets（WIF，見 SPEC 第十節），
  不要寫進程式碼、`.env` 或文件。
- 不確定某份資料算不算真實資料 → **當作是，先問 Bryant**，不要自己判斷後推上去。

### 如果不小心推了

立刻說，不要自己默默 force push 蓋掉。公開 repo 的 commit 可能已被 fork 或快取，
處理順序是「先撤換憑證（rotate），再處理 history」——只改 history 不換憑證等於沒處理。

## 二、三條不可違反的架構原則（SPEC 第零節）

1. **客戶端永遠不送金額。** 下單請求只能帶品項 ID 與數量，價格一律由 Cloud Function
   從資料庫查出後計算。zod schema 一律加 `.strict()`。
2. **顧客端不能直接寫入 Firestore。** 顧客只能讀公開菜單，下單必經 Cloud Function，
   `orders` 對顧客的寫入權限恆為 `false`。
3. **離線必須還能點餐出單。** 本地先寫入、回線後同步，且重試必須冪等（不可重複出單）。

改動任何涉及金額、權限或跨使用者狀態的邏輯時，先確認沒有違反這三條。

## 三、分支與 CI

- Trunk-based：小步提交、一天內合併，不開長命 feature branch，未完成的功能用
  Remote Config 開關關著出貨。
- **即使單人開發也走 PR**，`main` 需 CI 通過才能合併。主要目的是強迫測試跑過。
- CI 是 `.github/workflows/ci.yml` 的 `test` job。目前 `functions/` 尚未建立時會自動略過；
  一旦 `functions/package.json` 存在，Rules 測試與算價測試就會開始擋門。
- 另有 `.github/workflows/claude-review.yml`，每個 PR 會自動跑一次 code review 並留言。
  它是**建議不是閘門**：不在必要檢查裡，留言不擋合併，該不該照做由人決定。
  fork 來的 PR 依設計拿不到 secrets，會略過自動 review，要人工看。
- 部署另走 `workflow_dispatch` + 打烊後排程，**不要加 `on: push` 自動部署**
  （SPEC 第十節〈營業時間閘門〉：POS 壞掉的時候，店家正在收錢）。
- Commit message 用 Conventional Commits。

## 四、不要做的事

- 不要生成空的 adapter、interface 或標著 TODO 的樁函式（SPEC 第一節有完整理由）。
- 不要引入 Redux/MobX、DDD 分層、Clean Architecture 四層目錄。
- 不要建 Express/NestJS 之類的自架 API 站台——「後端」就是 `functions/` 加 `firestore.rules`。
- 不要為了簡化而移除 SPEC 第一節〈已經預留的〉表格裡的任何一條。
