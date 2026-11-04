import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { ConfigProvider, theme as antdTheme } from 'antd';
import zhCN from 'antd/locale/zh_CN';
import dayjs from 'dayjs';
import 'dayjs/locale/zh-cn';

dayjs.locale('zh-cn');

export type ThemeMode = 'light' | 'dark';

const STORAGE_KEY = 'badcsql.theme';

interface ThemeContextValue {
  mode: ThemeMode;
  setMode: (mode: ThemeMode) => void;
  toggle: () => void;
}

const ThemeContext = createContext<ThemeContextValue | null>(null);

function readInitialMode(): ThemeMode {
  if (typeof window === 'undefined') return 'light';
  const stored = window.localStorage.getItem(STORAGE_KEY);
  if (stored === 'light' || stored === 'dark') return stored;
  return window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
}

export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(readInitialMode);

  const setMode = useCallback((next: ThemeMode) => {
    setModeState(next);
    window.localStorage.setItem(STORAGE_KEY, next);
    document.documentElement.classList.toggle('dark', next === 'dark');
    document.documentElement.dataset.theme = next;
  }, []);

  const toggle = useCallback(() => {
    setMode(mode === 'dark' ? 'light' : 'dark');
  }, [mode, setMode]);

  // 首屏由 index.html 的内联脚本定过主题；这里只做一次对齐，避免水合后不一致。
  useEffect(() => {
    document.documentElement.classList.toggle('dark', mode === 'dark');
    document.documentElement.dataset.theme = mode;
  }, [mode]);

  const value = useMemo(() => ({ mode, setMode, toggle }), [mode, setMode, toggle]);

  const isDark = mode === 'dark';

  return (
    <ThemeContext.Provider value={value}>
      <ConfigProvider
        locale={zhCN}
        theme={{
          algorithm: isDark ? antdTheme.darkAlgorithm : antdTheme.defaultAlgorithm,
          token: {
            colorPrimary: isDark ? '#6b93ff' : '#3563e9',
            colorInfo: isDark ? '#6b93ff' : '#3563e9',
            colorSuccess: isDark ? '#3ddc97' : '#12805c',
            colorWarning: isDark ? '#f5b544' : '#b45309',
            colorError: isDark ? '#ff6b5e' : '#d92d20',
            colorBgBase: isDark ? '#0d0f13' : '#f5f6f8',
            colorBgContainer: isDark ? '#16191f' : '#ffffff',
            colorBgElevated: isDark ? '#1b1f26' : '#ffffff',
            colorBorder: isDark ? '#262b34' : '#e5e7eb',
            colorBorderSecondary: isDark ? '#22262e' : '#eef0f3',
            colorText: isDark ? '#e7eaf0' : '#171a20',
            colorTextSecondary: isDark ? '#a4adba' : '#5a6472',
            colorTextTertiary: isDark ? '#78818f' : '#8b93a1',
            borderRadius: 8,
            borderRadiusLG: 10,
            controlHeight: 34,
            fontSize: 14,
            fontFamily:
              "system-ui, -apple-system, 'Segoe UI', 'PingFang SC', 'Hiragino Sans GB', 'Microsoft YaHei', sans-serif",
            wireframe: false,
          },
          components: {
            Button: { primaryShadow: 'none', defaultShadow: 'none' },
            Table: {
              headerBg: isDark ? '#1b1f26' : '#fafbfc',
              headerColor: isDark ? '#a4adba' : '#5a6472',
              headerSplitColor: 'transparent',
              rowHoverBg: isDark ? '#1d222a' : '#f7f9fc',
              borderColor: isDark ? '#262b34' : '#eef0f3',
              cellPaddingBlock: 10,
            },
            Card: { boxShadowTertiary: 'none' },
            Tabs: { itemSelectedColor: isDark ? '#6b93ff' : '#3563e9' },
            Modal: { titleFontSize: 16 },
            Descriptions: { labelBg: isDark ? '#1b1f26' : '#fafbfc' },
          },
        }}
      >
        {children}
      </ConfigProvider>
    </ThemeContext.Provider>
  );
}

export function useTheme(): ThemeContextValue {
  const ctx = useContext(ThemeContext);
  if (!ctx) throw new Error('useTheme must be used inside ThemeProvider');
  return ctx;
}
