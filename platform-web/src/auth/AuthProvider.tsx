import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { onUnauthorized, request, tokenStore } from '@/api/http';

export interface AuthUser {
  userId: number;
  loginName: string;
}

interface AuthContextValue {
  user: AuthUser | null;
  authenticated: boolean;
  login: (loginName: string, password: string) => Promise<void>;
  logout: () => void;
}

const USER_KEY = 'badcsql.user';

const AuthContext = createContext<AuthContextValue | null>(null);

function readStoredUser(): AuthUser | null {
  const token = tokenStore.get();
  const raw = localStorage.getItem(USER_KEY);
  if (!token || !raw) return null;
  try {
    const parsed = JSON.parse(raw) as AuthUser;
    if (parsed && typeof parsed.userId === 'number' && parsed.loginName) return parsed;
  } catch {
    /* 坏数据当作未登录 */
  }
  return null;
}

interface LoginResponse {
  token: string;
  userId: number;
  loginName: string;
}

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<AuthUser | null>(readStoredUser);

  const logout = useCallback(() => {
    tokenStore.clear();
    localStorage.removeItem(USER_KEY);
    setUser(null);
  }, []);

  useEffect(() => {
    onUnauthorized(() => logout());
    return () => onUnauthorized(null);
  }, [logout]);

  const login = useCallback(async (loginName: string, password: string) => {
    const result = await request<LoginResponse>('/api/v1/auth/login', {
      method: 'POST',
      body: { loginName, password },
      skipAuthRedirect: true,
    });
    tokenStore.set(result.token);
    const nextUser: AuthUser = { userId: result.userId, loginName: result.loginName };
    localStorage.setItem(USER_KEY, JSON.stringify(nextUser));
    setUser(nextUser);
  }, []);

  const value = useMemo<AuthContextValue>(
    () => ({ user, authenticated: Boolean(user && tokenStore.get()), login, logout }),
    [user, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider');
  return ctx;
}
