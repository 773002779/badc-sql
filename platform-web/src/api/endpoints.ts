import { request } from './http';
import type {
  AckOutcome,
  AuditRow,
  AuditVerifyResult,
  DataSourceDetail,
  DataSourceView,
  GrantRequestItem,
  GrantReviewOutcome,
  GrantSubmitOutcome,
  ModuleEntity,
  MyGrants,
  NotificationItem,
  Page,
  QueryHistoryItem,
  QueryResult,
  ReviewOutcome,
  RiskLevel,
  RollbackResult,
  SchemaDiff,
  SubmitResult,
  SubmitTicketRequest,
  SystemEntity,
  TempExecOutcome,
  TicketDetail,
  TicketSummary,
} from './types';

// ── 组织 ────────────────────────────────────────────────

export const orgApi = {
  systems: () => request<SystemEntity[]>('/api/v1/systems'),
  modules: (systemId?: number) =>
    request<ModuleEntity[]>('/api/v1/modules', { query: { systemId } }),
  datasources: () => request<DataSourceView[]>('/api/v1/datasources'),
  datasource: (id: number) => request<DataSourceDetail>(`/api/v1/datasources/${id}`),
  createDatasource: (body: {
    systemId: number;
    name: string;
    dbName: string;
    type?: string;
    host: string;
    port: number;
    charset?: string;
    timeZone?: string;
  }) => request<DataSourceView>('/api/v1/datasources', { method: 'POST', body }),
  updateDatasource: (id: number, body: { name: string; dbName: string }) =>
    request<DataSourceView>(`/api/v1/datasources/${id}`, { method: 'PUT', body }),
  deleteDatasource: (id: number) =>
    request<void>(`/api/v1/datasources/${id}`, { method: 'DELETE' }),
  createSystem: (name: string) =>
    request<SystemEntity>('/api/v1/systems', { method: 'POST', body: { name } }),
  createModule: (systemId: number, name: string) =>
    request<ModuleEntity>('/api/v1/modules', { method: 'POST', body: { systemId, name } }),
  mountDatasource: (moduleId: number, datasourceId: number) =>
    request<void>(`/api/v1/modules/${moduleId}/datasources/${datasourceId}`, { method: 'POST' }),
};

// ── 查询 ────────────────────────────────────────────────

export const queryApi = {
  run: (datasourceId: number, sql: string) =>
    request<QueryResult>('/api/v1/query', { method: 'POST', body: { datasourceId, sql } }),
  history: (page = 1, size = 20) =>
    request<Page<QueryHistoryItem>>('/api/v1/history', { query: { page, size } }),
};

// ── 工单 ────────────────────────────────────────────────

export const ticketApi = {
  list: (page = 1, size = 20) =>
    request<Page<TicketSummary>>('/api/v1/tickets', { query: { page, size } }),
  detail: (id: number) => request<TicketDetail>(`/api/v1/tickets/${id}`),
  submit: (body: SubmitTicketRequest) =>
    request<SubmitResult>('/api/v1/tickets', { method: 'POST', body }),
  approve: (id: number, comment?: string) =>
    request<ReviewOutcome>(`/api/v1/tickets/${id}/approve`, { method: 'POST', body: { comment } }),
  reject: (id: number, comment: string) =>
    request<ReviewOutcome>(`/api/v1/tickets/${id}/reject`, { method: 'POST', body: { comment } }),
  acknowledgeRisk: (id: number) =>
    request<AckOutcome>(`/api/v1/tickets/${id}/risk-ack`, { method: 'POST', body: {} }),
  execute: (id: number) =>
    request<{ id: number; status: string }>(`/api/v1/tickets/${id}/execute`, {
      method: 'POST',
      body: {},
    }),
  rollback: (id: number, reason: string, rollbackSql?: string) =>
    request<RollbackResult>(`/api/v1/tickets/${id}/rollback`, {
      method: 'POST',
      body: { reason, rollbackSql },
    }),
  schemaDiff: (id: number) => request<SchemaDiff>(`/api/v1/tickets/${id}/schema-diff`),
  notifications: (id: number) =>
    request<NotificationItem[]>(`/api/v1/tickets/${id}/notifications`),
};

// ── 临时执行（运维） ────────────────────────────────────

export const tempExecApi = {
  execute: (datasourceId: number, statements: string[], confirmed: boolean) =>
    request<TempExecOutcome>('/api/v1/temp-executions', {
      method: 'POST',
      body: { datasourceId, statements, confirmed },
    }),
};

// ── 通知 ────────────────────────────────────────────────

export const notificationApi = {
  mine: (page = 1, size = 20) =>
    request<Page<NotificationItem>>('/api/v1/notifications', { query: { page, size } }),
};

// ── 授权 ────────────────────────────────────────────────

export const grantApi = {
  mine: () => request<MyGrants>('/api/v1/grants/me'),
  requests: (scope: 'mine' | 'pending', page = 1, size = 20) =>
    request<Page<GrantRequestItem>>('/api/v1/grant-requests', {
      query: { scope, page, size },
    }),
  submit: (moduleId: number, canExport: boolean, reason: string) =>
    request<GrantSubmitOutcome>('/api/v1/grant-requests', {
      method: 'POST',
      body: { moduleId, canExport, reason },
    }),
  approve: (id: number, comment?: string) =>
    request<GrantReviewOutcome>(`/api/v1/grant-requests/${id}/approve`, {
      method: 'POST',
      body: { comment },
    }),
  reject: (id: number, comment: string) =>
    request<GrantReviewOutcome>(`/api/v1/grant-requests/${id}/reject`, {
      method: 'POST',
      body: { comment },
    }),
};

// ── 审计 ────────────────────────────────────────────────

export const auditApi = {
  tail: (limit = 100) => request<AuditRow[]>('/api/v1/audit/tail', { query: { limit } }),
  verify: () => request<AuditVerifyResult>('/api/v1/audit/verify'),
};

export type { RiskLevel };
