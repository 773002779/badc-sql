import { useMemo, useState } from 'react';
import type { ReactNode } from 'react';
import { Link, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { Avatar, Dropdown, Tooltip } from 'antd';
import {
  LogoutOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  MoonOutlined,
  SunOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useAuth } from '@/auth/AuthProvider';
import { useTheme } from '@/theme/ThemeProvider';
import { NAV_GROUPS, findNavItem } from './nav';

const COLLAPSE_KEY = 'badcsql.sider.collapsed';

export function AppLayout() {
  const location = useLocation();
  const navigate = useNavigate();
  const { user, logout } = useAuth();
  const { mode, toggle } = useTheme();
  const [collapsed, setCollapsed] = useState(() => localStorage.getItem(COLLAPSE_KEY) === '1');

  const current = findNavItem(location.pathname);
  const title = current?.label ?? '控制台';

  const navGroups = useMemo(() => NAV_GROUPS, []);

  const toggleCollapsed = () => {
    setCollapsed((prev) => {
      const next = !prev;
      localStorage.setItem(COLLAPSE_KEY, next ? '1' : '0');
      return next;
    });
  };

  const handleUserMenu = ({ key }: { key: string }) => {
    if (key === 'logout') {
      logout();
      navigate('/login', { replace: true });
    }
  };

  return (
    <div className="app-shell">
      <aside className={`app-sider${collapsed ? ' collapsed' : ''}`}>
        <Link to="/query" className="app-brand" style={{ color: 'inherit' }}>
          <div className="brand-mark">BS</div>
          {!collapsed ? (
            <div className="brand-text">
              <span className="brand-title">BADC SQL</span>
              <span className="brand-sub">生产库执行平台</span>
            </div>
          ) : null}
        </Link>

        <nav className="app-nav">
          {navGroups.map((group) => (
            <div key={group.key}>
              <div className="nav-group-label">{group.label}</div>
              {group.items.map((item) => {
                const active =
                  location.pathname === item.path ||
                  location.pathname.startsWith(`${item.path}/`);
                const icon = <item.icon />;
                const link: ReactNode = (
                  <Link
                    key={item.key}
                    to={item.path}
                    className={`nav-item${active ? ' active' : ''}`}
                    title={collapsed ? item.label : undefined}
                  >
                    {icon}
                    <span className="nav-item-label">{item.label}</span>
                  </Link>
                );
                return collapsed ? (
                  <Tooltip key={item.key} title={item.label} placement="right">
                    {link}
                  </Tooltip>
                ) : (
                  link
                );
              })}
            </div>
          ))}
        </nav>

        <div className="sider-footer">
          <button
            type="button"
            className="sider-collapse-btn"
            onClick={toggleCollapsed}
            title={collapsed ? '展开侧栏' : '收起侧栏'}
          >
            {collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
            <span className="label">收起侧栏</span>
          </button>
        </div>
      </aside>

      <div className="app-main">
        <header className="app-header">
          <div className="header-crumb">
            <span>{title}</span>
            <span className="crumb-sub">BADC SQL</span>
          </div>

          <div className="header-actions">
            <Tooltip title={mode === 'dark' ? '切换到亮色' : '切换到暗色'}>
              <button
                type="button"
                className="icon-btn"
                onClick={toggle}
                aria-label={mode === 'dark' ? '切换到亮色' : '切换到暗色'}
              >
                {mode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
              </button>
            </Tooltip>

            <Dropdown
              trigger={['click']}
              menu={{
                items: [
                  {
                    key: 'profile',
                    disabled: true,
                    label: `账号 ID：${user?.userId ?? '-'}`,
                  },
                  { type: 'divider' },
                  { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
                ],
                onClick: handleUserMenu,
              }}
            >
              <button type="button" className="icon-btn" style={{ width: 'auto', padding: '0 8px', gap: 8 }} aria-label="账号菜单">
                <Avatar size={24} icon={<UserOutlined />} style={{ background: 'var(--primary)' }} />
                <span style={{ fontSize: 13, color: 'var(--text-2)' }}>{user?.loginName}</span>
              </button>
            </Dropdown>
          </div>
        </header>

        <main className="app-content">
          <Outlet />
        </main>
      </div>
    </div>
  );
}
