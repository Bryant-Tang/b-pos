/**
 * 營業日與當日發號。
 *
 * 營業到凌晨兩點的單屬於前一天，所以日結一律用 businessDate 而不是 createdAt
 * （SPEC 第三節〈三個必須遵守的資料規則〉第三條）。換日時間由店家設定，預設凌晨 5 點。
 */

/** 換日時間與時區的預設值，`settings/business` 沒設定時用這組。 */
export const DEFAULT_DAY_CLOSE_HOUR = 5;
export const DEFAULT_TIME_ZONE = 'Asia/Taipei';

export interface BusinessSettings {
  /** 0–23，當地時間幾點換日。 */
  dayCloseHour: number;
  /** IANA 時區名稱。 */
  timeZone: string;
}

export const DEFAULT_BUSINESS_SETTINGS: BusinessSettings = {
  dayCloseHour: DEFAULT_DAY_CLOSE_HOUR,
  timeZone: DEFAULT_TIME_ZONE,
};

/** 把 UTC 時刻換算成指定時區的年月日時。 */
function localParts(at: Date, timeZone: string): { y: number; m: number; d: number; h: number } {
  const fmt = new Intl.DateTimeFormat('en-CA', {
    timeZone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    hourCycle: 'h23',
  });
  const parts = new Map(fmt.formatToParts(at).map((p) => [p.type, p.value]));
  return {
    y: Number(parts.get('year')),
    m: Number(parts.get('month')),
    d: Number(parts.get('day')),
    h: Number(parts.get('hour')),
  };
}

function toIsoDate(y: number, m: number, d: number): string {
  return `${String(y).padStart(4, '0')}-${String(m).padStart(2, '0')}-${String(d).padStart(2, '0')}`;
}

/**
 * 算出某個時刻屬於哪個營業日，回傳 'YYYY-MM-DD'。
 *
 * 換日時間之前的單算前一天：dayCloseHour 為 5 時，凌晨 2:30 的單屬於前一天，
 * 早上 5:00 整的單屬於當天。
 *
 * 只在「當地日曆日」上加減，不在 UTC 毫秒上加減，所以有日光節約的時區也不會偏掉
 * （台灣沒有，但這支函式沒有理由綁死在台灣）。
 */
export function businessDateOf(at: Date, settings: BusinessSettings = DEFAULT_BUSINESS_SETTINGS): string {
  const { dayCloseHour, timeZone } = settings;
  if (!Number.isInteger(dayCloseHour) || dayCloseHour < 0 || dayCloseHour > 23) {
    throw new RangeError(`dayCloseHour 必須是 0 到 23 的整數，收到 ${dayCloseHour}`);
  }
  const { y, m, d, h } = localParts(at, timeZone);
  if (h >= dayCloseHour) return toIsoDate(y, m, d);

  // 退一個日曆日。用 UTC 當純日期容器，不涉及時區換算。
  const prev = new Date(Date.UTC(y, m - 1, d));
  prev.setUTCDate(prev.getUTCDate() - 1);
  return toIsoDate(prev.getUTCFullYear(), prev.getUTCMonth() + 1, prev.getUTCDate());
}

/** 發號的上限：4 碼數字，滿了就從 1 重來（SPEC 第十四節〈訂單號規則〉）。 */
export const MAX_PICKUP_CODE = 9999;

/**
 * 把計數器的第 n 個號碼轉成 4 碼字串。
 *
 * n 從 1 開始，超過 9999 就繞回 1——當日不重複是規格唯一的保證，
 * 而一天開超過 9999 張單的店不是這套系統的服務對象。
 */
export function formatPickupCode(n: number): string {
  if (!Number.isInteger(n) || n < 1) {
    throw new RangeError(`發號序數必須是正整數，收到 ${n}`);
  }
  const code = ((n - 1) % MAX_PICKUP_CODE) + 1;
  return String(code).padStart(4, '0');
}
