/**
 * 跟伺服器講話的兩件事：讀菜單、送出訂單。
 */

import { httpsCallable, type FunctionsError } from 'firebase/functions';
import { connect } from './firebase.js';
import type { Menu } from './menu.js';
import type { ItemRequest } from './cart.js';

export interface GuestOrderLine {
  lineId: string;
  name: string;
  qty: number;
  options: { name: string; priceDelta: number }[];
  subtotal: number;
}

/** createOrder 的回傳值，與 functions/src/orders/createGuestOrder.ts 的 GuestOrderView 對應。 */
export interface GuestOrder {
  orderId: string;
  sessionId: string;
  tableLabel: string;
  status: string;
  lines: GuestOrderLine[];
  subtotal: number;
  serviceCharge: number;
  discount: number;
  total: number;
}

/**
 * 讀菜單。
 *
 * SPEC 第二節要顧客優先讀 Hosting 上的靜態 `menu.json`（0 次 Firestore 讀取），
 * Firestore 文件當備援。`publishMenu` 還沒做，所以那份靜態檔還不存在——
 * 先讀 Firestore（Rules 對 `published/{doc}` 是 `read: if true`，不必登入），
 * 靜態檔一旦有了就會自動優先走那條，這裡不用再改。
 *
 * 一次下單只會讀一份文件，不是 listener，所以成本是一單一讀（SPEC 第九節）。
 */
export async function loadMenu(storeId: string): Promise<Menu> {
  try {
    const res = await fetch(`/menu-${storeId}.json`, { cache: 'no-cache' });
    if (res.ok) return (await res.json()) as Menu;
  } catch {
    // 還沒有靜態檔，或離線。往下走 Firestore。
  }

  // Firestore SDK 動態載入：它是整包 bundle 裡最大的一塊，而這條只是備援。
  // publishMenu 做好之後大多數客人會走上面那條靜態檔，就完全不用下載它。
  const [{ app }, { doc, getDoc, getFirestore }] = await Promise.all([
    connect(),
    import('firebase/firestore'),
  ]);
  const snap = await getDoc(doc(getFirestore(app), `tenants/${storeId}/published/menu`));
  if (!snap.exists()) {
    throw new Error('店家還沒有發佈菜單，請洽服務人員');
  }
  return snap.data() as Menu;
}

export interface CreateOrderRequest {
  storeId: string;
  tableToken: string;
  requestId: string;
  items: ItemRequest[];
}

/** 送出訂單時可能發生、而且客人看得懂的失敗。 */
export class OrderFailed extends Error {
  constructor(
    message: string,
    /** true 表示同一個 requestId 再送一次是有意義的（暫時性失敗） */
    readonly retryable: boolean,
  ) {
    super(message);
    this.name = 'OrderFailed';
  }
}

/**
 * 送出訂單。
 *
 * 請求裡只有品項 ID、數量與選項 ID，沒有任何金額——價格由伺服器從已發佈的菜單算
 * （CLAUDE.md 第二節第一條）。畫面上那個總額只是預估。
 */
export async function createOrder(request: CreateOrderRequest): Promise<GuestOrder> {
  const { functions } = await connect();
  const call = httpsCallable<CreateOrderRequest, GuestOrder>(functions, 'createOrder');
  try {
    return (await call(request)).data;
  } catch (err) {
    throw toOrderFailed(err);
  }
}

/**
 * 把 callable 的錯誤翻成客人看得懂的話。
 *
 * 伺服器對「客人該怎麼辦」的情況已經寫好中文訊息了（無效的 QR、本桌已結帳、
 * 菜單更新了），那些直接用。其餘的才是我們沒預期到的，給一句通則，
 * 並且明確告訴客人可以再試一次——因為那通常是網路，而重試是冪等的。
 */
function toOrderFailed(err: unknown): OrderFailed {
  const code = (err as FunctionsError | undefined)?.code;
  const message = (err as Error | undefined)?.message ?? '';

  switch (code) {
    case 'functions/permission-denied':
    case 'functions/failed-precondition':
    case 'functions/resource-exhausted':
      // 伺服器的訊息本來就是寫給客人看的，照用。
      return new OrderFailed(message, code === 'functions/resource-exhausted');
    case 'functions/unauthenticated':
      return new OrderFailed('連線過期了，請重新整理頁面', false);
    default:
      return new OrderFailed('送出失敗，請再試一次。如果一直失敗請洽服務人員', true);
  }
}
