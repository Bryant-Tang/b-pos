import { describe, expect, it } from 'vitest';
import { assertStaff, readStaffCaller, type CallerAuth } from '../../src/auth/staffAuth.js';

/** 虛構的員工 uid 與店家代號（見 CLAUDE.md 第一節）。 */
const UID = 'uid_clerk_1';
const STORE = 'store_demo';

const auth = (token: Record<string, unknown>): CallerAuth => ({ uid: UID, token });

const codeOf = (err: unknown) => (err as { code?: string }).code;

describe('readStaffCaller', () => {
  it('claims 齊全時讀出 uid、店家與角色', () => {
    expect(readStaffCaller(auth({ storeId: STORE, role: 'staff' }))).toEqual({
      uid: UID,
      storeId: STORE,
      role: 'staff',
    });
  });

  it('owner 也是店員：與 firestore.rules 的 isStaff() 同一份定義', () => {
    expect(readStaffCaller(auth({ storeId: STORE, role: 'owner' }))?.role).toBe('owner');
  });

  it('沒有登入就沒有身分', () => {
    expect(readStaffCaller(undefined)).toBeNull();
  });

  // 登得進 Firebase 不等於這個帳號能用：setStaffRole 還沒對這個 uid 跑過的話，
  // 密碼是對的、token 也拿得到，但 claims 是空的。
  it('claims 是空的就不是店員', () => {
    expect(readStaffCaller(auth({}))).toBeNull();
  });

  it('只有 storeId 沒有角色不算店員', () => {
    expect(readStaffCaller(auth({ storeId: STORE }))).toBeNull();
  });

  it('只有角色沒有 storeId 不算店員', () => {
    expect(readStaffCaller(auth({ role: 'staff' }))).toBeNull();
  });

  it('storeId 是空字串不算店員', () => {
    expect(readStaffCaller(auth({ storeId: '', role: 'staff' }))).toBeNull();
  });

  // 角色被改成別的字串時要當成「不是店員」，不是當成最低權限的 staff。
  // 猜錯的方向必須是把人擋在外面，不是放進來。
  it('不認得的角色一律擋掉，不退回 staff', () => {
    expect(readStaffCaller(auth({ storeId: STORE, role: 'manager' }))).toBeNull();
    expect(readStaffCaller(auth({ storeId: STORE, role: 'admin' }))).toBeNull();
  });

  it('角色不是字串也擋掉', () => {
    expect(readStaffCaller(auth({ storeId: STORE, role: true }))).toBeNull();
    expect(readStaffCaller(auth({ storeId: STORE, role: 1 }))).toBeNull();
  });

  it('storeId 不是字串也擋掉', () => {
    expect(readStaffCaller(auth({ storeId: 123, role: 'staff' }))).toBeNull();
  });
});

describe('assertStaff', () => {
  it('通過時回傳身分', () => {
    expect(assertStaff(auth({ storeId: STORE, role: 'owner' })).storeId).toBe(STORE);
  });

  it('擋下來時丟 permission-denied', () => {
    let thrown: unknown;
    try {
      assertStaff(undefined);
    } catch (err) {
      thrown = err;
    }
    expect(codeOf(thrown)).toBe('permission-denied');
  });

  // 沒登入與角色不對回同一句話，是刻意的（見 staffAuth.ts 的註解）。
  it('沒登入與 claims 不對給同一個訊息', () => {
    const messageOf = (a: CallerAuth | undefined) => {
      try {
        assertStaff(a);
        return null;
      } catch (err) {
        return (err as Error).message;
      }
    };
    expect(messageOf(undefined)).toBe(messageOf(auth({})));
    expect(messageOf(undefined)).not.toBeNull();
  });
});
