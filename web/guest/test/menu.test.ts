import { describe, expect, it } from 'vitest';
import { groupByCategory, optionGroupsOf } from '../src/menu.js';
import { MENU, item } from './fixtures.js';

describe('groupByCategory', () => {
  const groups = groupByCategory(MENU);

  it('依分類的 sort 排序，不用分類名稱排', () => {
    expect(groups.map((g) => g.category?.name)).toEqual(['主食', '飲料']);
  });

  it('售完與下架的品項不顯示', () => {
    const names = groups.flatMap((g) => g.items.map((i) => i.name));
    expect(names).not.toContain('今日售完的菜');
    expect(names).not.toContain('下架的菜');
  });

  it('空分類不顯示', () => {
    expect(groups.map((g) => g.category?.id)).not.toContain('cat_gone');
  });

  it('品項依 sort 排序', () => {
    expect(groups[0]?.items.map((i) => i.name)).toEqual(['牛肉麵', '白飯']);
  });

  it('漏填分類的品項收進最後一組，不會消失', () => {
    const orphan = { id: 'item_orphan', name: '沒分類的菜', price: 40 };
    const result = groupByCategory({ ...MENU, items: [...MENU.items, orphan] });
    expect(result[result.length - 1]?.category).toBeNull();
    expect(result[result.length - 1]?.items.map((i) => i.name)).toEqual(['沒分類的菜']);
  });

  it('沒有 categories 欄位時也不會整份菜單不見', () => {
    const result = groupByCategory({ items: MENU.items, optionGroups: MENU.optionGroups });
    expect(result).toHaveLength(1);
    expect(result[0]?.category).toBeNull();
    expect(result[0]?.items).toHaveLength(3);
  });
});

describe('optionGroupsOf', () => {
  it('依品項上列的順序回傳', () => {
    expect(optionGroupsOf(MENU, item('item_beef_noodle')).map((g) => g.id)).toEqual(['grp_spicy']);
  });

  it('沒掛選項群組就回空的', () => {
    expect(optionGroupsOf(MENU, item('item_bubble_tea'))).toEqual([]);
  });

  it('掛到不存在的群組就跳過，不要整個畫面炸掉', () => {
    const broken = { ...item('item_rice'), optionGroupIds: ['grp_gone', 'grp_remove'] };
    expect(optionGroupsOf(MENU, broken).map((g) => g.id)).toEqual(['grp_remove']);
  });
});
