/**
 * 顧客點餐頁。
 *
 * 客人的動線只有一條：掃 QR → 看菜單 → 點 → 送出 → 等店員確認。
 * 沒有註冊、沒有登入、沒有任何帳號介面（SPEC 第十三節）。
 *
 * QR code 裡的網址帶兩個參數：`?s=<storeId>&t=<qrToken>`。token 是伺服器產生的
 * 32 碼亂數，不是 `?table=5`——可預測的桌號等於讓任何人在家就能對任意桌下單
 * （SPEC 第五節〈createTable〉）。
 */

import { useCallback, useEffect, useState } from 'react';
import {
  addLine,
  cartCount,
  cartTotal,
  defaultSelection,
  lineSubtotal,
  selectionError,
  setQty,
  toItemRequests,
  toggleOption,
  type CartLine,
  type SelectedOption,
} from './cart.js';
import { groupByCategory, optionGroupsOf, type Menu, type MenuItem } from './menu.js';
import { createOrder, loadMenu, OrderFailed, type GuestOrder } from './api.js';
import { clearRequestId, loadOrder, saveOrder, takeRequestId } from './session.js';

const money = (n: number) => `$${n}`;
const signed = (n: number) => (n > 0 ? `+$${n}` : n < 0 ? `-$${Math.abs(n)}` : '');

interface Scan {
  storeId: string;
  tableToken: string;
}

/** 從網址讀掃到的桌。兩個參數缺一就不算掃過。 */
function readScan(): Scan | null {
  const params = new URLSearchParams(window.location.search);
  const storeId = params.get('s');
  const tableToken = params.get('t');
  if (storeId === null || tableToken === null) return null;
  if (!/^[0-9a-f]{32}$/.test(tableToken)) return null;
  return { storeId, tableToken };
}

