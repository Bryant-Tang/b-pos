import { describe, expect, it } from 'vitest';
import {
  calcOrderLines,
  calcOrderTotal,
  calcTaxSummary,
  lineSubtotal,
  PricingError,
  repriceLines,
  splitByLineIds,
  splitEvenly,
  taxFromInclusive,
  type MenuSnapshot,
  type OrderLine,
  type PricingSettings,
} from '../src/pricing.js';

// 全部是虛構資料（見 CLAUDE.md）
const MENU: MenuSnapshot = {
  items: [
    {
      id: 'beef_noodle',
      name: '牛肉麵',
      price: 180,
      takeoutPrice: 170,
      taxMode: 'taxable',
      optionGroupIds: ['spice', 'extras'],
    },
    { id: 'tea', name: '珍珠奶茶', price: 60, taxMode: 'taxable', optionGroupIds: ['extras'] },
    { id: 'rice_gift', name: '米禮盒', price: 500, taxMode: 'exempt', optionGroupIds: [] },
    { id: 'export_box', name: '外銷禮盒', price: 300, taxMode: 'zero', optionGroupIds: [] },
  ],
  optionGroups: [
    {
      id: 'spice',
      name: '辣度',
      type: 'single',
      min: 0,
      max: 1,
      options: [
        { id: 'mild', name: '小辣', priceDelta: 0 },
        { id: 'extra_hot', name: '大辣', priceDelta: 10 },
      ],
    },
    {
      id: 'extras',
      name: '加料',
      type: 'multi',
      min: 0,
      max: 3,
      options: [
        { id: 'egg', name: '加蛋', priceDelta: 15 },
        { id: 'no_meat', name: '不要肉', priceDelta: -20 },
      ],
    },
  ],
};

const NO_SERVICE_CHARGE: PricingSettings = { dineInServiceCharge: 0 };
const TEN_PERCENT: PricingSettings = { dineInServiceCharge: 0.1 };

const req = (itemId: string, qty: number, options: [string, string][] = []) => ({
  itemId,
  qty,
  options: options.map(([groupId, optionId]) => ({ groupId, optionId })),
});

describe('calcOrderLines', () => {
  it('單品 × 數量', () => {
    const [line] = calcOrderLines(MENU, [req('beef_noodle', 3)], 'dine_in');
    expect(line?.subtotal).toBe(540);
    expect(line?.itemId).toBe('beef_noodle');
  });

  it('含多個選項加價', () => {
    const [line] = calcOrderLines(
      MENU,
      [req('beef_noodle', 2, [['spice', 'extra_hot'], ['extras', 'egg']])],
      'dine_in',
    );
    // (180 + 10 + 15) × 2
    expect(line?.subtotal).toBe(410);
    expect(line?.options).toHaveLength(2);
  });

  it('priceDelta 為負數', () => {
    const [line] = calcOrderLines(MENU, [req('beef_noodle', 1, [['extras', 'no_meat']])], 'dine_in');
    expect(line?.subtotal).toBe(160);
  });

  it('把名稱與單價快照進 line，之後改菜單不影響已下的單', () => {
    const [line] = calcOrderLines(MENU, [req('beef_noodle', 1)], 'dine_in');
    expect(line).toMatchObject({
      name: '牛肉麵',
      unitPriceDineIn: 180,
      unitPriceTakeout: 170,
      taxMode: 'taxable',
    });
  });

  it('外帶價未填時等同內用價', () => {
    const [line] = calcOrderLines(MENU, [req('tea', 1)], 'takeout');
    expect(line?.unitPriceTakeout).toBe(60);
    expect(line?.subtotal).toBe(60);
  });

  it('候位單以內用價計', () => {
    const [line] = calcOrderLines(MENU, [req('beef_noodle', 1)], 'waitlist');
    expect(line?.subtotal).toBe(180);
  });

  it('拒絕未知品項與未知選項', () => {
    expect(() => calcOrderLines(MENU, [req('ghost', 1)], 'dine_in')).toThrow(PricingError);
    expect(() =>
      calcOrderLines(MENU, [req('beef_noodle', 1, [['spice', 'ghost']])], 'dine_in'),
    ).toThrow(PricingError);
  });

  it('拒絕非正整數的數量', () => {
    expect(() => calcOrderLines(MENU, [req('tea', 0)], 'dine_in')).toThrow(PricingError);
    expect(() => calcOrderLines(MENU, [req('tea', 1.5)], 'dine_in')).toThrow(PricingError);
  });

  it('不讀取呼叫端偷塞的金額欄位', () => {
    const tainted = { ...req('beef_noodle', 1), price: 1, subtotal: 1, total: 1 };
    const [line] = calcOrderLines(MENU, [tainted], 'dine_in');
    expect(line?.subtotal).toBe(180);
  });
});

