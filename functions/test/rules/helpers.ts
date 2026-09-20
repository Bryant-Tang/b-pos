import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import {
  initializeTestEnvironment,
  type RulesTestEnvironment,
  type RulesTestContext,
} from '@firebase/rules-unit-testing';
import type { Firestore } from 'firebase/firestore';

/** 虛構的店家代號，只在測試裡出現，不對應任何真實店家。 */
export const STORE = 'store_demo';
/** 第二間虛構店家，用來驗證跨店隔離。 */
export const OTHER_STORE = 'store_other';

const RULES_PATH = fileURLToPath(new URL('../../../firestore.rules', import.meta.url));

/**
 * emulators:exec 會把 FIRESTORE_EMULATOR_HOST 設進環境變數，
 * 直接跑 vitest（沒有 emulator）時給一個明確的錯誤比連線逾時好讀。
 */
function emulatorHost(): { host: string; port: number } {
  const raw = process.env['FIRESTORE_EMULATOR_HOST'];
  if (!raw) {
    throw new Error(
      'FIRESTORE_EMULATOR_HOST 沒有設定。Rules 測試要用 npm run test:emulator 跑，' +
        '它會先把 Firestore emulator 起起來。',
    );
  }
  const [host, port] = raw.split(':');
  return { host: host ?? '127.0.0.1', port: Number(port ?? 8080) };
}

export async function makeTestEnv(): Promise<RulesTestEnvironment> {
  const { host, port } = emulatorHost();
  return initializeTestEnvironment({
    projectId: 'demo-b-pos',
    firestore: { rules: readFileSync(RULES_PATH, 'utf8'), host, port },
  });
}

type Claims = { storeId: string; role: 'owner' | 'staff' };

export const ownerOf = (storeId: string): Claims => ({ storeId, role: 'owner' });
export const staffOf = (storeId: string): Claims => ({ storeId, role: 'staff' });

/** 店員或老闆：帶 custom claims 的登入身分。 */
export function asStaff(env: RulesTestEnvironment, uid: string, claims: Claims): Firestore {
  return env.authenticatedContext(uid, claims).firestore() as unknown as Firestore;
}

/** 顧客：Anonymous Auth，有 uid 但沒有任何 claim。 */
export function asGuest(env: RulesTestEnvironment, uid = 'uid_guest'): Firestore {
  return env.authenticatedContext(uid, {}).firestore() as unknown as Firestore;
}

/** 完全沒登入，例如還沒拿到匿名 uid 的掃碼訪客。 */
export function asAnonymous(env: RulesTestEnvironment): Firestore {
  return env.unauthenticatedContext().firestore() as unknown as Firestore;
}

/** 繞過規則寫入測試資料（相當於伺服器端寫入）。 */
export async function seed(
  env: RulesTestEnvironment,
  write: (db: Firestore) => Promise<unknown>,
): Promise<void> {
  await env.withSecurityRulesDisabled(async (ctx: RulesTestContext) => {
    await write(ctx.firestore() as unknown as Firestore);
  });
}

export const path = {
  publishedMenu: `tenants/${STORE}/published/menu`,
  menuItem: (id: string) => `tenants/${STORE}/menu_items/${id}`,
  category: (id: string) => `tenants/${STORE}/categories/${id}`,
  optionGroup: (id: string) => `tenants/${STORE}/option_groups/${id}`,
  area: (id: string) => `tenants/${STORE}/areas/${id}`,
  table: (id: string) => `tenants/${STORE}/tables/${id}`,
  session: (id: string) => `tenants/${STORE}/sessions/${id}`,
  receipt: (id: string) => `tenants/${STORE}/receipts/${id}`,
  order: (id: string) => `tenants/${STORE}/orders/${id}`,
  orderIntent: (id: string) => `tenants/${STORE}/order_intents/${id}`,
  orderArchive: (id: string) => `tenants/${STORE}/orders_archive/${id}`,
  dailyReport: (id: string) => `tenants/${STORE}/daily_reports/${id}`,
  staffDoc: (uid: string) => `tenants/${STORE}/staff/${uid}`,
};
