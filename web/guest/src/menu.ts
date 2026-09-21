/**
 * 顧客端看到的菜單，形狀對應 Firestore 的 `tenants/{storeId}/published/menu`
 * （SPEC 第三節〈發佈模型〉）。
 *
 * 這裡只宣告顧客畫面真的會用到的欄位。多出來的欄位不會壞事——菜單是伺服器寫的，
 * 不是使用者輸入，不需要像 Cloud Function 那樣 `.strict()` 擋。
 */

export type TaxMode = 'taxable' | 'exempt' | 'zero';

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
  takeoutPrice?: number;
  taxMode?: TaxMode;
  categoryId?: string;
  description?: string;
  imageUrl?: string;
  optionGroupIds?: string[];
  available?: boolean;
  archived?: boolean;
  sort?: number;
}

export interface MenuCategory {
  id: string;
  name: string;
  sort?: number;
  archived?: boolean;
}

export interface Menu {
  version?: number;
  categories?: MenuCategory[];
  items: MenuItem[];
  optionGroups: MenuOptionGroup[];
}

const bySort = <T extends { sort?: number }>(a: T, b: T) => (a.sort ?? 0) - (b.sort ?? 0);

/**
 * 把菜單整理成畫面要的分組。
 *
 * 售完（`available: false`）與下架（`archived: true`）的品項直接不顯示：
 * 灰掉還留在畫面上只會讓客人一直點它然後困惑。空的分類也不顯示。
 *
 * 沒有分類的品項會被收進一個匿名分類裡，而不是消失——菜單是老闆自己維護的，
 * 漏填分類是會發生的事，而「客人看不到這道菜」比「分類標題有點醜」嚴重得多。
 */
export function groupByCategory(menu: Menu): { category: MenuCategory | null; items: MenuItem[] }[] {
  const sellable = menu.items
    .filter((item) => item.archived !== true && item.available !== false)
    .sort(bySort);

  const categories = (menu.categories ?? []).filter((c) => c.archived !== true).sort(bySort);
  const known = new Set(categories.map((c) => c.id));

  const groups = categories
    .map((category) => ({
      category,
      items: sellable.filter((item) => item.categoryId === category.id),
    }))
    .filter((group) => group.items.length > 0);

  const orphans = sellable.filter((item) => item.categoryId == null || !known.has(item.categoryId));
  return orphans.length > 0 ? [...groups, { category: null, items: orphans }] : groups;
}

/** 這個品項掛了哪些選項群組，依品項上的順序。找不到的群組跳過。 */
export function optionGroupsOf(menu: Menu, item: MenuItem): MenuOptionGroup[] {
  const byId = new Map(menu.optionGroups.map((g) => [g.id, g]));
  return (item.optionGroupIds ?? [])
    .map((id) => byId.get(id))
    .filter((g): g is MenuOptionGroup => g !== undefined);
}