describe('型態轉換', () => {
  it('切換的是已快照的單價，不重查菜單', () => {
    const lines = calcOrderLines(MENU, [req('beef_noodle', 2)], 'dine_in');
    expect(lines[0]?.subtotal).toBe(360);

    const takeout = repriceLines(lines, 'takeout');
    expect(takeout[0]?.subtotal).toBe(340);
    // 原本那組不被就地修改
    expect(lines[0]?.subtotal).toBe(360);
  });

  it('店家不收服務費時，內用轉外帶總額恆等（僅在兩種價相同時）', () => {
    const lines = calcOrderLines(MENU, [req('tea', 2)], 'dine_in');
    const before = calcOrderTotal(lines, 'dine_in', NO_SERVICE_CHARGE);
    const after = calcOrderTotal(repriceLines(lines, 'takeout'), 'takeout', NO_SERVICE_CHARGE);
    expect(after.total).toBe(before.total);
  });

  it('收 10% 服務費時，內用轉外帶會產生差額', () => {
    const lines = calcOrderLines(MENU, [req('tea', 2)], 'dine_in');
    const before = calcOrderTotal(lines, 'dine_in', TEN_PERCENT);
    const after = calcOrderTotal(repriceLines(lines, 'takeout'), 'takeout', TEN_PERCENT);
    expect(before.serviceCharge).toBe(12);
    expect(before.total).toBe(132);
    expect(after.serviceCharge).toBe(0);
    expect(after.total).toBe(120);
  });
});

describe('calcOrderTotal', () => {
  it('折扣後總額不得為負', () => {
    const lines = calcOrderLines(MENU, [req('tea', 1)], 'dine_in');
    expect(calcOrderTotal(lines, 'dine_in', NO_SERVICE_CHARGE, 999).total).toBe(0);
  });

  it('拒絕負數折扣', () => {
    const lines = calcOrderLines(MENU, [req('tea', 1)], 'dine_in');
    expect(() => calcOrderTotal(lines, 'dine_in', NO_SERVICE_CHARGE, -1)).toThrow(PricingError);
  });

  it('作廢的 line 不計入總額', () => {
    const lines = calcOrderLines(MENU, [req('tea', 1), req('beef_noodle', 1)], 'dine_in');
    const voided: OrderLine[] = [
      { ...lines[0]!, voidedAt: new Date('2026-09-20T00:00:00Z') },
      lines[1]!,
    ];
    expect(calcOrderTotal(voided, 'dine_in', NO_SERVICE_CHARGE).subtotal).toBe(180);
    expect(lineSubtotal(voided[0]!, 'dine_in')).toBe(0);
  });

  it('服務費只對內用收，且是整數元', () => {
    const lines = calcOrderLines(MENU, [req('beef_noodle', 1, [['extras', 'egg']])], 'dine_in');
    // 195 × 0.1 = 19.5 → 20
    expect(calcOrderTotal(lines, 'dine_in', TEN_PERCENT).serviceCharge).toBe(20);
    expect(calcOrderTotal(lines, 'takeout', TEN_PERCENT).serviceCharge).toBe(0);
    expect(calcOrderTotal(lines, 'waitlist', TEN_PERCENT).serviceCharge).toBe(0);
  });
});

