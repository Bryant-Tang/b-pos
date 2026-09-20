import { afterAll, beforeAll, beforeEach, describe, expect, it } from 'vitest';
import { assertFails, assertSucceeds, type RulesTestEnvironment } from '@firebase/rules-unit-testing';
import {
  Timestamp,
  collection,
  deleteDoc,
  doc,
  getDoc,
  getDocs,
  setDoc,
  updateDoc,
} from 'firebase/firestore';
import {
  OTHER_STORE,
  STORE,
  asAnonymous,
  asGuest,
  asStaff,
  makeTestEnv,
  ownerOf,
  path,
  seed,
  staffOf,
} from './helpers.js';

let env: RulesTestEnvironment;

beforeAll(async () => {
  env = await makeTestEnv();
});

afterAll(async () => {
  await env.cleanup();
});

beforeEach(async () => {
  await env.clearFirestore();
});

const inFuture = () => Timestamp.fromMillis(Date.now() + 3 * 60 * 60 * 1000);
const inPast = () => Timestamp.fromMillis(Date.now() - 60 * 1000);

/** 一筆合法的下單意圖：只有品項 ID 與數量，沒有任何金額。 */
function validIntent(intentId: string, uid: string) {
  return {
    intentId,
    orderId: 'order_1',
    orderType: 'dine_in',
    tableId: 'table_1',
    lines: [{ itemId: 'item_beef_noodle', qty: 1, options: [] }],
    createdBy: uid,
    clientCreatedAt: Timestamp.now(),
  };
}

describe('published/menu：顧客要看得到菜單，但誰都不能寫', () => {
  beforeEach(async () => {
    await seed(env, (db) => setDoc(doc(db, path.publishedMenu), { version: 1, items: [] }));
  });

  it('沒登入也讀得到', async () => {
    await assertSucceeds(getDoc(doc(asAnonymous(env), path.publishedMenu)));
  });

  it('顧客不能寫', async () => {
    await assertFails(setDoc(doc(asGuest(env), path.publishedMenu), { version: 2 }));
  });

  it('老闆也不能直接寫，只能走 publishMenu', async () => {
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertFails(setDoc(doc(db, path.publishedMenu), { version: 2 }));
  });
});

describe('菜單編輯：只有老闆能改，而且不能硬刪', () => {
  beforeEach(async () => {
    await seed(env, (db) =>
      setDoc(doc(db, path.menuItem('item_beef_noodle')), {
        name: '牛肉麵',
        price: 180,
        categoryId: 'cat_noodle',
        archived: false,
        sort: 0,
      }),
    );
  });

  it('店員讀得到菜單', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertSucceeds(getDoc(doc(db, path.menuItem('item_beef_noodle'))));
  });

  it('店員改價格被擋', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertFails(updateDoc(doc(db, path.menuItem('item_beef_noodle')), { price: 10 }));
  });

  it('老闆改價格可以', async () => {
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertSucceeds(updateDoc(doc(db, path.menuItem('item_beef_noodle')), { price: 200 }));
  });

  it('老闆也不能硬刪品項，只能 archived', async () => {
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertFails(deleteDoc(doc(db, path.menuItem('item_beef_noodle'))));
    await assertSucceeds(updateDoc(doc(db, path.menuItem('item_beef_noodle')), { archived: true }));
  });

  it('分類與選項群組同樣不能硬刪', async () => {
    await seed(env, async (db) => {
      await setDoc(doc(db, path.category('cat_noodle')), { name: '麵食', sort: 0, archived: false });
      await setDoc(doc(db, path.optionGroup('grp_spicy')), {
        name: '辣度',
        type: 'single',
        min: 0,
        max: 1,
        options: [],
        archived: false,
      });
    });
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertFails(deleteDoc(doc(db, path.category('cat_noodle'))));
    await assertFails(deleteDoc(doc(db, path.optionGroup('grp_spicy'))));
  });

  it('顧客連讀都不行', async () => {
    await assertFails(getDoc(doc(asGuest(env), path.menuItem('item_beef_noodle'))));
  });
});

