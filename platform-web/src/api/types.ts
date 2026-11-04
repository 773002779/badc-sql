/** 与后端 `/api/v1` 对齐的响应类型。字段名保持后端原样，避免多一层翻译。 */

export type RiskLevel = 'L0' | 'L1' | 'L2' | 'L3';

export type TicketStatus =
  | 'DRAFT'
  | 'REVIEWING_L1'
  | 'REVIEWING_L2'
  | 'APPROVED'
  | 'EXECUTING'
  | 'SUCCEEDED'
  | 'PARTIAL_FAILED'
  | 'FAILED'
  | 'NEEDS_MANUAL_CONFIRM'
  | 'REJECTED'
  | 'EXPIRED'
  | 'CANCELLED'
  | 'ROLLED_BACK';

export type StatementStatus = 'PENDING' | 'EXECUTING' | 'SUCCEEDED' | 'FAILED' | string;

export interface Page<T> {
  total: number;
  page: number;
  size: number;
  items: T[];
}

// ── 组织 ────────────────────────────────────────────────

export interface SystemEntity {
  id: number;
  name: string;
}

export interface ModuleEntity {
  id: number;
  systemId: number;
  name: string;
}

export interface DataSourceView {
  id: number;
  name: string;
  dbName: string;
  systemId: number;
  type: string;
}

export interface Capability {
  versionString: string | null;
  version: string | null;
  supportsExplainAnalyze: boolean;
  supportsInstantDdl: boolean;
  binlogEnabled: boolean;
  onlineDdlAvailable: boolean;
}

export interface DataSourceDetail extends DataSourceView {
  capabilities: Capability | null;
}

// ── 查询 ────────────────────────────────────────────────

export interface ColumnMeta {
  name: string;
  type: string;
}

export interface QueryResult {
  resultId: string;
  columns: ColumnMeta[];
  rows: unknown[][];
  truncated: boolean;
  elapsedMs: number;
}

/** 历史接口直接返回数据库列名（snake_case）。 */
export interface QueryHistoryItem {
  id: number;
  datasource_id: number;
  sql_text: string;
  row_count: number;
  elapsed_ms: number;
  status: string;
  created_at: string;
}

// ── 工单 ────────────────────────────────────────────────

export interface TicketSummary {
  id: number;
  status: TicketStatus;
  riskLevel: RiskLevel;
  systemId: number;
  moduleId: number;
  datasourceId: number;
  title: string;
  submitterId: number;
  ticketType: string;
  createdAt: string;
  expectedExecuteAt: string | null;
}

export interface StatementView {
  seq: number;
  statementType: string;
  riskLevel: RiskLevel;
  status: StatementStatus;
  affectedRows: number | null;
  errorMessage: string | null;
  elapsedMs: number | null;
  executedAt: string | null;
  sql: string;
}

export interface ReviewView {
  reviewLevel: number;
  reviewerId: number;
  decision: string;
  comment: string | null;
  createdAt: string;
}

export interface SnapshotView {
  changeSeq: number;
  snapshotRef: string;
  tableFingerprints: Record<string, unknown> | null;
  capturedAt: string;
}

export interface TicketEventView {
  eventType: string;
  actorId: number | null;
  detail: string | null;
  createdAt: string;
}

export interface TicketDetail {
  id: number;
  status: TicketStatus;
  riskLevel: RiskLevel;
  ticketType: string;
  systemId: number;
  moduleId: number;
  datasourceId: number;
  title: string;
  changeReason: string | null;
  rollbackPlan: string | null;
  rollbackSql: string | null;
  statementFingerprint: string;
  snapshotEnabled: boolean;
  riskSnapshotHash: string | null;
  riskSnapshot: RiskSnapshot | null;
  submitterId: number;
  originTicketId: number | null;
  rollbackReason: string | null;
  expectedExecuteAt: string | null;
  createdAt: string;
  updatedAt: string;
  approvedAt: string | null;
  executedAt: string | null;
  finishedAt: string | null;
  notificationFailures: number;
  statements: StatementView[];
  reviews: ReviewView[];
  snapshots: SnapshotView[];
  events: TicketEventView[];
}

