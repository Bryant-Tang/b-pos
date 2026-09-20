/**
 * 算價純函式。
 *
 * 這裡的每一支都不碰 Firestore：輸入是菜單快照與品項請求，輸出是訂單行與金額。
 * 這樣本機毫秒級就能跑完上百個 case（見 docs/SPEC.md 第十二節）。
 *
 * 金額一律是整數「元」。除法只出現在均分與稅額回推，兩處都明確指定餘數歸屬，
 * 不使用浮點數累加。
 */

import { randomUUID } from 'node:crypto';

export type TaxMode = 'taxable' | 'exempt' | 'zero';
export type OrderType = 'dine_in' | 'takeout' | 'waitlist';

export interface MenuOption {
  id: string;
  name: string;
  /** 可為 0 或負數（例如「不要肉」折 10 元） */
  priceDelta: number;
}

export interface MenuOptionGroup {
  id: string;
  name: string;
  type: 'single' | 'multi';
  min: number;
  max: number;
  options: MenuOption[];
}

export interface MenuItem {
  id: string;
  name: string;
  /** 內用含稅價，整數元 */
  price: number;
  /** 外帶價；未填則同 price */
  takeoutPrice?: number;
  taxMode: TaxMode;
  optionGroupIds: string[];
}

export interface MenuSnapshot {
  items: MenuItem[];
  optionGroups: MenuOptionGroup[];
}

export interface ItemRequest {
  itemId: string;
  qty: number;
  options: { groupId: string; optionId: string }[];
}

export interface OrderLineOption {
  groupId: string;
  optionId: string;
  /** 快照：改了菜單也不影響已下的單 */
  name: string;
  priceDelta: number;
}

export interface OrderLine {
  lineId: string;
  /** 保留供品項維度的統計關聯 */
  itemId: string;
  name: string;
  unitPriceDineIn: number;
  unitPriceTakeout: number;
  taxMode: TaxMode;
  qty: number;
  options: OrderLineOption[];
  /** 依訂單當下的 orderType 算出的小計，轉換型態時由 repriceLines 重算 */
  subtotal: number;
  voidedAt: Date | null;
}

export interface PricingSettings {
  /** 內用服務費比例，0 表示不收。目前店家為 0（見 docs/SPEC.md 第十五節） */
  dineInServiceCharge: number;
}

export interface TaxBucket {
  /** 該稅別的含稅金額合計 */
  amount: number;
  /** 由含稅金額回推的稅額 */
  tax: number;
}

export interface TaxSummary {
  taxable: TaxBucket;
  exempt: TaxBucket;
  zero: TaxBucket;
}

export interface OrderTotals {
  subtotal: number;
  serviceCharge: number;
  discount: number;
  total: number;
  /**
   * 只涵蓋訂單行的金額，不含 serviceCharge。
   * 服務費的稅別歸屬尚未與店家確認，等確認後再決定要不要併進來。
   */
  taxSummary: TaxSummary;
}

/** 台灣營業稅率，用於由含稅價回推稅額 */
const TAX_RATE = 0.05;

export class PricingError extends Error {
  constructor(
    message: string,
    readonly code:
      | 'unknown_item'
      | 'unknown_option'
      | 'invalid_qty'
      | 'invalid_discount'
      | 'invalid_options',
  ) {
    super(message);
    this.name = 'PricingError';
  }
}

/**
 * 該訂單型態要用哪一個單價。
 *
 * `waitlist` 用內用價：候位單綁桌後會轉成 `dine_in`，在那之前以內用計。
 * 客人若改為外帶，走型態轉換流程重算（見 docs/SPEC.md 第十五節）。
 */
export function unitPriceFor(line: OrderLine, orderType: OrderType): number {
  return orderType === 'takeout' ? line.unitPriceTakeout : line.unitPriceDineIn;
}

