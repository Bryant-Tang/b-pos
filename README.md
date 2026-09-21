# b-pos

一間自營小餐廳的自用 POS 系統。店員用 Android 平板點餐結帳並出單到熱感印表機，
顧客可掃桌上 QR code 自助點餐，菜單與桌位由店家自行在 Web 後台編輯。

## 技術選型

| 層 | 選擇 |
| --- | --- |
| 後端 | Firebase（Blaze）、Cloud Functions 2nd gen（Node.js 22 + TypeScript） |
| 資料庫 | Cloud Firestore（原生離線快取與即時同步） |
| Android | Kotlin + Jetpack Compose，minSdk 26 |
| 顧客點餐頁 / 店家後台 | React + Vite，部署於 Firebase Hosting |
| 本地資料庫 | Room（離線佇列與快取） |

「後端」就是 `functions/` 底下的 Cloud Functions 加上 `firestore.rules`，
沒有自架 API 站台。

## 文件

- **[`docs/SPEC.md`](docs/SPEC.md)** — 完整技術規格：資料模型、Security Rules、
  Cloud Functions、列印模組、成本控管、開發順序
- **[`docs/firebase-setup.md`](docs/firebase-setup.md)** — 從零開一個 Firebase 專案
  到 GitHub 能自動部署的完整步驟，全程在瀏覽器裡點。換到店家的專案時照同一份再做一次
- **[`CLAUDE.md`](CLAUDE.md)** — 專案守則。第一條是**真實營運資料一律不進版控**

## 開發狀態

階段 0（地基）。伺服器端（`firestore.rules`、算價、`order_intents` 觸發器）已經
部署到真實的 Firebase 專案並驗證通過；平板 App 與顧客點餐頁還在進行中。

## 開發流程

Trunk-based：小步提交、一天內合併，未完成的功能用 Remote Config 開關關著出貨。
即使單人開發也走 PR，`main` 需 CI 的 `test` 檢查通過才能合併——目的不是 code review，
是強迫測試跑過。

部署不綁 `on: push`，走手動觸發加打烊後排程：POS 壞掉的時候，店家正在收錢。

## License

MIT
