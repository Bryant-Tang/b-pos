/**
 * 購物車。純資料，沒有 React、沒有 Firebase，所以邊界都測得到。
 *
 * **這裡算出來的金額只是給客人看的預估。** 真正的金額由 createOrder 在伺服器上
 * 從 published/menu 算（CLAUDE.md 第二節第一條：客戶端永遠不送金額），送出時也只送
 * 品項 ID、數量與選項 ID。畫面上的數字跟帳單對不上是可能的——老闆剛改了價、
 * 而客人手上這份菜單是幾分鐘前讀的——那時候以伺服器為準，畫面重讀菜單就會一致。
 */

import type { Menu, MenuItem, MenuOptionGroup } from './menu.js';

export interface SelectedOption {
  groupId: string;
  optionId: string;
  /** 顯示用，不送給伺服器 */
  name: string;
  /** 顯示用，不送給伺服器 */
  priceDelta: number;
}

export interface CartLine {
  /** 同品項同選項要合併成一行，這個鍵就是「算不算同一種」的判準 */
  key: string;
  itemId: string;
  name: string;
  unitPrice: number;
  qty: number;
  options: SelectedOption[];
}

/** 送給 createOrder 的形狀。只有 ID 與數量，沒有任何金額。 */
export interface ItemRequest {
  itemId: string;
  qty: number;
  options: { groupId: string; optionId: string }[];
}

/**
 * 同一個品項、同一組選項就是同一行。
 *
 * 選項先排序再組鍵：客人先選辣度再選加料，和先選加料再選辣度，點的是同一碗麵。
 */
export function lineKey(itemId: string, options: readonly SelectedOption[]): string {
  const parts = options.map((o) => `${o.groupId}:${o.optionId}`).sort();
  return [itemId, ...parts].join('|');
}

export function lineSubtotal(line: CartLine): number {
  const delta = line.options.reduce((sum, o) => sum + o.priceDelta, 0);
  // 與伺服器的 lineSubtotal 一樣夾在 0 以上：負數選項可能把單價壓成負的，
  // 那一行就會去抵銷整張單的金額。這裡是顯示層，夾住只是為了不顯示負數。
  return Math.max(0, line.unitPrice + delta) * line.qty;
}

export function cartTotal(lines: readonly CartLine[]): number {
  return lines.reduce((sum, line) => sum + lineSubtotal(line), 0);
}

export function cartCount(lines: readonly CartLine[]): number {
  return lines.reduce((sum, line) => sum + line.qty, 0);
}

/** 加一份到車上。已經有同品項同選項的行就加數量，不新增一行。 */
export function addLine(
  lines: readonly CartLine[],
  item: MenuItem,
  options: readonly SelectedOption[],
  qty: number,
): CartLine[] {
  const key = lineKey(item.id, options);
  const existing = lines.find((line) => line.key === key);
  if (existing) {
    return lines.map((line) => (line.key === key ? { ...line, qty: line.qty + qty } : line));
  }
  return [
    ...lines,
    { key, itemId: item.id, name: item.name, unitPrice: item.price, qty, options: [...options] },
  ];
}

/** 改數量；改到 0 或以下就把那一行移掉。 */
export function setQty(lines: readonly CartLine[], key: string, qty: number): CartLine[] {
  if (qty <= 0) return lines.filter((line) => line.key !== key);
  return lines.map((line) => (line.key === key ? { ...line, qty } : line));
}

export function toItemRequests(lines: readonly CartLine[]): ItemRequest[] {
  return lines.map((line) => ({
    itemId: line.itemId,
    qty: line.qty,
    options: line.options.map((o) => ({ groupId: o.groupId, optionId: o.optionId })),
  }));
}

/**
 * 選項選得對不對。
 *
 * 伺服器一定會再驗一次（`calcOrderLines`），這裡驗是為了讓客人在按下去之前就知道
 * 「請選擇辣度」，而不是送出後收到一句「餐點的選項有誤」。
 * 兩邊的規則必須一致，不一致的話客人會卡在一個按得下去但永遠失敗的按鈕上。
 */
export function selectionError(
  groups: readonly MenuOptionGroup[],
  selected: readonly SelectedOption[],
): string | null {
  for (const group of groups) {
    const count = selected.filter((o) => o.groupId === group.id).length;
    const max = group.type === 'single' ? 1 : group.max;
    if (count < group.min) {
      return group.min === 1 ? `請選擇${group.name}` : `${group.name}至少要選 ${group.min} 項`;
    }
    if (count > max) {
      return `${group.name}最多只能選 ${max} 項`;
    }
  }
  return null;
}

/**
 * 按下一個選項之後，這個群組的選取狀態會變成什麼。
 *
 * 單選群組是「換一個」不是「再加一個」：已經選了小辣再按大辣，結果只有大辣。
 * 單選群組如果不是必選（min 為 0），再按一次已選的那個就是取消。
 */
export function toggleOption(
  selected: readonly SelectedOption[],
  group: MenuOptionGroup,
  option: SelectedOption,
): SelectedOption[] {
  const isSelected = selected.some(
    (o) => o.groupId === group.id && o.optionId === option.optionId,
  );
  const others = selected.filter((o) => o.groupId !== group.id);

  if (group.type === 'single') {
    if (isSelected && group.min === 0) return others;
    return [...others, option];
  }

  const sameGroup = selected.filter((o) => o.groupId === group.id);
  if (isSelected) {
    return [...others, ...sameGroup.filter((o) => o.optionId !== option.optionId)];
  }
  // 超過上限時把最早選的擠掉，而不是讓客人自己去想要先取消哪一個。
  const kept = sameGroup.length >= group.max ? sameGroup.slice(1) : sameGroup;
  return [...others, ...kept, option];
}

/** 必選的單選群組預設選第一個，客人什麼都不動也送得出去。 */
export function defaultSelection(groups: readonly MenuOptionGroup[]): SelectedOption[] {
  const selected: SelectedOption[] = [];
  for (const group of groups) {
    const first = group.options[0];
    if (group.type === 'single' && group.min >= 1 && first) {
      selected.push({
        groupId: group.id,
        optionId: first.id,
        name: first.name,
        priceDelta: first.priceDelta,
      });
    }
  }
  return selected;
}

/** 車上的品項在重新讀到的菜單裡還在不在。老闆中途下架時用得到。 */
export function unavailableLines(lines: readonly CartLine[], menu: Menu): CartLine[] {
  const sellable = new Set(
    menu.items.filter((i) => i.archived !== true && i.available !== false).map((i) => i.id),
  );
  return lines.filter((line) => !sellable.has(line.itemId));
}