/**
 * 單行小計 = （單價 + 所有選項加價）× 數量。作廢的行一律 0。
 *
 * 夾在 0 以上：同一個 multi 群組可以合法地掛兩個以上的負 `priceDelta` 選項
 * （例如「不要肉 -20」與「不要蛋 -15」），各選一次就可能把單價壓成負數，
 * 那一行就會去抵銷同一張單其他品項的金額——30 元的小菜選掉兩項會變成 -5，
 * 三份再加一碗 180 的麵，整張單只剩 165。
 *
 * 這不是顧客送得動的攻擊面（`priceDelta` 是老闆在後台設定的），而是設定錯誤，
 * 所以這裡只做防呆下限，不拋錯——營業中不該因為菜單設定而點不了餐。
 * 真正該擋的地方是 `publishMenu` 的 `validateMenu`：發佈前就檢查每個品項在
 * 最壞情況下的選項組合不會把單價壓到負數，讓老闆在後台當下就看到問題。
 */
export function lineSubtotal(line: OrderLine, orderType: OrderType): number {
  if (line.voidedAt !== null) return 0;
  const delta = line.options.reduce((sum, o) => sum + o.priceDelta, 0);
  return Math.max(0, unitPriceFor(line, orderType) + delta) * line.qty;
}

/**
 * 把品項請求換算成訂單行。
 *
 * 價格「只」從菜單快照來；呼叫端送進來的任何金額欄位都不會被讀取。
 *
 * `lineId` 預設用 UUID。不要改回 `line_${index}` 這種依序號產生的值——加點時新的 lines
 * 會附加到既有訂單上，序號會跟既有的 lineId 撞號，而分單與退點都是靠 lineId 指定目標。
 */
export function calcOrderLines(
  menu: MenuSnapshot,
  items: ItemRequest[],
  orderType: OrderType,
  makeLineId: (index: number) => string = () => randomUUID(),
): OrderLine[] {
  const itemsById = new Map(menu.items.map((i) => [i.id, i]));
  const groupsById = new Map(menu.optionGroups.map((g) => [g.id, g]));

  return items.map((req, index) => {
    if (!Number.isInteger(req.qty) || req.qty < 1) {
      throw new PricingError(`數量必須是正整數：${req.qty}`, 'invalid_qty');
    }

    const item = itemsById.get(req.itemId);
    if (!item) throw new PricingError(`找不到品項 ${req.itemId}`, 'unknown_item');

    const allowedGroups = new Set(item.optionGroupIds);
    const seen = new Set<string>();
    const countByGroup = new Map<string, number>();

    const options = req.options.map((sel) => {
      const group = groupsById.get(sel.groupId);
      const option = group?.options.find((o) => o.id === sel.optionId);
      if (!group || !option) {
        throw new PricingError(`找不到選項 ${sel.groupId}/${sel.optionId}`, 'unknown_option');
      }

      // 群組必須真的掛在這個品項上，否則等於讓呼叫端把別的品項的折價選項搬過來。
      if (!allowedGroups.has(group.id)) {
        throw new PricingError(
          `品項 ${item.id} 沒有選項群組 ${group.id}`,
          'invalid_options',
        );
      }

      // 同一個選項不得重複。少了這條，呼叫端只要把負數 priceDelta 的選項送個十次，
      // 就能把單價壓成負數，等於繞過「客戶端永遠不送金額」。
      const key = `${group.id}/${option.id}`;
      if (seen.has(key)) {
        throw new PricingError(`選項 ${key} 重複`, 'invalid_options');
      }
      seen.add(key);

      const count = (countByGroup.get(group.id) ?? 0) + 1;
      countByGroup.set(group.id, count);
      const max = group.type === 'single' ? 1 : group.max;
      if (count > max) {
        throw new PricingError(
          `選項群組 ${group.id} 最多只能選 ${max} 項`,
          'invalid_options',
        );
      }

      return {
        groupId: group.id,
        optionId: option.id,
        name: option.name,
        priceDelta: option.priceDelta,
      };
    });

    // 必選群組不得從缺。
    for (const groupId of item.optionGroupIds) {
      const group = groupsById.get(groupId);
      if (!group) continue;
      const count = countByGroup.get(groupId) ?? 0;
      if (count < group.min) {
        throw new PricingError(
          `選項群組 ${groupId} 至少要選 ${group.min} 項`,
          'invalid_options',
        );
      }
    }

    const line: OrderLine = {
      lineId: makeLineId(index),
      itemId: item.id,
      name: item.name,
      unitPriceDineIn: item.price,
      unitPriceTakeout: item.takeoutPrice ?? item.price,
      taxMode: item.taxMode,
      qty: req.qty,
      options,
      subtotal: 0,
      voidedAt: null,
    };
    line.subtotal = lineSubtotal(line, orderType);
    return line;
  });
}