describe('tables：qrToken 只能由 createTable 產生', () => {
  beforeEach(async () => {
    await seed(env, (db) =>
      setDoc(doc(db, path.table('table_1')), {
        areaId: 'area_1f',
        label: '窗邊',
        sort: 0,
        seats: 4,
        x: 0.2,
        y: 0.3,
        shape: 'square',
        qrToken: 'token_placeholder',
        archived: false,
      }),
    );
  });

  it('老闆拖曳桌位（改 x/y/sort）可以', async () => {
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertSucceeds(updateDoc(doc(db, path.table('table_1')), { x: 0.5, y: 0.6, sort: 3 }));
  });

  it('老闆改桌名可以', async () => {
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertSucceeds(updateDoc(doc(db, path.table('table_1')), { label: '吧台3' }));
  });

  it('老闆改 qrToken 被擋', async () => {
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertFails(updateDoc(doc(db, path.table('table_1')), { qrToken: 'token_hijack' }));
  });

  it('店員不能改桌位', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertFails(updateDoc(doc(db, path.table('table_1')), { label: '窗邊2' }));
  });

  it('誰都不能直接建桌或刪桌', async () => {
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertFails(setDoc(doc(db, path.table('table_2')), { label: '新桌' }));
    await assertFails(deleteDoc(doc(db, path.table('table_1'))));
  });
});

describe('sessions：sessionId 就是憑證，但不可列舉', () => {
  beforeEach(async () => {
    await seed(env, (db) =>
      setDoc(doc(db, path.session('sess_aaa')), {
        tableId: 'table_1',
        status: 'active',
        orderId: 'order_1',
      }),
    );
  });

  it('知道 sessionId 就讀得到', async () => {
    await assertSucceeds(getDoc(doc(asGuest(env), path.session('sess_aaa'))));
  });

  it('顧客不能列舉整個 collection', async () => {
    await assertFails(getDocs(collection(asGuest(env), `tenants/${STORE}/sessions`)));
  });

  it('店員可以列舉', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertSucceeds(getDocs(collection(db, `tenants/${STORE}/sessions`)));
  });

  it('誰都不能寫', async () => {
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertFails(updateDoc(doc(db, path.session('sess_aaa')), { status: 'closed' }));
    await assertFails(updateDoc(doc(asGuest(env), path.session('sess_aaa')), { status: 'closed' }));
  });
});

describe('receipts：過期就讀不到', () => {
  beforeEach(async () => {
    await seed(env, async (db) => {
      await setDoc(doc(db, path.receipt('sess_fresh')), {
        lookupCode: '1234',
        tableLabel: '窗邊',
        total: 480,
        expiresAt: inFuture(),
      });
      await setDoc(doc(db, path.receipt('sess_stale')), {
        lookupCode: '5678',
        tableLabel: '窗邊',
        total: 300,
        expiresAt: inPast(),
      });
    });
  });

  it('未過期的帳單顧客讀得到', async () => {
    await assertSucceeds(getDoc(doc(asGuest(env), path.receipt('sess_fresh'))));
  });

  it('過期的帳單顧客讀不到', async () => {
    await assertFails(getDoc(doc(asGuest(env), path.receipt('sess_stale'))));
  });

  it('店員不受過期限制', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertSucceeds(getDoc(doc(db, path.receipt('sess_stale'))));
  });

  it('顧客不能列舉', async () => {
    await assertFails(getDocs(collection(asGuest(env), `tenants/${STORE}/receipts`)));
  });

  it('誰都不能寫', async () => {
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertFails(updateDoc(doc(db, path.receipt('sess_fresh')), { total: 1 }));
  });
});

