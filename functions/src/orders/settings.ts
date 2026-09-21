/**
 * 讀取店家設定文件。
 *
 * 共通原則：**設定讀不到或格式不對，一律退回安全預設，不要讓整間店點不了餐。**
 * 設定壞掉是老闆可以自己在後台修好的事，不該是營業中斷的理由（SPEC 第九節的反面：
 * 營業中的 POS 停擺比少收一點服務費貴得多）。
 */

import { DEFAULT_BUSINESS_SETTINGS, type BusinessSettings } from './businessDate.js';
import type { PricingSettings } from './pricing.js';

/** 服務費設定；讀不到或不是 0 到 1 的數字時退回 0，方向是少收不是多收。 */
export function readPricingSettings(data: unknown): PricingSettings {
  const rate = (data as { dineInServiceCharge?: unknown } | undefined)?.dineInServiceCharge;
  if (typeof rate === 'number' && Number.isFinite(rate) && rate >= 0 && rate <= 1) {
    return { dineInServiceCharge: rate };
  }
  if (rate !== undefined) {
    console.warn(`settings/pricing.dineInServiceCharge 不是 0 到 1 的數字，改用 0：${String(rate)}`);
  }
  return { dineInServiceCharge: 0 };
}

/** 換日時間與時區；缺漏或格式不對時退回 DEFAULT_BUSINESS_SETTINGS。 */
export function readBusinessSettings(data: unknown): BusinessSettings {
  const raw = data as { dayCloseHour?: unknown; timeZone?: unknown } | undefined;
  const hour = raw?.dayCloseHour;
  const zone = raw?.timeZone;
  return {
    dayCloseHour:
      typeof hour === 'number' && Number.isInteger(hour) && hour >= 0 && hour <= 23
        ? hour
        : DEFAULT_BUSINESS_SETTINGS.dayCloseHour,
    timeZone: typeof zone === 'string' && zone.length > 0 ? zone : DEFAULT_BUSINESS_SETTINGS.timeZone,
  };
}
