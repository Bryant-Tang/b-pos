# 開一個 Firebase 專案給 b-pos 用

從「只有一個 Google 帳號」開始，一路做到在 GitHub 按一個鍵就能部署。

**全程在瀏覽器裡點，不需要在自己的電腦上裝任何東西，也不需要把這個 repo clone 下來。**

這份文件是可重複的程序，不是一次性的筆記。開發用的測試專案照著做一次，
之後要開店家的正式專案就再照著做一次——兩者的差別集中在
[D. 換到店家的專案](#d-換到店家的專案)。

| 階段 | 步驟 | 大概時間 |
| --- | --- | --- |
| A. 開專案 | 1 – 5 | 20 分鐘 |
| B. 讓 GitHub 能部署 | 6 – 10 | 30 分鐘 |
| C. 部署並驗一次 | 11 – 12 | 15 分鐘 |

---

## 動手之前要知道的三件事

**一、只有一個決定是不可逆的：Firestore 的位置。**
步驟 4 要選 `asia-east1`。選錯只能把整個專案砍掉重開，沒有別的辦法。
其他每一步做錯都能回頭改。

**二、要綁信用卡，但正常情況不會扣到錢。**
Cloud Functions 規定專案要有付款方式（Blaze 方案），這不等於開始收費——
免費額度原封不動保留著。步驟 3 會設一道會真的停掉服務的上限當保險。

**三、正式專案要開在店家名下。**
帳單和顧客資料在法律上的持有者是店家不是開發者（SPEC 第九節〈Billing 歸屬〉）。
開發用的測試專案不要轉正，正式環境另外開一個。

---

# A. 開專案

## 步驟 1：建 Firebase 專案

一個 Google 帳號就夠了，不用先開 Google Cloud 帳號——Firebase 專案建好的時候，
背後那個 GCP 專案會自動跟著生出來。

1. 開 [Firebase console](https://console.firebase.google.com/?hl=zh-TW)，用 Google 帳號登入
2. 按「建立專案」
3. 取名字（例如 `b-pos-dev`）
   - 名稱下面會顯示**專案 ID**，可能被加上亂碼變成 `b-pos-dev-a1b2c`。**抄下來。**
4. 下一頁問 Google Analytics → **關掉**。POS 用不到，開了還要多同意一份條款
5. 按建立，等三十秒

建好之後再抄一次**專案編號**（project number，一串純數字），
在左上角齒輪 →「專案設定」→「一般設定」裡。

> **專案 ID 和專案編號是兩個不同的東西，後面會分別用到，抄的時候不要混。**
> 專案 ID 是英數字串，專案編號是純數字。步驟 7、9 用編號，步驟 10 兩個都要。

## 步驟 2：升級 Blaze 方案

免費的 Spark 方案不能部署 Cloud Functions，而算價、建訂單的程式全在那裡。
Blaze 的差別是「免費額度用完後改成計費」，不是「超過就斷線」。

1. 左下角方案那一塊按「升級」→ 選 **Blaze**
2. 建立或選擇一個帳單帳戶，綁信用卡
3. 中間會問「設定預算金額」：這一格只是寄信用的警示，填多少都不影響服務，
   真正會停掉服務的上限在下一步

## 步驟 3：設支出上限與預算警示

分兩件事做。第一件會真的停掉服務，第二件只是寄信。

### 3a. Cloud Functions 的支出上限（會真的停）

Firebase console →「設定」→「用量與帳單」→「詳細資料與設定」→
找到「**服務層級支出上限**」那張卡片。

- 服務選 **Cloud Functions for Firebase**
- 每月預算：測試專案填約 **US$5**，正式專案填 **US$20**
- 按「設定」

> **這個數字和 SPEC 裡的預算熔斷是兩回事，不要拿去對。**
> 服務層級支出上限是 SPEC 寫完之後才有的機制。SPEC 第十節給 dev 的熔斷是 US$1，
> 理由是「效果等同到量就停」——那個理由**不能套用在這裡**：熔斷算的是實際帳單，
> 而支出上限算的是毛額成本（見下面第一點），填 US$1 會在還沒用到任何付費額度時
> 就把服務停掉。正式專案的 US$20 則是刻意沿用 SPEC 第九節的數字。

到 100% 的時候那個服務**這個月剩下的時間都會被暫停**，50% 和 80% 會先寄信。
下個月一號自動恢復；想提早恢復就回同一個地方按「解除」。

四件必須知道的事：

- **不能填 0，也不要填個位數。**
  [官方文件](https://firebase.google.com/docs/projects/billing/spend-caps?hl=zh-TW)
  寫明上限的計算「以毛額成本為準，**不含折扣與抵免**」，而 Google 的免費額度在帳單上
  正是以抵免的形式呈現。也就是說 0 元上限不等於「用完免費額度才停」，而是
  **一有任何用量就停**——光是部署本身就足以觸發。
- **幣別跟著帳單帳戶走。** 台灣開的帳號是新台幣，US$5 就填 **NT$150**、US$20 填 **NT$600**。
- **不是零時差。** 用量回報本身有延遲，文件寫明執行可能慢上幾分鐘，那幾分鐘超出的仍要付費。
- **不是每個服務都有。** 目前只涵蓋 Cloud Functions、App Hosting、Firebase AI Logic、
  Extensions。**Firestore 不在內。** 好消息是會燒錢的迴圈通常跑在 Function 裡，
  Function 一停，它連帶產生的 Firestore 用量也跟著停。

> **正式環境的上限是最後的保險，不是日常控管。**
> Cloud Functions 一被暫停，`order_intents` 就沒人處理，平板送出的單建立不起來——
> 等於營業中 POS 掛掉，而且按下解除之後最多要一小時才會完全恢復。
> 所以正式專案要設得比你預期的用量高很多。

### 3b. 整個專案的預算警示（只會寄信）

1. 開 [Cloud Billing](https://console.cloud.google.com/billing?hl=zh-TW) →
   「預算與快訊」→「建立預算」
2. 範圍選這個專案
3. 金額與 3a 相同
4. 警示門檻勾 **50% / 90% / 100%**
5. 勾「將快訊電子郵件傳送給帳單帳戶的收款人和使用者」，儲存

這一層涵蓋 Firestore 等沒有支出上限的服務，但**只會寄信，不會停**。

### 成本防線目前有幾層

| 層 | 涵蓋範圍 | 反應速度 | 會不會真的停 | 狀態 |
| --- | --- | --- | --- | --- |
| `onOrderIntentCreated` 的 `maxInstances: 10` | 單一函式失控展開 | 立即 | 會 | 已實作 |
| 服務層級支出上限（3a） | Cloud Functions 等四項 | 幾分鐘 | 會 | 照本文設定 |
| 預算警示（3b） | 整個專案 | 幾小時 | 不會，只寄信 | 照本文設定 |
| SPEC 第九節的預算熔斷 | 整個專案 | 幾小時 | 會（解除帳單綁定） | **尚未實作** |

## 步驟 4：建 Firestore 資料庫

1. 左邊「建構」→「Firestore Database」→「建立資料庫」
2. 資料庫 ID 用預設的 **`(default)`**，不要自己取名字
3. **位置選 `asia-east1`（台灣）**

   > **這是整份文件裡唯一做錯要重來的地方。**
   > `functions/src/index.ts` 把 Cloud Function 的區域寫死成 `asia-east1`，
   > 而 Firestore 觸發器要求資料庫與函式同區，不同區就部署不上去。
   > 而且 **Firestore 的位置一旦建立就永久不能改**，選錯只能砍掉整個專案重開。

4. 模式選「**以正式版模式啟動**」（Production mode）
   - 預設全部擋住。不要選測試模式，那是三十天內全世界都能讀寫。
   - 真正的規則是 repo 裡的 `firestore.rules`，步驟 11 會部署上去。

## 步驟 5：開 Authentication

1. 「建構」→「Authentication」→「開始使用」
2. 在「Sign-in method」分頁啟用兩個：

| 方式 | 給誰用 | 注意 |
| --- | --- | --- |
| 電子郵件／密碼 | 老闆、店員 | **不要勾**「電子郵件連結（免密碼登入）」。店員在共用平板上還得去開信箱收信才能登入，比打密碼麻煩 |
| 匿名 | 掃 QR code 的顧客 | **勾「啟用自動清理」**。客人查帳單靠的是 `sessionId` 與 4 碼 `lookupCode`，不是這個匿名帳號，舊帳號留著只是囤顧客識別碼 |

現在還不用建任何帳號。

> **App Check 測試階段不要開 enforce。** SPEC 第四節說正式環境要開（擋人家寫腳本
> 直接打資料庫），但在還沒註冊應用程式之前開了，連自己從 console 打都會被擋，
> 很容易誤判成程式壞掉。等要上真機再開。

---

# B. 讓 GitHub 能部署

GitHub Actions 要幫忙部署，就得有權限動這個 Firebase 專案。

最直覺的做法是產生一把 service account 的 JSON 金鑰貼進 GitHub Secrets。
問題是**那把金鑰永久有效**，外洩等於整個專案被接管，而且不會收到通知。
這個 repo 又是公開的，更不划算。

**WIF**（Workload Identity Federation）換個做法：GitHub 每次跑 workflow 時出示一張
「我是 `Bryant-Tang/b-pos` 的 Actions」的臨時證明，Google 驗過才發一把只活幾十分鐘的
鑰匙。結果是 **GitHub 上完全不存任何長期金鑰**。

## 步驟 6：啟用需要的 API

一個一個點進去按「啟用」。有些會顯示「管理」而不是「啟用」，那表示已經開好了，跳過。

| API | 為什麼需要 |
| --- | --- |
| [Cloud Functions API](https://console.cloud.google.com/apis/library/cloudfunctions.googleapis.com?hl=zh-TW) | 部署 Functions 本身 |
| [Cloud Run Admin API](https://console.cloud.google.com/apis/library/run.googleapis.com?hl=zh-TW) | 第二代 Functions 實際上跑在 Cloud Run 上 |
| [Cloud Build API](https://console.cloud.google.com/apis/library/cloudbuild.googleapis.com?hl=zh-TW) | 把程式碼打包成容器 |
| [Artifact Registry API](https://console.cloud.google.com/apis/library/artifactregistry.googleapis.com?hl=zh-TW) | 打包好的容器放這裡 |
| [Cloud Storage API](https://console.cloud.google.com/apis/library/storage.googleapis.com?hl=zh-TW) | 上傳打包好的程式碼 |
| [Eventarc API](https://console.cloud.google.com/apis/library/eventarc.googleapis.com?hl=zh-TW) | Firestore 觸發器靠它把事件送到 Function |
| [Cloud Pub/Sub API](https://console.cloud.google.com/apis/library/pubsub.googleapis.com?hl=zh-TW) | Eventarc 底層用它傳遞事件 |
| [Firebase Rules API](https://console.cloud.google.com/apis/library/firebaserules.googleapis.com?hl=zh-TW) | 部署 `firestore.rules` 與索引 |
| [Cloud Billing API](https://console.cloud.google.com/apis/library/cloudbilling.googleapis.com?hl=zh-TW) | CLI 部署前要確認專案真的在 Blaze 方案 |
| [Firebase Extensions API](https://console.cloud.google.com/apis/library/firebaseextensions.googleapis.com?hl=zh-TW) | 專案沒用 Extensions，但 CLI 每次部署都會查一下 |
| [IAM Service Account Credentials API](https://console.cloud.google.com/apis/library/iamcredentials.googleapis.com?hl=zh-TW) | WIF 換短效憑證要用 |
| [Cloud Resource Manager API](https://console.cloud.google.com/apis/library/cloudresourcemanager.googleapis.com?hl=zh-TW) | WIF 驗身分時要用 |

> **為什麼要手動點，不讓 CI 自己開？**
> 在本機跑 `firebase deploy` 時，CLI 會互動式地一個一個問你要不要啟用。CI 是無人值守
> 模式問不了，而 CI 用的帳號**刻意只給 Service Usage 消費者、不給管理員**（步驟 8）：
> 啟用 API 是一次性的事，沒必要為此長期放大 CI 的權限。
>
> 漏開的後果不嚴重：部署會失敗，而錯誤訊息會直接寫缺哪一個 API 並附上啟用連結。

## 步驟 7：建 Workload Identity 集區

GCP console →「IAM 與管理」→「Workload Identity 聯合」→「建立集區」

1. 集區名稱與 ID：`github`，繼續
2. 新增供應商：
   - 類型：**OpenID Connect (OIDC)**
   - 名稱與 ID：`github`
   - **核發者網址**：`https://token.actions.githubusercontent.com`
   - 對象（Audience）：預設
3. **屬性對應**——這裡要有**兩行**：

   | Google 欄位 | 填 |
   | --- | --- |
   | `google.subject` | `assertion.sub` |
   | `attribute.repository` | `assertion.repository` |

   > `google.subject` 那行通常是 console 預設就給你的。**要「再新增一行」放
   > `attribute.repository`，不是把預設那行改掉。** 少了 `google.subject`，
   > 部署會停在認證這一步，錯誤訊息是
   > `Could not obtain a value for google.subject from the given credential`。
   >
   > `attribute.repository` 那行也不能省：步驟 9 的 `principalSet://` 與下面的
   > 屬性條件都靠它。

4. **屬性條件**——這格一定要填：

   ```
   assertion.repository == 'Bryant-Tang/b-pos'
   ```

   **這是整段設定裡最重要的一行。** 沒有它，全世界任何一個 GitHub repo 的 Actions
   都能拿到這個專案的權限。填了之後只有這個 repo 通得過。

## 步驟 8：建一個給 CI 用的服務帳號

GCP console →「IAM 與管理」→「服務帳戶」→「建立服務帳戶」

- 名稱：`github-deployer`
- 在「授予專案存取權」那一步加上這些角色：

| 角色 | 為什麼需要 |
| --- | --- |
| Firebase Admin | 部署 Firestore rules 與索引 |
| Cloud Functions 管理員 | 部署 Functions |
| Cloud Run 管理員 | 第二代 Functions 跑在 Cloud Run 上 |
| Artifact Registry 管理員 | 容器放這裡，也用來設清理政策 |
| Cloud Build 編輯者 | 負責打包容器 |
| Eventarc 管理員 | Firestore 觸發器靠它傳事件 |
| 服務帳戶使用者 | 部署時要「以 Functions 的執行身分」建立資源 |
| Service Usage 消費者 | 呼叫上面那些 API 時要用 |

看起來很多，但這就是「部署一支第二代 Cloud Function」會碰到的所有零件。
之後部署失敗跳權限錯誤的話，訊息會直接寫缺哪一個。

> **刻意沒給的兩個角色**，不要為了省事補上去：
> - **Service Usage 管理員**——可以自己啟用任何 API（理由見步驟 6）。
> - **Project IAM 管理員**——可以改寫專案的 IAM 政策。能改專案 IAM 的 CI
>   等於可以自己給自己任何權限。步驟 9b 那三筆改由人工補，就是為了不給這個。

抄下這個帳號的 email，長得像 `github-deployer@<專案ID>.iam.gserviceaccount.com`。

## 步驟 9：讓這個 repo 可以「扮演」那個帳號

前兩步各自做好了「誰可以進來」和「有什麼權限」，這一步把兩者接起來。

「服務帳戶」→ 點 `github-deployer` →「權限」分頁 →「具備存取權的主體」→「**新增主體**」
（有些 console 版本那顆按鈕叫「授予存取權」，是同一件事）

- 在「新增主體」欄貼上，把 `<專案編號>` 換成步驟 1 抄的那串數字：

  ```
  principalSet://iam.googleapis.com/projects/<專案編號>/locations/global/workloadIdentityPools/github/attribute.repository/Bryant-Tang/b-pos
  ```

  貼上去的時候 console 可能因為它不是 email 格式而顯示得怪怪的，照貼即可。

- **角色**：「Workload Identity 使用者」

儲存。

## 步驟 9b：手動補三筆服務代理的權限

第二代 Functions 要靠 Pub/Sub 與 Eventarc 把 Firestore 事件送進來，Google 為此在專案裡
自動生了幾個「服務代理」帳號。第一次部署時 Firebase CLI 會想幫它們補上必要的角色，
但那個動作等於改寫整個專案的 IAM 政策，而 `github-deployer` 沒有這個權限（見步驟 8）。

所以這三筆用專案擁有者的身分補一次，之後都不用再管。

開 [Cloud Shell](https://console.cloud.google.com/?cloudshell=true&hl=zh-TW)
（GCP console 右上角的 `>_` 圖示也可以，是瀏覽器裡的終端機，不用在自己電腦裝東西），
把下面整段貼進去按 Enter：

```bash
PROJECT=<專案編號>
gcloud projects add-iam-policy-binding $PROJECT \
  --member=serviceAccount:service-$PROJECT@gcp-sa-pubsub.iam.gserviceaccount.com \
  --role=roles/iam.serviceAccountTokenCreator
gcloud projects add-iam-policy-binding $PROJECT \
  --member=serviceAccount:$PROJECT-compute@developer.gserviceaccount.com \
  --role=roles/run.invoker
gcloud projects add-iam-policy-binding $PROJECT \
  --member=serviceAccount:$PROJECT-compute@developer.gserviceaccount.com \
  --role=roles/eventarc.eventReceiver
```

第一次用 Cloud Shell 會問要不要授權，按同意。每一筆成功會印出一大段 YAML，
那是更新後的完整政策，正常。

> 沒做這步的話，部署會走到最後才失敗，訊息是
> `We failed to modify the IAM policy for the project`，上面還會附這三行指令。

## 步驟 10：在 GitHub 設環境與 secrets

repo →「Settings」→「Environments」→「New environment」。
測試專案取名 `dev`，店家的正式專案取名 `prod`。

進去在「Environment secrets」加三個：

| Secret 名稱 | 值 |
| --- | --- |
| `GCP_WIF_PROVIDER` | `projects/<專案編號>/locations/global/workloadIdentityPools/github/providers/github` |
| `GCP_SERVICE_ACCOUNT` | `github-deployer@<專案ID>.iam.gserviceaccount.com` |
| `FIREBASE_PROJECT_ID` | `<專案ID>` |

注意前兩個裡面，一個要專案**編號**（純數字），一個要專案 **ID**（英數）。填錯不會
馬上報錯，要到部署時才會認證失敗。

> **用 environment secrets 而不是 repository secrets**，是為了讓之後換到店家專案
> 只是「多一個 environment」，`deploy.yml` 一個字都不用改。

---

# C. 部署並驗一次

## 步驟 11：跑第一次部署

repo →「Actions」→ 左邊選「**deploy**」→ 右邊「**Run workflow**」：

- 分支選 `main`
- environment 選 `dev`（或 `prod`）
- 按綠色按鈕

它會先跑完整測試（型別檢查、編譯、單元測試、emulator 測試），通過之後才部署
Firestore rules、索引與 Functions。第一次大約五到八分鐘。

> **要重跑的時候用「Run workflow」開新的一次，不要按舊執行上的「Re-run」。**
> Re-run 會用**當初那次執行的程式碼**重跑一遍。`main` 上有新的修正時，
> Re-run 抓不到，你會看到一模一樣的舊錯誤。

跑完回 Firebase console →「建構」→「Functions」，`onOrderIntentCreated` 應該在那裡，
區域是 `asia-east1`。

### 第一次部署到一個全新專案時會失敗一次，這是正常的

Rules、索引、程式碼都會上去，但最後「建立函式」那步會這樣：

```
Permission denied while using the Eventarc Service Agent.
Since this is your first time using 2nd gen functions, we need a little bit
longer to finish setting everything up. Retry the deployment in a few minutes.
```

Eventarc 是這次部署才開通的，Google 幫它配權限要幾分鐘才全面生效。
**等五到十分鐘，重跑一次就好。** 換到店家專案時會再遇到一次。

等了還是同一個錯，才需要回 Cloud Shell 補這一筆：

```bash
PROJECT=<專案編號>
gcloud projects add-iam-policy-binding $PROJECT \
  --member=serviceAccount:service-$PROJECT@gcp-sa-eventarc.iam.gserviceaccount.com \
  --role=roles/eventarc.serviceAgent
```

## 步驟 12：驗一次，確認真的有動

到 Firebase console →「Firestore Database」→「資料」，手動建兩份文件。

**以下全是虛構資料**，`CLAUDE.md` 第一條規定真實店家資料不得進入這個 repo，
也不要拿真實菜單來做這個測試。

數字欄位的型態一律選 **int64**：這個系統的金額是整數新台幣沒有小數，
而 `qty` 在 `functions/src/orders/intentSchema.ts` 裡明確要求是整數。

### 第一份：菜單

集合 `tenants` → 文件 ID `store_test` → 子集合 `published` → 文件 ID `menu`

| 欄位 | 型態 | 值 |
| --- | --- | --- |
| `version` | int64 | `1` |
| `items` | array | 一個 map（見下） |
| `optionGroups` | array | 空的 |

`items` 裡那個 map：

| 欄位 | 型態 | 值 |
| --- | --- | --- |
| `id` | string | `item_beef_noodle` |
| `name` | string | `牛肉麵` |
| `price` | int64 | `180` |
| `takeoutPrice` | int64 | `170` |
| `taxMode` | string | `taxable` |
| `optionGroupIds` | array | 空的 |

### 第二份：下單意圖

回到 `tenants/store_test` → 子集合 `order_intents` → 文件 ID `intent_0001`

| 欄位 | 型態 | 值 |
| --- | --- | --- |
| `intentId` | string | `intent_0001` |
| `orderId` | string | `order_0001` |
| `orderType` | string | `takeout` |
| `tableId` | **null** | 型態直接選 null |
| `lines` | array | 一個 map（見下） |
| `createdBy` | string | `tablet_test` |
| `clientCreatedAt` | **timestamp** | 現在時間 |

`lines` 裡那個 map：

| 欄位 | 型態 | 值 |
| --- | --- | --- |
| `itemId` | string | `item_beef_noodle` |
| `qty` | int64 | `2` |
| `options` | array | 空的 |

> 用外帶（`takeout`）是故意的：內用單一定要有桌位文件，外帶不用，少建一份。

### 應該看到什麼

儲存後**幾秒內**，`tenants/store_test/orders/order_0001` 會自己出現：

- `total` = **340**（外帶價 170 × 2）
- `pickupCode` = `"0001"`
- `status` = `"open"`
- `lines` 裡有當下的價格快照

同時 `intent_0001` 上會多出 `appliedAt` 和 `orderId`。

看到這個，代表 **Security Rules、算價、觸發器三樣都在雲端真的跑起來了**，
而且整條自動部署的路也通了。

### 沒出現的話

- console →「建構」→「Functions」→ 點 `onOrderIntentCreated` →「記錄檔」，錯誤在那裡。
- 如果 `intent_0001` 上冒出來的是 `rejectReason` 而不是 `appliedAt`，代表程式收到了
  但資料有問題，旁邊的 `rejectDetail` 會寫哪個欄位錯。
- 如果什麼都沒發生、記錄檔也是空的，先確認步驟 3a 的支出上限沒有被觸發
  （Firebase console →設定→用量與帳單）。服務被暫停時函式不會被觸發，
  而且按下解除之後最多要一小時才恢復。

驗完把 `tenants/store_test` 整個刪掉。

## 步驟 13：試顧客掃 QR code 點餐

步驟 12 驗的是店員那條路（平板寫意圖、伺服器出單）。這一步驗顧客那條：
掃 QR code 開網頁、自己點、送出，伺服器建單。

先確認部署有跑過一次**包含 Hosting 的版本**（deploy workflow 的 `--only` 裡要有
`hosting`）。網址在 Firebase console →「建構」→「Hosting」上面那一行，
長得像 `https://<專案 ID>.web.app`。

### 建一份桌位文件

顧客端不能讀 `tables`（那是刻意的，可預測的桌號等於讓任何人在家就能對任意桌下單），
所以桌位只能在 console 建。

集合 `tenants` → 文件 `store_test` → 子集合 `tables` → 文件 ID `table_a1`

| 欄位 | 型態 | 值 |
| --- | --- | --- |
| `label` | string | `A1` |
| `qrToken` | string | `0123456789abcdef0123456789abcdef` |
| `archived` | boolean | `false` |
| `activeSessionId` | **null** | 型態直接選 null |

> `qrToken` 必須剛好 **32 個 16 進位字元**，網頁與 `createOrder` 兩邊都會擋格式。
> 上面這個值是給測試專案用的假 token，**正式環境絕對不能自己編**：真的桌位要等
> `createTable` 做出來，由伺服器產生隨機值。猜得到的 token 等於任何人都能對那桌下單。
>
> `archived` 一定要有，`findTableByToken` 只用 `qrToken` 查、在程式裡判斷 `archived`，
> 少了這個欄位不會壞，但漏了 `label` 客人畫面上就看不到桌號。

菜單沿用步驟 12 建的那一份就好。想看選項（辣度、加料）的話再往 `items` 裡加
`optionGroupIds`，並把 `optionGroups` 補起來。

### 掃進去

把網址組起來：

```
https://<專案 ID>.web.app/?s=store_test&t=0123456789abcdef0123456789abcdef
```

用手機開（或電腦瀏覽器直接貼也行）。要產生真的 QR code 拿手機掃，
把這串網址丟進任何一個線上 QR 產生器就好。

### 應該看到什麼

1. 畫面出現菜單，**最上面顯示「桌號 A1」**（掃錯桌的話這裡就看得出來），
   點得進去、加得進購物車，金額是**預估**（下面那行小字有講）。
2. 按「送出訂單」後畫面變成「已送出」，上面有桌號 `A1`。
3. Firestore 裡 `tenants/store_test/orders/` 多一張單：`source` = `guest`、
   `status` = `pending_confirm`、`total` 是伺服器算的（內用價 180 × 份數）。
4. `tenants/store_test/tables/table_a1` 的 `activeSessionId` 從 null 變成一串亂數，
   `tenants/store_test/sessions/` 多一份對應的文件。
5. 在「已送出」畫面按「我要加點」再送一次，**不會**變成第二張單，
   而是加到同一張單的 `lines` 裡。這就是同桌共用一張單。
6. 換一支手機（或換一個瀏覽器）掃同一張 QR code，菜單頁上會出現
   「這桌目前已經點了 N 份，合計 $X，你點的會加在同一張單上」——
   同桌的第二個人掃進來看到的就是這個。

### 沒出現的話

- **「這張 QR code 已經失效」**：`qrToken` 打錯，或 `archived` 是 `true`。
  32 個字元裡有大寫或 `g` 以上的字母也會失效。
- **「店家還沒有發佈菜單」**：`tenants/store_test/published/menu` 不存在，回去做步驟 12。
- **一直轉圈或「暫時看不到菜單」**：Authentication 的**匿名登入**沒開（步驟 5），
  顧客端是用匿名身分登入的。
- **「送出得太頻繁了」**：限流生效了，同一個匿名身分每分鐘最多送 5 次，等一下再試。
- 其他錯誤到 console →「建構」→「Functions」→ `createOrder` →「記錄檔」看。

### 想再測一次的話

不用整個砍掉重建。最省事的是把 `tenants/store_test/tables/table_a1` 的
**`activeSessionId` 改回 null**——那個欄位就是「這桌現在這一攤」的指標，清掉之後
那張桌對程式來說就是全新的。想連資料一起清乾淨，再刪 `orders` 與 `sessions`
兩個子集合。

> **不要只刪 `orders` 卻留著 `sessions`。** 指標還指著一個活著的場次、但它的訂單
> 不見了，那桌會變成「訂單資料異常，請洽服務人員」，反而點不了餐。只要
> `activeSessionId` 有清回 null，怎麼刪都不會踩到。

`published/menu`、`settings` 底下那幾份與 `tables/table_a1` 這份文件本身不要刪，
不然步驟 12、13 的前置又要重建一次。踩到「送出得太頻繁了」的話，把
`tenants/store_test/rate_limits` 整個刪掉，計數就歸零（不刪的話過一分鐘也會自己重來）。

**手機要用無痕視窗重新掃。** 網頁會把上一張單存在瀏覽器裡（離線也看得到自己點了什麼），
同一個分頁再開會看到剛才那張「已送出」，看起來像沒刪成功。

驗完一樣把 `tenants/store_test` 整個刪掉。

---

# D. 換到店家的專案

這套設計最想保護的一件事：**換環境不用改任何程式或 workflow。**

1. 在**店家的** Google 帳號下，把步驟 1 到 9b 再做一次。
   支出上限與預算改設 US$20（NT$600），理由見步驟 3a 的警告。
   **WIF 是綁定專案的，不能共用，整套要重做。**
2. 步驟 10 建第二個 environment 叫 `prod`，填上店家專案的那三個 secrets。
3. 部署時 environment 選 `prod`。

`prod` 值得再加一層保險：在它的設定裡勾「Required reviewers」把自己加進去，
之後任何人按下部署到 prod 都要先核准一次。

SPEC 第十節〈搬到 prod 時需要人工處理的項目〉列了其他不會自動跟過去的東西——
App Check 的 SHA-256 指紋、Functions 的 secrets、預算警示與熔斷、第一組 owner 帳號——
搬家時要一起處理。WIF 這一套也是其中一項。

---

# 附錄：在本機跑

完全不是必要的，上面每一步都在瀏覽器裡做得完。但如果要在自己電腦上改東西或跑 emulator：

```bash
git clone https://github.com/Bryant-Tang/b-pos.git
cd b-pos
npm install -g firebase-tools@15     # 需要先裝 Node.js 22
firebase login
firebase use --add                   # 選專案，別名打 dev
```

然後 `firebase deploy --only firestore:rules,functions -P dev` 就能從本機部署。
跑 emulator 測試還需要 Java 21 以上。

`firebase use --add` 會生出一個 `.firebaserc`，裡面是真實的專案 ID。
它已經被 `.gitignore` 擋掉了，不會不小心推上這個公開的 repo——
但請確認它確實沒有被加進 commit。