describe('orders：客戶端完全唯讀（見 docs/decisions/0002）', () => {
  beforeEach(async () => {
    await seed(env, (db) =>
      setDoc(doc(db, path.order('order_1')), {
        orderType: 'dine_in',
        status: 'open',
        tableIds: ['table_1'],
        lines: [],
        subtotal: 480,
        serviceCharge: 0,
        discount: 0,
        total: 480,
        businessDate: '2026-09-20',
      }),
    );
  });

  it('店員讀得到單筆與列表', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertSucceeds(getDoc(doc(db, path.order('order_1'))));
    await assertSucceeds(getDocs(collection(db, `tenants/${STORE}/orders`)));
  });

  it('顧客不能讀、不能列舉、不能寫', async () => {
    const db = asGuest(env);
    await assertFails(getDoc(doc(db, path.order('order_1'))));
    await assertFails(getDocs(collection(db, `tenants/${STORE}/orders`)));
    await assertFails(setDoc(doc(db, path.order('order_evil')), { total: 0 }));
  });

  it('店員不能改 total', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertFails(updateDoc(doc(db, path.order('order_1')), { total: 0 }));
  });

  it('店員不能在總額不變的情況下把單標成已結帳', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertFails(updateDoc(doc(db, path.order('order_1')), { status: 'closed' }));
  });

  it('店員不能改 businessDate 把營收搬到別天', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertFails(updateDoc(doc(db, path.order('order_1')), { businessDate: '2026-09-19' }));
  });

  it('老闆一樣不能直接改單', async () => {
    const db = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertFails(updateDoc(doc(db, path.order('order_1')), { discount: 100 }));
  });

  it('誰都不能建單或刪單', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertFails(setDoc(doc(db, path.order('order_2')), { total: 0 }));
    await assertFails(deleteDoc(doc(db, path.order('order_1'))));
  });
});

describe('order_intents：店員端唯一的寫入點', () => {
  const uid = 'uid_staff';
  const db = () => asStaff(env, uid, staffOf(STORE));

  it('合法的意圖寫得進去', async () => {
    await assertSucceeds(
      setDoc(doc(db(), path.orderIntent('intent_1')), validIntent('intent_1', uid)),
    );
  });

  it('外帶單的 tableId 可以是 null', async () => {
    const intent = { ...validIntent('intent_2', uid), orderType: 'takeout', tableId: null };
    await assertSucceeds(setDoc(doc(db(), path.orderIntent('intent_2')), intent));
  });

  it('多塞一個金額欄位就被擋', async () => {
    const intent = { ...validIntent('intent_3', uid), total: 0 };
    await assertFails(setDoc(doc(db(), path.orderIntent('intent_3')), intent));
  });

  it('少一個必要欄位也被擋', async () => {
    const { lines: _lines, ...withoutLines } = validIntent('intent_4', uid);
    await assertFails(setDoc(doc(db(), path.orderIntent('intent_4')), withoutLines));
  });

  it('intentId 與文件 id 不一致被擋（冪等鍵不可造假）', async () => {
    await assertFails(
      setDoc(doc(db(), path.orderIntent('intent_5')), validIntent('intent_other', uid)),
    );
  });

  it('createdBy 冒用別人的 uid 被擋', async () => {
    await assertFails(
      setDoc(doc(db(), path.orderIntent('intent_6')), validIntent('intent_6', 'uid_someone_else')),
    );
  });

  it('空的 lines 被擋', async () => {
    const intent = { ...validIntent('intent_7', uid), lines: [] };
    await assertFails(setDoc(doc(db(), path.orderIntent('intent_7')), intent));
  });

  it('不認得的 orderType 被擋', async () => {
    const intent = { ...validIntent('intent_8', uid), orderType: 'free_meal' };
    await assertFails(setDoc(doc(db(), path.orderIntent('intent_8')), intent));
  });

  it('顧客不能寫意圖', async () => {
    const guest = asGuest(env);
    await assertFails(
      setDoc(doc(guest, path.orderIntent('intent_9')), validIntent('intent_9', 'uid_guest')),
    );
  });

  it('送出後不可改也不可刪', async () => {
    await seed(env, (adminDb) =>
      setDoc(doc(adminDb, path.orderIntent('intent_10')), validIntent('intent_10', uid)),
    );
    await assertFails(updateDoc(doc(db(), path.orderIntent('intent_10')), { orderType: 'takeout' }));
    await assertFails(deleteDoc(doc(db(), path.orderIntent('intent_10'))));
  });
});

