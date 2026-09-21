import type { Menu } from '../src/menu.js';

/** 虛構菜單，與 functions 的測試用同一組假資料（見 CLAUDE.md 第一節）。 */
export const MENU: Menu = {
  version: 1_758_000_000_000,
  categories: [
    { id: 'cat_main', name: '主食', sort: 0 },
    { id: 'cat_drink', name: '飲料', sort: 1 },
    { id: 'cat_gone', name: '空分類', sort: 2 },
  ],
  items: [
    {
      id: 'item_beef_noodle',
      name: '牛肉麵',
      price: 180,
      categoryId: 'cat_main',
      optionGroupIds: ['grp_spicy'],
      sort: 0,
    },
    {
      id: 'item_rice',
      name: '白飯',
      price: 30,
      categoryId: 'cat_main',
      optionGroupIds: ['grp_remove'],
      sort: 1,
    },
    { id: 'item_bubble_tea', name: '珍珠奶茶', price: 60, categoryId: 'cat_drink', sort: 0 },
    {
      id: 'item_sold_out',
      name: '今日售完的菜',
      price: 100,
      categoryId: 'cat_main',
      available: false,
      sort: 2,
    },
    {
      id: 'item_archived',
      name: '下架的菜',
      price: 100,
      categoryId: 'cat_main',
      archived: true,
      sort: 3,
    },
  ],
  optionGroups: [
    {
      id: 'grp_spicy',
      name: '辣度',
      type: 'single',
      min: 1,
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

export const item = (id: string) => MENU.items.find((i) => i.id === id)!;
export const group = (id: string) => MENU.optionGroups.find((g) => g.id === id)!;
export const pick = (groupId: string, optionId: string) => {
  const g = group(groupId);
  const o = g.options.find((x) => x.id === optionId)!;
  return { groupId, optionId, name: o.name, priceDelta: o.priceDelta };
};