export function App() {
  const [scan] = useState<Scan | null>(readScan);
  const [menu, setMenu] = useState<Menu | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [cart, setCart] = useState<CartLine[]>([]);
  const [editing, setEditing] = useState<MenuItem | null>(null);
  const [showCart, setShowCart] = useState(false);
  const [order, setOrder] = useState<GuestOrder | null>(null);
  // 已經下過單、但客人按了「我要加點」回到菜單。order 要留著：桌號只有 createOrder
  // 回傳值裡有（tables 對顧客是讀不到的），清掉的話加點畫面就不知道自己在哪一桌。
  const [adding, setAdding] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);

  useEffect(() => {
    if (scan === null) return;
    setOrder(loadOrder(scan.storeId, scan.tableToken));
    loadMenu(scan.storeId).then(setMenu, (err: unknown) => {
      setLoadError(err instanceof Error ? err.message : '菜單讀取失敗，請重新整理頁面');
    });
  }, [scan]);

  const submit = useCallback(async () => {
    if (scan === null || cart.length === 0) return;
    setSubmitting(true);
    setSubmitError(null);
    try {
      const placed = await createOrder({
        storeId: scan.storeId,
        tableToken: scan.tableToken,
        // 重送要沿用同一個 requestId，伺服器才認得出那是同一次送出而不是再點一份。
        requestId: takeRequestId(scan.storeId, scan.tableToken),
        items: toItemRequests(cart),
      });
      clearRequestId();
      saveOrder(scan.storeId, scan.tableToken, placed);
      setOrder(placed);
      setAdding(false);
      setCart([]);
      setShowCart(false);
    } catch (err) {
      // 不可重試的失敗（QR 失效、本桌已結帳）要換掉 requestId：那次送出已經確定
      // 不會成功，留著它只會讓下一次點餐沿用一個註定失敗的鍵。
      if (err instanceof OrderFailed && !err.retryable) clearRequestId();
      setSubmitError(err instanceof Error ? err.message : '送出失敗，請再試一次');
    } finally {
      setSubmitting(false);
    }
  }, [scan, cart]);

  if (scan === null) {
    return (
      <div className="centered">
        <h1>請掃描桌上的 QR code</h1>
        <p className="note">直接開啟這個網址是沒辦法點餐的，要從桌上的 QR code 進來。</p>
      </div>
    );
  }

  if (loadError !== null) {
    return (
      <div className="centered">
        <h1>暫時看不到菜單</h1>
        <p className="note">{loadError}</p>
        <button className="secondary" onClick={() => window.location.reload()}>
          重新整理
        </button>
      </div>
    );
  }

  if (menu === null) {
    return (
      <div className="centered">
        <p className="note">正在讀菜單…</p>
      </div>
    );
  }

  if (order !== null && !adding) {
    return (
      <OrderPlaced
        order={order}
        onAddMore={() => {
          setAdding(true);
          setSubmitError(null);
        }}
      />
    );
  }

  const groups = groupByCategory(menu);
  const count = cartCount(cart);

  return (
    <>
      <div className="screen">
        {/* 桌號只有送出過一次之後才知道（tables 對顧客讀不到），不知道就整行不顯示 */}
        {order !== null && <p className="table-label">桌號 {order.tableLabel}</p>}
        <h1>{adding ? '加點' : '點餐'}</h1>

        {adding && order !== null && (
          <p className="note">
            這一桌已經點了 {order.lines.reduce((sum, line) => sum + line.qty, 0)} 份，
            合計 {money(order.total)}。
          </p>
        )}

        {groups.map(({ category, items }) => (
          <section key={category?.id ?? '__other'}>
            {category !== null && <h2>{category.name}</h2>}
            {items.map((item) => {
              const inCart = cart
                .filter((line) => line.itemId === item.id)
                .reduce((sum, line) => sum + line.qty, 0);
              return (
                <button key={item.id} className="item" onClick={() => setEditing(item)}>
                  <span>
                    <span className="item-name">
                      {inCart > 0 && <span className="qty-badge">{inCart}</span>}
                      {item.name}
                    </span>
                    {item.description != null && item.description !== '' && (
                      <span className="item-desc">{item.description}</span>
                    )}
                  </span>
                  <span className="price">{money(item.price)}</span>
                </button>
              );
            })}
          </section>
        ))}
      </div>

      {count > 0 && (
        <div className="bar">
          <div className="bar-inner">
            <span className="price">
              {count} 份 · 約 {money(cartTotal(cart))}
            </span>
            <button className="primary" onClick={() => setShowCart(true)}>
              看購物車
            </button>
          </div>
        </div>
      )}

      {editing !== null && (
        <OptionSheet
          menu={menu}
          item={editing}
          onCancel={() => setEditing(null)}
          onAdd={(options, qty) => {
            setCart((lines) => addLine(lines, editing, options, qty));
            setEditing(null);
          }}
        />
      )}

      {showCart && (
        <CartSheet
          cart={cart}
          submitting={submitting}
          error={submitError}
          onChangeQty={(key, qty) => setCart((lines) => setQty(lines, key, qty))}
          onClose={() => setShowCart(false)}
          onSubmit={submit}
        />
      )}
    </>
  );
}

function OptionSheet({
  menu,
  item,
  onAdd,
  onCancel,
}: {
  menu: Menu;
  item: MenuItem;
  onAdd: (options: SelectedOption[], qty: number) => void;
  onCancel: () => void;
}) {
  const groups = optionGroupsOf(menu, item);
  const [selected, setSelected] = useState<SelectedOption[]>(() => defaultSelection(groups));
  const [qty, setQtyState] = useState(1);
  const error = selectionError(groups, selected);

  const unitPrice = item.price + selected.reduce((sum, o) => sum + o.priceDelta, 0);

  return (
    <div className="sheet-backdrop" onClick={onCancel}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <h1>{item.name}</h1>

        {groups.map((group) => (
          <section key={group.id}>
            <h2>
              {group.name}
              {group.min > 0 && '（必選）'}
              {group.type === 'multi' && group.max > 1 && `（最多 ${group.max} 項）`}
            </h2>
            {group.options.map((option) => {
              const checked = selected.some(
                (o) => o.groupId === group.id && o.optionId === option.id,
              );
              return (
                <label className="option" key={option.id}>
                  <input
                    type={group.type === 'single' ? 'radio' : 'checkbox'}
                    name={group.id}
                    checked={checked}
                    onChange={() =>
                      setSelected((current) =>
                        toggleOption(current, group, {
                          groupId: group.id,
                          optionId: option.id,
                          name: option.name,
                          priceDelta: option.priceDelta,
                        }),
                      )
                    }
                  />
                  <span>{option.name}</span>
                  <span className="option-delta">{signed(option.priceDelta)}</span>
                </label>
              );
            })}
          </section>
        ))}

        <div className="stepper">
          <button onClick={() => setQtyState((n) => Math.max(1, n - 1))} aria-label="減少">
            −
          </button>
          <span className="price">{qty}</span>
          <button onClick={() => setQtyState((n) => Math.min(20, n + 1))} aria-label="增加">
            ＋
          </button>
        </div>

        {error !== null && <p className="warn">{error}</p>}

        <div className="bar-inner">
          <button className="secondary" onClick={onCancel}>
            取消
          </button>
          <button
            className="primary"
            disabled={error !== null}
            onClick={() => onAdd(selected, qty)}
          >
            加入 · 約 {money(Math.max(0, unitPrice) * qty)}
          </button>
        </div>
      </div>
    </div>
  );
}