describe('唯讀資料：archive、報表、員工', () => {
  beforeEach(async () => {
    await seed(env, async (db) => {
      await setDoc(doc(db, path.orderArchive('order_old')), { total: 480, businessDate: '2026-09-19' });
      await setDoc(doc(db, path.dailyReport('2026-09-19')), { revenue: 12000 });
      await setDoc(doc(db, path.staffDoc('uid_staff')), {
        displayName: '王小姐',
        role: 'staff',
        active: true,
      });
    });
  });

  it('店員讀得到 archive，但寫不進去', async () => {
    const db = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertSucceeds(getDoc(doc(db, path.orderArchive('order_old'))));
    await assertFails(updateDoc(doc(db, path.orderArchive('order_old')), { total: 0 }));
  });

  it('日報只有老闆讀得到', async () => {
    const owner = asStaff(env, 'uid_owner', ownerOf(STORE));
    const staff = asStaff(env, 'uid_staff', staffOf(STORE));
    await assertSucceeds(getDoc(doc(owner, path.dailyReport('2026-09-19'))));
    await assertFails(getDoc(doc(staff, path.dailyReport('2026-09-19'))));
  });

  it('員工名冊只能由 setStaffRole 寫', async () => {
    const owner = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertSucceeds(getDoc(doc(owner, path.staffDoc('uid_staff'))));
    await assertFails(updateDoc(doc(owner, path.staffDoc('uid_staff')), { role: 'owner' }));
  });
});

describe('跨店隔離：claim 的 storeId 必須等於路徑上的 storeId', () => {
  beforeEach(async () => {
    await seed(env, async (db) => {
      await setDoc(doc(db, path.order('order_1')), { total: 480, status: 'open' });
      await setDoc(doc(db, path.menuItem('item_beef_noodle')), { name: '牛肉麵', price: 180 });
    });
  });

  it('別家店的老闆讀不到這家店的訂單與菜單', async () => {
    const other = asStaff(env, 'uid_other_owner', ownerOf(OTHER_STORE));
    await assertFails(getDoc(doc(other, path.order('order_1'))));
    await assertFails(getDocs(collection(other, `tenants/${STORE}/orders`)));
    await assertFails(getDoc(doc(other, path.menuItem('item_beef_noodle'))));
  });

  it('別家店的老闆也寫不進這家店的意圖', async () => {
    const other = asStaff(env, 'uid_other_owner', ownerOf(OTHER_STORE));
    await assertFails(
      setDoc(doc(other, path.orderIntent('intent_x')), validIntent('intent_x', 'uid_other_owner')),
    );
  });

  it('沒有 claim 的匿名顧客一樣讀不到', async () => {
    await assertFails(getDoc(doc(asGuest(env), path.order('order_1'))));
  });
});

describe('預設全關', () => {
  it('沒有規則的 collection 讀不到也寫不了', async () => {
    await seed(env, (db) => setDoc(doc(db, `tenants/${STORE}/secret_stuff/x`), { a: 1 }));
    const owner = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertFails(getDoc(doc(owner, `tenants/${STORE}/secret_stuff/x`)));
    await assertFails(setDoc(doc(owner, `tenants/${STORE}/secret_stuff/y`), { a: 1 }));
  });

  it('租戶文件本身讀不到', async () => {
    await seed(env, (db) => setDoc(doc(db, `tenants/${STORE}`), { name: '範例餐廳' }));
    const owner = asStaff(env, 'uid_owner', ownerOf(STORE));
    await assertFails(getDoc(doc(owner, `tenants/${STORE}`)));
  });

  it('根目錄的 collection 讀不到', async () => {
    await seed(env, (db) => setDoc(doc(db, 'anything/x'), { a: 1 }));
    await assertFails(getDoc(doc(asGuest(env), 'anything/x')));
  });
});
