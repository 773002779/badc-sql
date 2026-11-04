import dayjs from 'dayjs';
import utc from 'dayjs/plugin/utc';
import timezone from 'dayjs/plugin/timezone';

dayjs.extend(utc);
dayjs.extend(timezone);

/** 全站统一按 Asia/Shanghai 展示（存储是 UTC）。 */
export const DISPLAY_TZ = 'Asia/Shanghai';

export function formatDateTime(value?: string | null): string {
  if (!value) return '—';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.tz(DISPLAY_TZ).format('YYYY-MM-DD HH:mm:ss') : value;
}

export function formatDate(value?: string | null): string {
  if (!value) return '—';
  const parsed = dayjs(value);
  return parsed.isValid() ? parsed.tz(DISPLAY_TZ).format('YYYY-MM-DD') : value;
}

export function formatDuration(ms?: number | null): string {
  if (ms === null || ms === undefined) return '—';
  if (ms < 1000) return `${ms} ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(ms < 10_000 ? 2 : 1)} s`;
  const minutes = Math.floor(ms / 60_000);
  const seconds = Math.round((ms % 60_000) / 1000);
  return `${minutes}m ${seconds}s`;
}

export function formatNumber(value?: number | null): string {
  if (value === null || value === undefined) return '—';
  return value.toLocaleString('zh-CN');
}

/** 页面上的本地时间（DatePicker）→ 后端要的 ISO-8601 瞬间。 */
export function toIso(value: dayjs.Dayjs | null | undefined): string | undefined {
  return value ? value.toISOString() : undefined;
}
