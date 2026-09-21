import { deleteApp, initializeApp, type App } from 'firebase-admin/app';
import { getFirestore, type Firestore } from 'firebase-admin/firestore';

/** 虛構的店家代號，只在測試裡出現，不對應任何真實店家。 */
export const STORE = 'store_demo';
export const PROJECT_ID = 'demo-b-pos';

function emulatorHost(): string {
  const raw = process.env['FIRESTORE_EMULATOR_HOST'];
  if (!raw) {
    throw new Error(
      'FIRESTORE_EMULATOR_HOST 沒有設定。這些測試要用 npm run test:emulator 跑，' +
        '它會先把 Firestore emulator 起起來。',
    );
  }
  return raw;
}

let app: App | undefined;

/**
 * admin SDK 會自己認 FIRESTORE_EMULATOR_HOST，所以這裡只要給一個 projectId。
 * admin SDK 本來就繞過 Security Rules——這些測試驗的是觸發器的邏輯，
 * 權限那一層由 test/rules/ 負責。
 *
 * 沒有 GCP metadata server 的地方（CI runner、開發機）會看到一行
 * MetadataLookupWarning 403：那是 SDK 去找 application default credentials 的結果，
 * 連得上 emulator 就不影響，不要為了消掉它在 repo 裡放假的服務帳號金鑰。
 */
export function adminDb(): Firestore {
  emulatorHost();
  app ??= initializeApp({ projectId: PROJECT_ID }, 'integration');
  return getFirestore(app);
}

export async function disposeAdminDb(): Promise<void> {
  if (app) {
    await deleteApp(app);
    app = undefined;
  }
}

/** 清空 emulator 裡的所有資料。emulator 專用的端點，正式環境沒有。 */
export async function clearFirestore(): Promise<void> {
  const res = await fetch(
    `http://${emulatorHost()}/emulator/v1/projects/${PROJECT_ID}/databases/(default)/documents`,
    { method: 'DELETE' },
  );
  if (!res.ok) {
    throw new Error(`清空 emulator 失敗：${res.status} ${await res.text()}`);
  }
}

export const path = {
  publishedMenu: `tenants/${STORE}/published/menu`,
  pricingSettings: `tenants/${STORE}/settings/pricing`,
  businessSettings: `tenants/${STORE}/settings/business`,
  table: (id: string) => `tenants/${STORE}/tables/${id}`,
  session: (id: string) => `tenants/${STORE}/sessions/${id}`,
  rateLimit: (uid: string) => `tenants/${STORE}/rate_limits/${uid}`,
  order: (id: string) => `tenants/${STORE}/orders/${id}`,
  orderIntent: (id: string) => `tenants/${STORE}/order_intents/${id}`,
  counter: (businessDate: string) => `tenants/${STORE}/counters/${businessDate}`,
};

/**
 * 虛構菜單（見 CLAUDE.md）。牛肉麵 180、珍珠奶茶 60（外帶 55）、
 * 白飯 30（可加不要肉 −20、不要蛋 −15，用來驗負數選項的邊界）。
 */
export const MENU = {
  version: 1_758_000_000_000,
  items: [
    {
      id: 'item_beef_noodle',
      name: '牛肉麵',
      price: 180,
      takeoutPrice: 180,
      taxMode: 'taxable',
      optionGroupIds: ['grp_spicy'],
    },
    {
      id: 'item_bubble_tea',
      name: '珍珠奶茶',
      price: 60,
      takeoutPrice: 55,
      taxMode: 'taxable',
      optionGroupIds: [],
    },
    {
      id: 'item_rice',
      name: '白飯',
      price: 30,
      takeoutPrice: 30,
      taxMode: 'taxable',
      optionGroupIds: ['grp_remove'],
    },
  ],
  optionGroups: [
    {
      id: 'grp_spicy',
      name: '辣度',
      type: 'single',
      min: 0,
      max: 1,
      options: [
        { id: 'opt_mild', name: '小辣', priceDelta: 0 },
        { id: 'opt_extra', name: '加辣加價', priceDelta: 10 },
      ],
    },
    {
      id: 'grp_remove',
      name: '減料',
      type: 'multi',
      min: 0,
      max: 2,
      options: [
        { id: 'opt_no_meat', name: '不要肉', priceDelta: -20 },
        { id: 'opt_no_egg', name: '不要蛋', priceDelta: -15 },
      ],
    },
  ],
};

/**
 * 虛構的桌位 token。形狀與 createTable 產生的一樣（32 碼十六進位），
 * 但這串是隨手編的，不對應任何真實桌位（見 CLAUDE.md 第一節）。
 */
export const TABLE_TOKEN = '0123456789abcdef0123456789abcdef';

export const TABLE = {
  areaId: 'area_1f',
  label: '窗邊',
  sort: 0,
  seats: 4,
  x: 0.2,
  y: 0.3,
  shape: 'square',
  qrToken: TABLE_TOKEN,
  archived: false,
};
