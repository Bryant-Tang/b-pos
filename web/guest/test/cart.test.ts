import { describe, expect, it } from 'vitest';
import {
  addLine,
  cartCount,
  cartTotal,
  defaultSelection,
  lineKey,
  lineSubtotal,
  selectionError,
  setQty,
  toItemRequests,
  toggleOption,
  unavailableLines,
  type CartLine,
} from '../src/cart.js';
import { MENU, group, item, pick } from './fixtures.js';

const mild = pick('grp_spicy', 'opt_mild');
const extra = pick('grp_spicy', 'opt_extra');
const noMeat = pick('grp_remove', 'opt_no_meat');
const noEgg = pick('grp_remove', 'opt_no_egg');

describe('同品項同選項合併成一行', () => {
  it('選項順序不同但內容一樣，算同一行', () => {
    expect(lineKey('item_rice', [noMeat, noEgg])).toBe(lineKey('item_rice', [noEgg, noMeat]));
  });

  it('選項不同就是不同行', () => {
    expect(lineKey('item_beef_noodle', [mild])).not.toBe(lineKey('item_beef_noodle', [extra]));
  });

  it('加第二次只加數量，不多一行', () => {
    let cart = addLine([], item('item_beef_noodle'), [mild], 1);
    cart = addLine(cart, item('item_beef_noodle'), [mild], 2);
    expect(cart).toHaveLength(1);
    expect(cart[0]?.qty).toBe(3);
  });

  it('同品項不同選項會是兩行', () => {
    let cart = addLine([], item('item_beef_noodle'), [mild], 1);
    cart = addLine(cart, item('item_beef_noodle'), [extra], 1);
    expect(cart).toHaveLength(2);
  });
});

describe('金額（只是預估，伺服器才算數）', () => {
  const line = (over: Partial<CartLine> = {}): CartLine => ({
    key: 'k',
    itemId: 'item_rice',
    name: '白飯',
    unitPrice: 30,
    qty: 1,
    options: [],
    ...over,
  });

  it('選項加價乘進數量', () => {
    expect(lineSubtotal(line({ unitPrice: 180, options: [extra], qty: 2 }))).toBe(380);
  });

  it('負數選項把單價壓到負數時夾在 0', () => {
    // 白飯 30，不要肉 −20、不要蛋 −15 = −5，夾成 0 而不是去扣別道菜的錢
    expect(lineSubtotal(line({ options: [noMeat, noEgg], qty: 3 }))).toBe(0);
  });

  it('總額與份數', () => {
    const cart = [line({ key: 'a', qty: 2 }), line({ key: 'b', unitPrice: 60, qty: 1 })];
    expect(cartTotal(cart)).toBe(120);
    expect(cartCount(cart)).toBe(3);
  });
});

describe('改數量', () => {
  it('改成 0 就移掉那一行', () => {
    const cart = addLine([], item('item_rice'), [], 2);
    expect(setQty(cart, cart[0]!.key, 0)).toEqual([]);
  });

  it('負數也是移掉', () => {
    const cart = addLine([], item('item_rice'), [], 2);
    expect(setQty(cart, cart[0]!.key, -1)).toEqual([]);
  });
});

describe('送出的內容只有 ID 與數量', () => {
  it('沒有任何金額欄位', () => {
    const cart = addLine([], item('item_beef_noodle'), [extra], 2);
    const requests = toItemRequests(cart);
    expect(requests).toEqual([
      {
        itemId: 'item_beef_noodle',
        qty: 2,
        options: [{ groupId: 'grp_spicy', optionId: 'opt_extra' }],
      },
    ]);
    // 這條是三條架構原則的第一條，退化了要立刻知道
    expect(JSON.stringify(requests)).not.toContain('price');
    expect(JSON.stringify(requests)).not.toContain('Delta');
  });
});

describe('選項規則（要和伺服器一致）', () => {
  const spicy = group('grp_spicy');
  const remove = group('grp_remove');

  it('必選群組沒選會擋住，訊息講的是怎麼辦', () => {
    expect(selectionError([spicy], [])).toBe('請選擇辣度');
  });

  it('選了就過', () => {
    expect(selectionError([spicy], [mild])).toBeNull();
  });

  it('非必選群組可以完全不選', () => {
    expect(selectionError([remove], [])).toBeNull();
  });

  it('超過上限會擋住', () => {
    const third = { groupId: 'grp_remove', optionId: 'opt_third', name: '不要蔥', priceDelta: 0 };
    expect(selectionError([remove], [noMeat, noEgg, third])).toBe('減料最多只能選 2 項');
  });

  it('必選的單選群組預設選第一個，客人什麼都不動也送得出去', () => {
    expect(defaultSelection([spicy])).toEqual([mild]);
    expect(selectionError([spicy], defaultSelection([spicy]))).toBeNull();
  });

  it('非必選群組沒有預設值', () => {
    expect(defaultSelection([remove])).toEqual([]);
  });
});

describe('按選項', () => {
  const spicy = group('grp_spicy');
  const remove = group('grp_remove');

  it('單選是換一個，不是再加一個', () => {
    expect(toggleOption([mild], spicy, extra)).toEqual([extra]);
  });

  it('必選的單選再按一次不會變成沒選', () => {
    expect(toggleOption([mild], spicy, mild)).toEqual([mild]);
  });

  it('非必選的單選再按一次是取消', () => {
    const optional = { ...spicy, min: 0 };
    expect(toggleOption([mild], optional, mild)).toEqual([]);
  });

  it('複選可以同時選多個', () => {
    expect(toggleOption([noMeat], remove, noEgg)).toEqual([noMeat, noEgg]);
  });

  it('複選再按一次是取消', () => {
    expect(toggleOption([noMeat, noEgg], remove, noMeat)).toEqual([noEgg]);
  });

  it('超過上限時擠掉最早選的，而不是讓客人自己想要取消哪一個', () => {
    const third = { groupId: 'grp_remove', optionId: 'opt_third', name: '不要蔥', priceDelta: 0 };
    expect(toggleOption([noMeat, noEgg], remove, third)).toEqual([noEgg, third]);
  });

  it('不同群組互不影響', () => {
    expect(toggleOption([mild, noMeat], remove, noEgg)).toEqual([mild, noMeat, noEgg]);
  });
});

describe('老闆中途下架', () => {
  it('車上點不到的品項找得出來', () => {
    let cart = addLine([], item('item_beef_noodle'), [mild], 1);
    cart = addLine(cart, { id: 'item_gone', name: '被下架的', price: 50 }, [], 1);
    expect(unavailableLines(cart, MENU).map((l) => l.itemId)).toEqual(['item_gone']);
  });

  it('都還在就回空的', () => {
    const cart = addLine([], item('item_rice'), [], 1);
    expect(unavailableLines(cart, MENU)).toEqual([]);
  });
});
