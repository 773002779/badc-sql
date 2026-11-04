/**
 * 极简 fetch 封装：
 * - 自动带 JWT
 * - 统一解析后端错误信封 {code,message}
 * - 401 统一登出（由 AuthProvider 注册回调）
 */

export interface ApiErrorBody {
  code?: string;
  message?: string;
}

export class ApiError extends Error {
  readonly status: number;
  readonly code: string;

  constructor(status: number, code: string, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

const TOKEN_KEY = 'badcsql.token';

export const tokenStore = {
  get: (): string | null => localStorage.getItem(TOKEN_KEY),
  set: (token: string) => localStorage.setItem(TOKEN_KEY, token),
  clear: () => localStorage.removeItem(TOKEN_KEY),
};

let unauthorizedHandler: (() => void) | null = null;

export function onUnauthorized(handler: (() => void) | null) {
  unauthorizedHandler = handler;
}

function defaultMessage(status: number): string {
  if (status === 401) return '登录已过期，请重新登录';
  if (status === 403) return '没有权限执行该操作';
  if (status === 404) return '请求的资源不存在';
  if (status === 409) return '操作冲突，请稍后重试';
  if (status >= 500) return '服务暂时不可用，请稍后重试';
  return `请求失败（HTTP ${status}）`;
}

async function parseBody(response: Response): Promise<unknown> {
  const text = await response.text();
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

interface RequestOptions {
  method?: 'GET' | 'POST' | 'PUT' | 'PATCH' | 'DELETE';
  body?: unknown;
  query?: Record<string, string | number | boolean | null | undefined>;
  signal?: AbortSignal;
  /** 401 时不触发全局登出（登录接口自己处理） */
  skipAuthRedirect?: boolean;
  raw?: boolean;
}

function buildUrl(path: string, query?: RequestOptions['query']): string {
  if (!query) return path;
  const params = new URLSearchParams();
  Object.entries(query).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') params.set(key, String(value));
  });
  const qs = params.toString();
  return qs ? `${path}?${qs}` : path;
}

export async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' };
  const token = tokenStore.get();
  if (token) headers.Authorization = `Bearer ${token}`;
  if (options.body !== undefined) headers['Content-Type'] = 'application/json';

  let response: Response;
  try {
    response = await fetch(buildUrl(path, options.query), {
      method: options.method ?? (options.body === undefined ? 'GET' : 'POST'),
      headers,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
      signal: options.signal,
    });
  } catch (error) {
    if ((error as Error).name === 'AbortError') throw error;
    throw new ApiError(0, 'NETWORK_ERROR', '网络连接失败，请检查网络或后端服务');
  }

  if (response.status === 401 && !options.skipAuthRedirect) {
    unauthorizedHandler?.();
  }

  if (!response.ok) {
    const body = (await parseBody(response)) as ApiErrorBody | string | undefined;
    const code =
      typeof body === 'object' && body && typeof body.code === 'string'
        ? body.code
        : `HTTP_${response.status}`;
    const message =
      typeof body === 'object' && body && typeof body.message === 'string' && body.message
        ? body.message
        : defaultMessage(response.status);
    throw new ApiError(response.status, code, message);
  }

  if (response.status === 204) return undefined as T;
  return (await parseBody(response)) as T;
}

/** 人类可读的错误文案 */
export function errorMessage(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return '未知错误';
}