/** 风险快照的结构随规则演进，前端只做展示兜底。 */
export interface RiskSnapshot {
  level?: RiskLevel;
  hits?: Array<{ rule?: string; level?: RiskLevel; message?: string; table?: string }>;
  [key: string]: unknown;
}

export interface SubmitResult {
  id: number;
  status: TicketStatus;
  riskLevel: RiskLevel;
  statements: Array<{ seq: number; statementType: string; riskLevel: RiskLevel }>;
}

export interface ReviewOutcome {
  id: number;
  status: TicketStatus;
  riskLevel: RiskLevel;
}

export interface AckOutcome {
  ticketId: number;
  stage: 'SUBMIT' | 'REVIEW' | 'L3_REVIEW' | 'EXECUTE';
  riskSnapshotHash: string;
  acknowledgedAt: string;
}

export interface RollbackResult {
  id: number;
  status: TicketStatus;
  riskLevel: RiskLevel;
  ticketType: string;
  originTicketId: number;
}

export interface SubmitTicketRequest {
  datasourceId: number;
  title: string;
  changeReason: string;
  rollbackPlan?: string | null;
  rollbackSql?: string | null;
  statements: string[];
  expectedExecuteAt: string;
}

export type SchemaChange = 'ADDED' | 'REMOVED' | 'CHANGED' | 'UNCHANGED' | string;

export interface FieldChange {
  name: string;
  status: SchemaChange;
  before: string | null;
  after: string | null;
}

export interface TableChange {
  table: string;
  status: SchemaChange;
  columns: FieldChange[];
  indexes: FieldChange[];
  triggers: FieldChange[];
}

export interface SchemaDiff {
  tables: TableChange[];
}

// ── 临时执行 ────────────────────────────────────────────

export interface TempExecStatementResult {
  seq: number;
  success: boolean;
  affectedRows: number;
  elapsedMs: number;
  errorMessage: string | null;
}

export interface TempExecExecuted {
  executed: true;
  riskLevel: RiskLevel;
  selfApproved: boolean;
  results: TempExecStatementResult[];
}

export interface TempExecTicketPending {
  executed: false;
  ticketId: number;
  riskLevel: RiskLevel;
  status: TicketStatus;
}

export type TempExecOutcome = TempExecExecuted | TempExecTicketPending;

// ── 通知 ────────────────────────────────────────────────

export interface NotificationItem {
  id: number;
  eventType: string;
  systemId: number | null;
  ticketId: number | null;
  grantRequestId: number | null;
  channel: string;
  status: 'PENDING' | 'SENT' | 'FAILED' | 'SKIPPED' | string;
  title: string;
  body: string;
  attempts: number;
  lastError: string | null;
  createdAt: string;
  sentAt: string | null;
}

// ── 授权 ────────────────────────────────────────────────

export interface GrantRequestItem {
  id: number;
  userId: number;
  moduleId: number;
  canExport: boolean;
  reason: string;
  status: 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED' | string;
  reviewerId: number | null;
  reviewComment: string | null;
  createdAt: string;
  reviewedAt: string | null;
}

export interface GrantSubmitOutcome {
  id: number;
  status: string;
}

export interface GrantReviewOutcome {
  id: number;
  status: string;
  canExport: boolean;
}

export interface MyGrants {
  modules: number[];
  datasources: number[];
}

// ── 审计 ────────────────────────────────────────────────

export interface AuditRow {
  seq: number;
  event_type: string;
  operator_id: number | null;
  exec_identity: string | null;
  datasource_id: number | null;
  action: string;
  object_id: string | null;
  payload_json: string | null;
  prev_hash: string;
  hash: string;
}

export interface AuditVerifyResult {
  valid: boolean;
  brokenAtSeq: number | null;
}

export interface LoginResponse {
  token: string;
  userId: number;
  loginName: string;
}
