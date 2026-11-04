import type { TicketStatus } from '@/api/types';

type TagColor = 'default' | 'processing' | 'success' | 'warning' | 'error' | 'blue' | 'purple' | 'cyan';

export const TICKET_STATUS_META: Record<string, { label: string; color: TagColor }> = {
  DRAFT: { label: '草稿', color: 'default' },
  REVIEWING_L1: { label: '待运维审核', color: 'processing' },
  REVIEWING_L2: { label: '待组长终审', color: 'processing' },
  APPROVED: { label: '待执行', color: 'blue' },
  EXECUTING: { label: '执行中', color: 'processing' },
  SUCCEEDED: { label: '执行成功', color: 'success' },
  PARTIAL_FAILED: { label: '部分失败', color: 'warning' },
  FAILED: { label: '执行失败', color: 'error' },
  NEEDS_MANUAL_CONFIRM: { label: '需人工确认', color: 'warning' },
  REJECTED: { label: '已驳回', color: 'error' },
  EXPIRED: { label: '已过期', color: 'default' },
  CANCELLED: { label: '已取消', color: 'default' },
  ROLLED_BACK: { label: '已回滚', color: 'purple' },
};

export function ticketStatusLabel(status?: TicketStatus | string | null): string {
  if (!status) return '—';
  return TICKET_STATUS_META[status]?.label ?? status;
}

export function ticketStatusColor(status?: TicketStatus | string | null): TagColor {
  if (!status) return 'default';
  return TICKET_STATUS_META[status]?.color ?? 'default';
}

export const TICKET_TYPE_META: Record<string, string> = {
  NORMAL: '变更',
  ROLLBACK: '回滚',
  TEMP_EXEC: '临时执行转单',
};

export const ACK_STAGE_META: Record<string, string> = {
  SUBMIT: '提交确认',
  REVIEW: '审核确认',
  L3_REVIEW: '终审确认',
  EXECUTE: '执行确认',
};

export const GRANT_STATUS_META: Record<string, { label: string; color: TagColor }> = {
  PENDING: { label: '待审批', color: 'processing' },
  APPROVED: { label: '已通过', color: 'success' },
  REJECTED: { label: '已驳回', color: 'error' },
  CANCELLED: { label: '已取消', color: 'default' },
};

export const NOTIFICATION_STATUS_META: Record<string, { label: string; color: TagColor }> = {
  PENDING: { label: '待发送', color: 'processing' },
  SENT: { label: '已发送', color: 'success' },
  FAILED: { label: '发送失败', color: 'error' },
  SKIPPED: { label: '已跳过', color: 'default' },
};

export const NOTIFICATION_EVENT_META: Record<string, string> = {
  TICKET_SUBMITTED: '工单已提交',
  TICKET_LEAD_FALLBACK: '组长代审',
  TICKET_APPROVED: '工单已通过',
  TICKET_REJECTED: '工单已驳回',
  TICKET_L3_PENDING_LEAD: '工单待终审',
  TICKET_EXECUTED: '工单已执行',
  TICKET_EXEC_FAILED: '工单执行失败',
  GRANT_REQUESTED: '授权申请',
  GRANT_APPROVED: '授权已通过',
  GRANT_REJECTED: '授权已驳回',
};

export const CHANNEL_META: Record<string, string> = {
  IN_APP: '站内信',
  WECOM: '企业微信',
  SMTP: '邮件',
  MAIL: '邮件',
  EMAIL: '邮件',
};

export const EVENT_TYPE_META: Record<string, string> = {
  LOGIN: '登录',
  LOGIN_FAILED: '登录失败',
  QUERY: '查询',
  QUERY_REJECTED: '查询被拒',
  EXPORT_REQUESTED: '导出申请',
  EXPORT_APPROVED: '导出通过',
  EXPORT_REJECTED: '导出驳回',
  EXPORT_DOWNLOADED: '导出下载',
  TICKET_CREATED: '工单创建',
  TICKET_SUBMITTED: '工单提交',
  TICKET_APPROVED: '工单审核通过',
  TICKET_REJECTED: '工单驳回',
  TICKET_EXECUTED: '工单执行',
  TICKET_EXEC_FAILED: '工单执行失败',
  TICKET_CANCELLED: '工单取消',
  RISK_ACKNOWLEDGED: '风险确认',
  SNAPSHOT_CREATED: '结构快照',
  DIRECT_EXEC: '临时执行',
  DIRECT_EXEC_ACKNOWLEDGED: '临时执行确认',
  DIRECT_EXEC_BLOCKED: '临时执行被拒',
  GRANT_REQUESTED: '授权申请',
  GRANT_APPROVED: '授权通过',
  GRANT_REJECTED: '授权驳回',
  CONFIG_CHANGED: '配置变更',
  DATASOURCE_CREATED: '数据源创建',
  DATASOURCE_UPDATED: '数据源修改',
  DATASOURCE_DELETED: '数据源删除',
  ACCOUNT_ROTATED: '账号轮换',
};

export function humanize(value?: string | null): string {
  if (!value) return '—';
  return EVENT_TYPE_META[value] ?? value;
}