/**
 * 轉換訂單型態時重算小計。
 *
 * 只切換用哪一個已快照的單價，不重查菜單——否則期間店家調了價，
 * 轉換後金額會莫名其妙變動（見 docs/SPEC.md 第十五節）。
 */
export function repriceLines(lines: OrderLine[], orderType: OrderType): OrderLine[] {
  return lines.map((line) => ({ ...line, subtotal: lineSubtotal(line, orderType) }));
}

/** 由含稅金額回推稅額：tax = amount − round(amount ÷ 1.05) */
export function taxFromInclusive(amount: number): number {
  return amount - Math.round(amount / (1 + TAX_RATE));
}

function emptyTaxSummary(): TaxSummary {
  return {
    taxable: { amount: 0, tax: 0 },
    exempt: { amount: 0, tax: 0 },
    zero: { amount: 0, tax: 0 },
  };
}

/** 依稅別分組彙總。`exempt` 與 `zero` 的稅額恆為 0。 */
export function calcTaxSummary(lines: OrderLine[], orderType: OrderType): TaxSummary {
  const summary = emptyTaxSummary();
  for (const line of lines) {
    if (line.voidedAt !== null) continue;
    summary[line.taxMode].amount += lineSubtotal(line, orderType);
  }
  summary.taxable.tax = taxFromInclusive(summary.taxable.amount);
  return summary;
}

/**
 * 訂單總額。
 *
 * 服務費只對內用收取，且是對小計四捨五入後的整數元。
 * 折扣後總額不得為負。
 */
export function calcOrderTotal(
  lines: OrderLine[],
  orderType: OrderType,
  settings: PricingSettings,
  discount = 0,
): OrderTotals {
  if (!Number.isInteger(discount) || discount < 0) {
    throw new PricingError(`折扣必須是非負整數：${discount}`, 'invalid_discount');
  }

  const subtotal = lines.reduce((sum, line) => sum + lineSubtotal(line, orderType), 0);
  const serviceCharge =
    orderType === 'dine_in' ? Math.round(subtotal * settings.dineInServiceCharge) : 0;

  return {
    subtotal,
    serviceCharge,
    discount,
    total: Math.max(0, subtotal + serviceCharge - discount),
    taxSummary: calcTaxSummary(lines, orderType),
  };
}

/**
 * 均分金額，餘數歸第一張單，全程整數運算。
 *
 * 100 元分 3 人 = [34, 33, 33]。
 */
export function splitEvenly(total: number, ways: number): number[] {
  if (!Number.isInteger(ways) || ways < 1) {
    throw new PricingError(`份數必須是正整數：${ways}`, 'invalid_qty');
  }
  const base = Math.floor(total / ways);
  const remainder = total - base * ways;
  return Array.from({ length: ways }, (_, i) => (i === 0 ? base + remainder : base));
}

/**
 * 按品項拆單：把指定的 lines 移到新單。
 *
 * 回傳的兩組 lines 合起來必須等於原本那一組，不多也不少。
 */
export function splitByLineIds(
  lines: OrderLine[],
  lineIds: readonly string[],
): { taken: OrderLine[]; remaining: OrderLine[] } {
  const wanted = new Set(lineIds);
  const taken: OrderLine[] = [];
  const remaining: OrderLine[] = [];
  for (const line of lines) {
    (wanted.has(line.lineId) ? taken : remaining).push(line);
  }
  return { taken, remaining };
}
