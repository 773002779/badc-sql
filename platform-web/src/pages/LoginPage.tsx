import { useState } from 'react';
import { Navigate, useLocation, useNavigate } from 'react-router-dom';
import { Alert, Button, Form, Input, Tooltip } from 'antd';
import {
  CheckCircleFilled,
  LockOutlined,
  MoonOutlined,
  SunOutlined,
  UserOutlined,
} from '@ant-design/icons';
import { useAuth } from '@/auth/AuthProvider';
import { useTheme } from '@/theme/ThemeProvider';
import { errorMessage } from '@/api/http';

interface LoginForm {
  loginName: string;
  password: string;
}

export function LoginPage() {
  const { authenticated, login } = useAuth();
  const { mode, toggle } = useTheme();
  const navigate = useNavigate();
  const location = useLocation();
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  if (authenticated) {
    return <Navigate to="/query" replace />;
  }

  const from = (location.state as { from?: string } | null)?.from ?? '/query';

  const onFinish = async (values: LoginForm) => {
    setSubmitting(true);
    setError(null);
    try {
      await login(values.loginName.trim(), values.password);
      navigate(from, { replace: true });
    } catch (e) {
      setError(errorMessage(e));
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="login-page">
      <section className="login-hero">
        <div className="login-brand">
          <div className="brand-mark">BS</div>
          <span>BADC SQL</span>
        </div>

        <div>
          <h1>生产库变更的唯一入口。</h1>
          <p>
            查询、变更、审批与审计收敛到同一个平台：研发自助查数，写操作只经已审批工单，
            每一次变更都留下结构快照与完整链路。
          </p>
          <ul className="login-points">
            <li>
              <CheckCircleFilled /> 只读自助查询，结果随时可查
            </li>
            <li>
              <CheckCircleFilled /> 变更工单分级审批，风险高亮
            </li>
            <li>
              <CheckCircleFilled /> 结构快照 + 全量审计，可追可退
            </li>
          </ul>
        </div>

        <div className="muted-3 tiny">内部系统 · 请使用个人账号登录</div>
      </section>

      <section className="login-form-wrap">
        <div style={{ position: 'absolute', top: 20, right: 24 }}>
          <Tooltip title={mode === 'dark' ? '切换到亮色' : '切换到暗色'}>
            <button
              type="button"
              className="icon-btn"
              onClick={toggle}
              aria-label="切换主题"
            >
              {mode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
            </button>
          </Tooltip>
        </div>

        <div className="login-card">
          <h2>登录控制台</h2>
          <p className="sub">使用平台账号登录，权限以管理员分配为准。</p>

          {error ? (
            <Alert
              type="error"
              showIcon
              message={error}
              style={{ marginBottom: 16 }}
              closable
              onClose={() => setError(null)}
            />
          ) : null}

          <Form<LoginForm> layout="vertical" onFinish={onFinish} requiredMark={false} size="large">
            <Form.Item
              name="loginName"
              label="账号"
              rules={[{ required: true, message: '请输入账号' }]}
            >
              <Input prefix={<UserOutlined />} placeholder="登录名" autoComplete="username" autoFocus />
            </Form.Item>
            <Form.Item
              name="password"
              label="密码"
              rules={[{ required: true, message: '请输入密码' }]}
            >
              <Input.Password
                prefix={<LockOutlined />}
                placeholder="密码"
                autoComplete="current-password"
              />
            </Form.Item>
            <Form.Item style={{ marginBottom: 8 }}>
              <Button type="primary" htmlType="submit" block loading={submitting}>
                登录
              </Button>
            </Form.Item>
          </Form>

          <p className="muted-3 tiny" style={{ marginTop: 18, textAlign: 'center' }}>
            登录即表示同意平台使用规范与审计要求
          </p>
        </div>
      </section>
    </div>
  );
}
