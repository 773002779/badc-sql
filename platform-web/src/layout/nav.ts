import type { ComponentType } from 'react';
import {
  AuditOutlined,
  BellOutlined,
  ClockCircleOutlined,
  DatabaseOutlined,
  FileTextOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons';

export interface NavItem {
  key: string;
  label: string;
  path: string;
  icon: ComponentType;
  /** 需要运维/管理员身份的功能，仅作展示提示，权限以后端为准 */
  opsOnly?: boolean;
}

export interface NavGroup {
  key: string;
  label: string;
  items: NavItem[];
}

export const NAV_GROUPS: NavGroup[] = [
  {
    key: 'query',
    label: '查询',
    items: [
      { key: 'query', label: '查询工作台', path: '/query', icon: ThunderboltOutlined },
      { key: 'history', label: '历史记录', path: '/history', icon: ClockCircleOutlined },
    ],
  },
  {
    key: 'change',
    label: '变更',
    items: [
      { key: 'tickets', label: '变更工单', path: '/tickets', icon: FileTextOutlined },
      { key: 'temp-exec', label: '临时执行', path: '/temp-exec', icon: DatabaseOutlined, opsOnly: true },
    ],
  },
  {
    key: 'collab',
    label: '协作',
    items: [
      { key: 'notifications', label: '通知中心', path: '/notifications', icon: BellOutlined },
      { key: 'grants', label: '权限申请', path: '/grants', icon: SafetyCertificateOutlined },
    ],
  },
  {
    key: 'governance',
    label: '治理',
    items: [
      { key: 'audit', label: '审计日志', path: '/audit', icon: AuditOutlined },
      { key: 'admin', label: '系统配置', path: '/admin', icon: SettingOutlined, opsOnly: true },
    ],
  },
];

export const NAV_ITEMS: NavItem[] = NAV_GROUPS.flatMap((group) => group.items);

/** 按路径前缀找当前导航项（/tickets/new 命中 /tickets）。 */
export function findNavItem(pathname: string): NavItem | undefined {
  return NAV_ITEMS.filter(
    (item) => pathname === item.path || pathname.startsWith(`${item.path}/`),
  ).sort((a, b) => b.path.length - a.path.length)[0];
}