describe('稅別', () => {
  it('exempt 與 zero 的品項在 taxSummary 中分開計算', () => {
    const lines = calcOrderLines(
      MENU,
      [req('tea', 1), req('rice_gift', 1), req('export_box', 1)],
      'dine_in',
    );
    const summary = calcTaxSummary(lines, 'dine_in');

    expect(summary.taxable.amount).toBe(60);
    expect(summary.exempt.amount).toBe(500);
    expect(summary.zero.amount).toBe(300);
    expect(summary.exempt.tax).toBe(0);
    expect(summary.zero.tax).toBe(0);
  });

  it('由含稅價回推稅額', () => {
    // 105 含稅 → 未稅 100，稅 5
    expect(taxFromInclusive(105)).toBe(5);
    expect(taxFromInclusive(0)).toBe(0);
  });

  it('三個稅別的金額合計等於小計', () => {
    const lines = calcOrderLines(
      MENU,
      [req('tea', 2), req('rice_gift', 1), req('export_box', 3)],
      'dine_in',
    );
    const { subtotal, taxSummary } = calcOrderTotal(lines, 'dine_in', NO_SERVICE_CHARGE);
    const summed = taxSummary.taxable.amount + taxSummary.exempt.amount + taxSummary.zero.amount;
    expect(summed).toBe(subtotal);
  });

  it('作廢的 line 不計入稅別彙總', () => {
    const lines = calcOrderLines(MENU, [req('rice_gift', 1)], 'dine_in');
    const voided = [{ ...lines[0]!, voidedAt: new Date() }];
    expect(calcTaxSummary(voided, 'dine_in').exempt.amount).toBe(0);
  });
});

describe('splitEvenly', () => {
  it('100 元分 3 人 = 34 / 33 / 33，餘數歸第一張單', () => {
    expect(splitEvenly(100, 3)).toEqual([34, 33, 33]);
  });

  it('整除時每份相同', () => {
    expect(splitEvenly(90, 3)).toEqual([30, 30, 30]);
  });

  it('分 1 份就是全部', () => {
    expect(splitEvenly(100, 1)).toEqual([100]);
  });

  it('無論怎麼分，各份合計都等於原額', () => {
    for (let total = 0; total <= 200; total++) {
      for (let ways = 1; ways <= 7; ways++) {
        const parts = splitEvenly(total, ways);
        expect(parts).toHaveLength(ways);
        expect(parts.reduce((a, b) => a + b, 0)).toBe(total);
        expect(parts.every(Number.isInteger)).toBe(true);
      }
    }
  });

  it('拒絕非正整數的份數', () => {
    expect(() => splitEvenly(100, 0)).toThrow(PricingError);
    expect(() => splitEvenly(100, 2.5)).toThrow(PricingError);
  });
});

describe('splitByLineIds', () => {
  it('按品項拆單後，兩張單的總和必須等於原單', () => {
    const lines = calcOrderLines(
      MENU,
      [req('beef_noodle', 2), req('tea', 3), req('rice_gift', 1)],
      'dine_in',
    );
    const original = calcOrderTotal(lines, 'dine_in', NO_SERVICE_CHARGE).subtotal;

    const { taken, remaining } = splitByLineIds(lines, [lines[1]!.lineId]);
    const takenTotal = calcOrderTotal(taken, 'dine_in', NO_SERVICE_CHARGE).subtotal;
    const remainingTotal = calcOrderTotal(remaining, 'dine_in', NO_SERVICE_CHARGE).subtotal;

    expect(taken).toHaveLength(1);
    expect(remaining).toHaveLength(2);
    expect(takenTotal + remainingTotal).toBe(original);
  });

  it('沒有指定任何 line 時，原單不變', () => {
    const lines = calcOrderLines(MENU, [req('tea', 1)], 'dine_in');
    const { taken, remaining } = splitByLineIds(lines, []);
    expect(taken).toEqual([]);
    expect(remaining).toEqual(lines);
  });

  it('忽略不屬於這張單的 lineId', () => {
    const lines = calcOrderLines(MENU, [req('tea', 1)], 'dine_in');
    const { taken, remaining } = splitByLineIds(lines, ['line_nope']);
    expect(taken).toEqual([]);
    expect(remaining).toHaveLength(1);
  });
});