function CartSheet({
  cart,
  submitting,
  error,
  onChangeQty,
  onClose,
  onSubmit,
}: {
  cart: CartLine[];
  submitting: boolean;
  error: string | null;
  onChangeQty: (key: string, qty: number) => void;
  onClose: () => void;
  onSubmit: () => void;
}) {
  return (
    <div className="sheet-backdrop" onClick={onClose}>
      <div className="sheet" onClick={(e) => e.stopPropagation()}>
        <h1>購物車</h1>

        {cart.map((line) => (
          <div className="cart-line" key={line.key}>
            <span>
              <span className="item-name">{line.name}</span>
              {line.options.length > 0 && (
                <span className="cart-line-options">
                  {line.options.map((o) => o.name).join('、')}
                </span>
              )}
              <span className="stepper">
                <button onClick={() => onChangeQty(line.key, line.qty - 1)} aria-label="減少">
                  −
                </button>
                <span>{line.qty}</span>
                <button onClick={() => onChangeQty(line.key, line.qty + 1)} aria-label="增加">
                  ＋
                </button>
              </span>
            </span>
            <span className="price">{money(lineSubtotal(line))}</span>
          </div>
        ))}

        <div className="total-row">
          <span>小計</span>
          <span className="price">約 {money(cartTotal(cart))}</span>
        </div>
        {/* 金額以伺服器為準（CLAUDE.md 第二節第一條），畫面上的是預估，講清楚比較不會吵架 */}
        <p className="note">實際金額以店家結帳為準。</p>

        {error !== null && <p className="warn">{error}</p>}

        <div className="bar-inner">
          <button className="secondary" onClick={onClose} disabled={submitting}>
            繼續點
          </button>
          <button
            className="primary"
            onClick={onSubmit}
            disabled={submitting || cart.length === 0}
          >
            {submitting ? '送出中…' : '送出訂單'}
          </button>
        </div>
      </div>
    </div>
  );
}

function OrderPlaced({ order, onAddMore }: { order: GuestOrder; onAddMore: () => void }) {
  return (
    <div className="screen">
      <p className="table-label">桌號 {order.tableLabel}</p>
      <h1>已送出</h1>
      {order.status === 'pending_confirm' ? (
        <p>
          <span className="status-badge">等店員確認</span>
        </p>
      ) : (
        <p className="note">店員已確認，餐點製作中。</p>
      )}

      {order.lines.map((line) => (
        <div className="cart-line" key={line.lineId}>
          <span>
            <span className="item-name">
              <span className="qty-badge">{line.qty}</span>
              {line.name}
            </span>
            {line.options.length > 0 && (
              <span className="cart-line-options">
                {line.options.map((o) => o.name).join('、')}
              </span>
            )}
          </span>
          <span className="price">{money(line.subtotal)}</span>
        </div>
      ))}

      <div className="total-row">
        <span>合計</span>
        <span className="price">{money(order.total)}</span>
      </div>
      {order.serviceCharge > 0 && (
        <p className="note">（含服務費 {money(order.serviceCharge)}）</p>
      )}

      <p className="note">
        這是送出當下的內容。店員後續的調整不會即時顯示在這裡，以店家結帳為準。
      </p>

      <div className="bar">
        <div className="bar-inner">
          <button className="primary" onClick={onAddMore}>
            我要加點
          </button>
        </div>
      </div>
    </div>
  );
}
