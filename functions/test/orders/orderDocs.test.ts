import { describe, expect, it } from 'vitest';
import { Timestamp } from 'firebase-admin/firestore';
import { readStoredLines, toPricingLine, toStoredLine } from '../../src/orders/orderDocs.js';
import type { OrderLine } from '../../src/orders/pricing.js';

const PRINTED = new Date('2026-09-20T19:05:00Z');
const VOIDED = new Date('2026-09-20T19:20:00Z');

function line(over: Partial<OrderLine> = {}): OrderLine {
  return {
    lineId: 'line_1',
    itemId: 'item_beef_noodle',
    name: '牛肉麵',
    unitPriceDineIn: 180,
    unitPriceTakeout: 180,
    taxMode: 'taxable',
    qty: 1,
    options: [],
    subtotal: 180,
    printedAt: null,
    voidedAt: null,
    voidReason: null,
    ...over,
  };
}

describe('訂單行的存讀轉換', () => {
  it('日期存成 Timestamp', () => {
    const stored = toStoredLine(line({ printedAt: PRINTED, voidedAt: VOIDED }));
    expect(stored.printedAt).toBeInstanceOf(Timestamp);
    expect(stored.voidedAt).toBeInstanceOf(Timestamp);
  });

  it('null 保持 null', () => {
    const stored = toStoredLine(line());
    expect(stored.printedAt).toBeNull();
    expect(stored.voidedAt).toBeNull();
  });

  /**
   * 這條是整支退點功能站得住的前提。
   *
   * 加點走的是「讀出整個 lines、加新的、整個寫回去」（createGuestOrder、applyOrderIntent），
   * 也就是每加點一次，既有的每一行都會跑一次這個來回。只要來回時掉了 printedAt，
   * 客人一加點，先前已經送進廚房的行就會變回「還沒印」，而退點看的正是這個欄位——
   * 結果是已經在做的菜被當成點錯的直接從帳上刪掉。
   */
  it('printedAt 與作廢原因經過一次存讀不會掉', () => {
    const original = line({ printedAt: PRINTED, voidedAt: VOIDED, voidReason: '上錯桌' });
    const round = toPricingLine(toStoredLine(original));
    expect(round).toEqual(original);
  });

  it('多來回幾次也一樣', () => {
    const original = line({ printedAt: PRINTED });
    let round = original;
    for (let i = 0; i < 3; i += 1) round = toPricingLine(toStoredLine(round));
    expect(round).toEqual(original);
  });

  // printedAt 是後來才加的欄位，在它之前建的單根本沒有這個 key。
  // 直接 .toDate() 的話，一張營業中的舊單會整張讀不出來。
  it('舊資料缺欄位時讀成 null，不會爆炸', () => {
    const legacy = { ...toStoredLine(line()) } as Record<string, unknown>;
    delete legacy['printedAt'];
    delete legacy['voidReason'];
    const read = readStoredLines({ lines: [legacy] });
    expect(read[0]?.printedAt).toBeNull();
    expect(read[0]?.voidReason).toBeNull();
  });

  it('欄位型別不對也讀成 null', () => {
    const broken = { ...toStoredLine(line()), printedAt: '2026-09-20', voidReason: 7 };
    const read = readStoredLines({ lines: [broken] });
    expect(read[0]?.printedAt).toBeNull();
    expect(read[0]?.voidReason).toBeNull();
  });

  it('沒有 lines 或形狀不對時回空陣列', () => {
    expect(readStoredLines({})).toEqual([]);
    expect(readStoredLines({ lines: 'nope' })).toEqual([]);
  });
});
