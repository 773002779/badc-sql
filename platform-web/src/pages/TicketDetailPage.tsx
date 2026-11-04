import { useMemo, useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  Alert,
  App,
  Badge,
  Button,
  Checkbox,
  Descriptions,
  Empty,
  Input,
  Modal,
  Result,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Timeline,
  Tooltip,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  ArrowLeftOutlined,
  CheckCircleOutlined,
  CloseCircleOutlined,
  DiffOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  RollbackOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ticketApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type {
  FieldChange,
  NotificationItem,
  RiskSnapshot,
  SchemaDiff,
  StatementView,
  TableChange,
} from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { RiskBadge } from '@/components/RiskBadge';
import { StatusTag } from '@/components/StatusTag';
import { useDatasources } from '@/hooks/useOrg';
import { formatDateTime, formatDuration, formatNumber } from '@/lib/format';
import {
  CHANNEL_META,
  NOTIFICATION_EVENT_META,
  NOTIFICATION_STATUS_META,
  TICKET_TYPE_META,
  ticketStatusColor,
  ticketStatusLabel,
} from '@/lib/labels';

type ActionKind = 'approve' | 'reject' | 'execute' | 'rollback' | null;

const CHANGE_LABEL: Record<string, string> = {
  ADDED: '新增',
  REMOVED: '删除',
  CHANGED: '变更',
  UNCHANGED: '不变',
};

const CHANGE_COLOR: Record<string, string> = {
  ADDED: 'green',
  REMOVED: 'red',
  CHANGED: 'orange',
  UNCHANGED: 'default',
};

export function TicketDetailPage() {
  const { id } = useParams<{ id: string }>();
  const ticketId = Number(id);
  const navigate = useNavigate();
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const datasourcesQuery = useDatasources();

  const [action, setAction] = useState<ActionKind>(null);
  const [comment, setComment] = useState('');
  const [riskAcknowledged, setRiskAcknowledged] = useState(false);
  const [rollbackReason, setRollbackReason] = useState('');
  const [rollbackSql, setRollbackSql] = useState('');
  const [actionError, setActionError] = useState<string | null>(null);

  const detailQuery = useQuery({
    queryKey: ['ticket', ticketId],
    queryFn: () => ticketApi.detail(ticketId),
    enabled: Number.isFinite(ticketId) && ticketId > 0,
    refetchInterval: (query) =>
      query.state.data?.status === 'EXECUTING' ? 2500 : false,
  });

  const ticket = detailQuery.data;

  const datasourceName = useMemo(() => {
    const map = new Map<number, string>();
    (datasourcesQuery.data ?? []).forEach((item) => map.set(item.id, item.name));
    return map;
  }, [datasourcesQuery.data]);

  const notificationsQuery = useQuery({
    queryKey: ['ticket', ticketId, 'notifications'],
    queryFn: () => ticketApi.notifications(ticketId),
    enabled: Boolean(ticket),
    retry: false,
  });

  const [diff, setDiff] = useState<SchemaDiff | null>(null);
  const [diffError, setDiffError] = useState<string | null>(null);
  const [diffLoading, setDiffLoading] = useState(false);

  const refresh = () => {
    queryClient.invalidateQueries({ queryKey: ['ticket', ticketId] });
    queryClient.invalidateQueries({ queryKey: ['tickets'] });
  };

  const approveMutation = useMutation({
    mutationFn: () => ticketApi.approve(ticketId, comment.trim() || undefined),
    onSuccess: () => {
      message.success('已通过审批');
      closeAction();
      refresh();
    },
    onError: (error) => setActionError(errorMessage(error)),
  });

  const rejectMutation = useMutation({
    mutationFn: () => ticketApi.reject(ticketId, comment.trim()),
    onSuccess: () => {
      message.success('已驳回');
      closeAction();
      refresh();
    },
    onError: (error) => setActionError(errorMessage(error)),
  });

  const executeMutation = useMutation({
    mutationFn: async () => {
      await ticketApi.acknowledgeRisk(ticketId);
      return ticketApi.execute(ticketId);
    },
    onSuccess: () => {
      message.success('工单已进入执行队列');
      closeAction();
      refresh();
    },
    onError: (error) => setActionError(errorMessage(error)),
  });

  const rollbackMutation = useMutation({
    mutationFn: () => ticketApi.rollback(ticketId, rollbackReason.trim(), rollbackSql.trim() || undefined),
    onSuccess: (result) => {
      message.success('回滚工单已创建');
      closeAction();
      navigate(`/tickets/${result.id}`);
    },
    onError: (error) => setActionError(errorMessage(error)),
  });

  const loadDiff = async () => {
    setDiffLoading(true);
    setDiffError(null);
    try {
      setDiff(await ticketApi.schemaDiff(ticketId));
    } catch (error) {
      setDiffError(errorMessage(error));
    } finally {
      setDiffLoading(false);
    }
  };

  const closeAction = () => {
    setAction(null);
    setComment('');
    setRiskAcknowledged(false);
    setRollbackReason('');
    setRollbackSql('');
    setActionError(null);
  };

  const openAction = (kind: ActionKind) => {
    setAction(kind);
    setActionError(null);
    if (kind === 'rollback') {
      setRollbackSql(ticket?.rollbackSql ?? '');
    }
  };

  if (detailQuery.isLoading) {
    return (
      <div className="page" style={{ paddingTop: 80, textAlign: 'center' }}>
        <Spin />
      </div>
    );
  }

  if (detailQuery.isError || !ticket) {
    return (
      <div className="page">
        <Result
          status="warning"
          title="无法打开工单"
          subTitle={errorMessage(detailQuery.error)}
          extra={
            <Button type="primary" onClick={() => navigate('/tickets')}>
              返回工单列表
            </Button>
          }
        />
      </div>
    );
  }

  const isReviewing = ticket.status === 'REVIEWING_L1' || ticket.status === 'REVIEWING_L2';
  const canExecute = ticket.status === 'APPROVED';
  const canRollback =
    ticket.status === 'SUCCEEDED' || ticket.status === 'PARTIAL_FAILED' || ticket.status === 'FAILED';

  const statementColumns: ColumnsType<StatementView> = [
    {
      title: '#',
      dataIndex: 'seq',
      width: 56,
      render: (value: number) => <span className="mono muted-3">{value}</span>,
    },
    {
      title: '语句',
      dataIndex: 'sql',
      render: (value: string) => (
        <pre className="sql-preview" style={{ margin: 0, maxHeight: 96, overflow: 'auto' }}>
          {value}
        </pre>
      ),
    },
    {
      title: '类型',
      dataIndex: 'statementType',
      width: 120,
      render: (value: string) => <span className="mono tiny">{value}</span>,
    },
    {
      title: '风险',
      dataIndex: 'riskLevel',
      width: 110,
      render: (value: string) => <RiskBadge level={value} />,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: string) => (
        <StatusTag
          label={
            value === 'SUCCEEDED'
              ? '成功'
              : value === 'FAILED'
                ? '失败'
                : value === 'EXECUTING'
                  ? '执行中'
                  : '待执行'
          }
          color={
            value === 'SUCCEEDED'
              ? 'success'
              : value === 'FAILED'
                ? 'error'
                : value === 'EXECUTING'
                  ? 'processing'
                  : 'default'
          }
        />
      ),
    },
    {
      title: '影响行数',
      dataIndex: 'affectedRows',
      width: 100,
      align: 'right',
      render: (value: number | null) => (
        <span className="mono">{value === null ? '—' : formatNumber(value)}</span>
      ),
    },
    {
      title: '耗时',
      dataIndex: 'elapsedMs',
      width: 90,
      align: 'right',
      render: (value: number | null) => <span className="mono">{formatDuration(value)}</span>,
    },
    {
      title: '执行时间',
      dataIndex: 'executedAt',
      width: 160,
      render: (value: string | null) => <span className="mono tiny">{formatDateTime(value)}</span>,
    },
    {
      title: '错误',
      dataIndex: 'errorMessage',
      width: 200,
      render: (value: string | null) =>
        value ? (
          <Tooltip title={value}>
            <span className="danger-text tiny">{value}</span>
          </Tooltip>
        ) : (
          <span className="muted-3">—</span>
        ),
    },
  ];

  const notificationColumns: ColumnsType<NotificationItem> = [
    {
      title: '事件',
      dataIndex: 'eventType',
      render: (value: string) => NOTIFICATION_EVENT_META[value] ?? value,
    },
    {
      title: '渠道',
      dataIndex: 'channel',
      width: 100,
      render: (value: string) => CHANNEL_META[value] ?? value,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: string) => {
        const meta = NOTIFICATION_STATUS_META[value];
        return <StatusTag label={meta?.label ?? value} color={meta?.color ?? 'default'} />;
      },
    },
    {
      title: '尝试',
      dataIndex: 'attempts',
      width: 70,
      align: 'right',
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 168,
      render: (value: string) => <span className="mono tiny">{formatDateTime(value)}</span>,
    },
    {
      title: '失败原因',
      dataIndex: 'lastError',
      render: (value: string | null) =>
        value ? <span className="danger-text tiny">{value}</span> : <span className="muted-3">—</span>,
    },
  ];

  const riskSnapshot = ticket.riskSnapshot as RiskSnapshot | null;

  return (
    <div className="page">
      <PageHeader
        title={
          <span>
            工单 #{ticket.id} · {ticket.title}
          </span>
        }
        description={
          <Space size={8} wrap>
            <StatusTag label={ticketStatusLabel(ticket.status)} color={ticketStatusColor(ticket.status)} />
            <RiskBadge level={ticket.riskLevel} long />
            <Tag>{TICKET_TYPE_META[ticket.ticketType] ?? ticket.ticketType}</Tag>
            {ticket.originTicketId ? (
              <a onClick={() => navigate(`/tickets/${ticket.originTicketId}`)}>
                原工单 #{ticket.originTicketId}
              </a>
            ) : null}
          </Space>
        }
        extra={
          <>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tickets')}>
              返回
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => detailQuery.refetch()}
              loading={detailQuery.isFetching}
            >
              刷新
            </Button>
            {isReviewing ? (
              <>
                <Button
                  danger
                  icon={<CloseCircleOutlined />}
                  onClick={() => openAction('reject')}
                >
                  驳回
                </Button>
                <Button
                  type="primary"
                  icon={<CheckCircleOutlined />}
                  onClick={() => openAction('approve')}
                >
                  通过审批
                </Button>
              </>
            ) : null}
            {canExecute ? (
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                onClick={() => openAction('execute')}
              >
                执行工单
              </Button>
            ) : null}
            {canRollback ? (
              <Button
                icon={<RollbackOutlined />}
                onClick={() => {
                  setRollbackSql(ticket.rollbackSql ?? '');
                  openAction('rollback');
                }}
              >
                发起回滚
              </Button>
            ) : null}
          </>
        }
      />

      {ticket.notificationFailures > 0 ? (
        <Alert
          type="warning"
          showIcon
          message={`有 ${ticket.notificationFailures} 条通知投递失败`}
          description="通知失败不影响流程，可在「通知」标签查看失败原因。"
        />
      ) : null}

      {ticket.status === 'EXECUTING' ? (
        <Alert type="info" showIcon message="工单执行中，页面每 2.5 秒自动刷新。" />
      ) : null}

      <div className="surface-card pad">
        <Descriptions
          size="small"
          column={{ xs: 1, sm: 2, lg: 3 }}
          items={[
            {
              key: 'datasource',
              label: '数据源',
              children: datasourceName.get(ticket.datasourceId) ?? `#${ticket.datasourceId}`,
            },
            { key: 'system', label: '系统 ID', children: `#${ticket.systemId}` },
            { key: 'module', label: '模块 ID', children: `#${ticket.moduleId}` },
            { key: 'submitter', label: '提交人', children: `用户 #${ticket.submitterId}` },
            {
              key: 'created',
              label: '提交时间',
              children: formatDateTime(ticket.createdAt),
            },
            {
              key: 'expected',
              label: '期望执行',
              children: formatDateTime(ticket.expectedExecuteAt),
            },
            { key: 'approvedAt', label: '审批通过', children: formatDateTime(ticket.approvedAt) },
            { key: 'executedAt', label: '开始执行', children: formatDateTime(ticket.executedAt) },
            { key: 'finishedAt', label: '执行结束', children: formatDateTime(ticket.finishedAt) },
            {
              key: 'snapshot',
              label: '结构快照',
              children: ticket.snapshotEnabled ? (
                <Badge status="success" text="已启用（强制）" />
              ) : (
                <Badge status="default" text="未启用" />
              ),
            },
            {
              key: 'fingerprint',
              label: '语句指纹',
              children: (
                <span className="mono tiny" title={ticket.statementFingerprint}>
                  {ticket.statementFingerprint.slice(0, 16)}…                </span>
              ),
            },
            {
              key: 'riskHash',
              label: '风险快照哈希',
              children: (
                <span className="mono tiny" title={ticket.riskSnapshotHash ?? ''}>
                  {ticket.riskSnapshotHash ? `${ticket.riskSnapshotHash.slice(0, 16)}…` : '—'}
                </span>
              ),
            },
          ]}
        />
      </div>

      <Tabs
        defaultActiveKey="overview"
        items={[
          {
            key: 'overview',
            label: '概览',
            children: (
              <div className="stack">
                <div className="surface-card pad">
                  <h3 className="section-title">变更说明</h3>
                  <p className="muted" style={{ whiteSpace: 'pre-wrap', margin: '8px 0 0' }}>
                    {ticket.changeReason || '—'}
                  </p>
                </div>

                <div className="surface-card pad">
                  <div className="row" style={{ justifyContent: 'space-between' }}>
                    <h3 className="section-title">风险提示</h3>
                    <RiskBadge level={ticket.riskLevel} long />
                  </div>
                  {riskSnapshot?.hits?.length ? (
                    <div className="stack" style={{ gap: 8, marginTop: 10 }}>
                      {riskSnapshot.hits.map((hit, index) => (
                        <div
                          key={`${hit.rule ?? 'hit'}-${index}`}
                          className="row"
                          style={{ alignItems: 'flex-start', gap: 10 }}
                        >
                          <RiskBadge level={hit.level ?? ticket.riskLevel} />
                          <span style={{ flex: 1 }}>{hit.message ?? hit.rule ?? '命中风险规则'}</span>
                          {hit.table ? <span className="muted-3 mono tiny">{hit.table}</span> : null}
                        </div>
                      ))}
                    </div>
                  ) : (
                    <p className="muted tiny" style={{ margin: '8px 0 0' }}>
                      未命中额外风险规则。风险等级在提交、审核、执行前各计算一次，取最严格结果。                    </p>
                  )}
                </div>

                <div className="surface-card pad">
                  <h3 className="section-title">回滚方案</h3>
                  <div className="grid-2" style={{ marginTop: 10 }}>
                    <div>
                      <div className="muted-3 tiny">回滚 SQL</div>
                      <pre className="sql-preview" style={{ marginTop: 6, minHeight: 64 }}>
                        {ticket.rollbackSql || '—'}
                      </pre>
                    </div>
                    <div>
                      <div className="muted-3 tiny">回滚说明</div>
                      <pre className="sql-preview" style={{ marginTop: 6, minHeight: 64, whiteSpace: 'pre-wrap' }}>
                        {ticket.rollbackPlan || '—'}
                      </pre>
                    </div>
                  </div>
                  <p className="muted-3 tiny" style={{ marginBottom: 0, marginTop: 10 }}>
                    平台只保留结构快照，不备份行数据；回滚 SQL 由提单人提供，执行前请人工确认其正确性。                  </p>
                </div>

                <div className="surface-card pad">
                  <div className="row" style={{ justifyContent: 'space-between' }}>
                    <h3 className="section-title">结构差异</h3>
                    <Button
                      size="small"
                      icon={<DiffOutlined />}
                      onClick={loadDiff}
                      loading={diffLoading}
                    >
                      加载变更前后差异
                    </Button>
                  </div>
                  {diffError ? (
                    <Alert type="warning" showIcon message={diffError} style={{ marginTop: 10 }} />
                  ) : null}
                  {diff ? <SchemaDiffView diff={diff} /> : null}
                  {!diff && !diffError ? (
                    <p className="muted tiny" style={{ margin: '8px 0 0' }}>
                      执行完成后可比较变更前后的表结构（列 / 索引 / 触发器）。                    </p>
                  ) : null}
                </div>
              </div>
            ),
          },
          {
            key: 'statements',
            label: `语句（${ticket.statements.length}）`,
            children: (
              <div className="surface-card" style={{ overflow: 'hidden' }}>
                <Table<StatementView>
                  rowKey="seq"
                  size="small"
                  columns={statementColumns}
                  dataSource={ticket.statements}
                  pagination={false}
                  scroll={{ x: 'max-content' }}
                />
              </div>
            ),
          },
          {
            key: 'reviews',
            label: `审批（${ticket.reviews.length}）`,
            children: (
              <div className="surface-card pad">
                {ticket.reviews.length === 0 && ticket.events.length === 0 ? (
                  <Empty description="暂无审批记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                ) : (
                  <Timeline
                    items={[
                      ...ticket.reviews.map((review) => ({
                        color: review.decision === 'APPROVED' ? 'green' : 'red',
                        children: (
                          <div>
                            <div>
                              <strong>
                                {review.reviewLevel === 1 ? '运维一级' : '组长终审'}
                              </strong>
                              {' · '}
                              {review.decision === 'APPROVED' ? '通过' : '驳回'}
                              <span className="muted-3 tiny" style={{ marginLeft: 8 }}>
                                {formatDateTime(review.createdAt)} · 用户 #{review.reviewerId}
                              </span>
                            </div>
                            {review.comment ? (
                              <div className="muted" style={{ marginTop: 4 }}>
                                {review.comment}
                              </div>
                            ) : null}
                          </div>
                        ),
                      })),
                      ...ticket.events.map((event) => ({
                        color: 'gray',
                        children: (
                          <div>
                            <span className="mono tiny">{event.eventType}</span>
                            <span className="muted-3 tiny" style={{ marginLeft: 8 }}>
                              {formatDateTime(event.createdAt)}
                              {event.actorId ? ` · 用户 #${event.actorId}` : ''}
                            </span>
                            {event.detail ? (
                              <pre
                                className="sql-preview"
                                style={{ marginTop: 6, fontSize: 11.5, maxHeight: 120, overflow: 'auto' }}
                              >
                                {event.detail}
                              </pre>
                            ) : null}
                          </div>
                        ),
                      })),
                    ]}
                  />
                )}
              </div>
            ),
          },
          {
            key: 'snapshots',
            label: `结构快照（${ticket.snapshots.length}）`,
            children: (
              <div className="stack">
                {ticket.snapshots.length === 0 ? (
                  <div className="surface-card pad">
                    <Empty description="执行前抓取结构快照后，这里会出现快照记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
                  </div>
                ) : (
                  ticket.snapshots.map((snapshot) => (
                    <div className="surface-card pad" key={`${snapshot.changeSeq}-${snapshot.capturedAt}`}>
                      <div className="row" style={{ justifyContent: 'space-between' }}>
                        <h3 className="section-title">
                          变更 #{snapshot.changeSeq}
                          <Tag style={{ marginLeft: 8 }} color="blue">
                            {snapshot.changeSeq === 1 ? '执行前' : '执行后'}
                          </Tag>
                        </h3>
                        <span className="muted-3 tiny mono">{formatDateTime(snapshot.capturedAt)}</span>
                      </div>
                      <div className="muted-3 tiny mono" style={{ marginTop: 4 }}>
                        {snapshot.snapshotRef}
                      </div>
                      <pre
                        className="sql-preview"
                        style={{ marginTop: 10, maxHeight: 240, overflow: 'auto', fontSize: 11.5 }}
                      >
                        {JSON.stringify(snapshot.tableFingerprints ?? {}, null, 2)}
                      </pre>
                    </div>
                  ))
                )}
              </div>
            ),
          },
          {
            key: 'notifications',
            label: '通知',
            children: (
              <div className="surface-card" style={{ overflow: 'hidden' }}>
                <Table<NotificationItem>
                  rowKey="id"
                  size="small"
                  columns={notificationColumns}
                  dataSource={notificationsQuery.data ?? []}
                  loading={notificationsQuery.isLoading}
                  pagination={false}
                  locale={{ emptyText: '暂无通知记录' }}
                  scroll={{ x: 'max-content' }}
                />
              </div>
            ),
          },
        ]}
      />

      {/* 审批通过 */}
      <Modal
        title="通过审批"
        open={action === 'approve'}
        onCancel={closeAction}
        onOk={() => approveMutation.mutate()}
        okText="确认通过"
        confirmLoading={approveMutation.isPending}
        destroyOnHidden
      >
        <p className="muted">
          通过后工单进入待执行状态，由提交人或运维点击执行。如需说明可填写审核意见。        </p>
        <Input.TextArea
          rows={3}
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          placeholder="审核意见（可选）"
        />
        {actionError ? <Alert type="error" showIcon message={actionError} style={{ marginTop: 12 }} /> : null}
      </Modal>

      {/* 驳回 */}
      <Modal
        title="驳回工单"
        open={action === 'reject'}
        onCancel={closeAction}
        onOk={() => {
          if (!comment.trim()) {
            setActionError('驳回必须填写原因');
            return;
          }
          rejectMutation.mutate();
        }}
        okText="确认驳回"
        okButtonProps={{ danger: true }}
        confirmLoading={rejectMutation.isPending}
        destroyOnHidden
      >
        <p className="muted">驳回后工单关闭，提交人需修改后重新提交。</p>
        <Input.TextArea
          rows={3}
          value={comment}
          onChange={(event) => setComment(event.target.value)}
          placeholder="驳回原因（必填）"
        />
        {actionError ? <Alert type="error" showIcon message={actionError} style={{ marginTop: 12 }} /> : null}
      </Modal>

      {/* 执行 */}
      <Modal
        title="执行前风险确认"
        open={action === 'execute'}
        onCancel={closeAction}
        onOk={() => {
          if (!riskAcknowledged) {
            setActionError('请先勾选确认已了解风险');
            return;
          }
          executeMutation.mutate();
        }}
        okText="确认执行"
        okButtonProps={{ danger: ticket.riskLevel === 'L3' }}
        confirmLoading={executeMutation.isPending}
        destroyOnHidden
      >
        <div className="stack" style={{ gap: 12 }}>
          <div className="row">
            <span className="muted">本次风险等级</span>
            <RiskBadge level={ticket.riskLevel} long />
          </div>
          {riskSnapshot?.hits?.length ? (
            <ul className="muted" style={{ margin: 0, paddingLeft: 18 }}>
              {riskSnapshot.hits.slice(0, 6).map((hit, index) => (
                <li key={index}>{hit.message ?? hit.rule ?? '命中风险规则'}</li>
              ))}
            </ul>
          ) : null}
          <Alert
            type={ticket.riskLevel === 'L3' ? 'error' : 'warning'}
            showIcon
            message={
              ticket.riskLevel === 'L3'
                ? '此操作不可一键回滚，数据需从备份恢复'
                : '执行前会重新校验权限、结构漂移与风险等级'
            }
          />
          <Checkbox
            checked={riskAcknowledged}
            onChange={(event) => setRiskAcknowledged(event.target.checked)}
          >
            我已确认影响范围，并知悉回滚依赖我填写的回滚 SQL / 备份
          </Checkbox>
          {actionError ? <Alert type="error" showIcon message={actionError} /> : null}
        </div>
      </Modal>

      {/* 回滚 */}
      <Modal
        title={`基于工单 #${ticket.id} 发起回滚`}
        open={action === 'rollback'}
        onCancel={closeAction}
        onOk={() => {
          if (rollbackReason.trim().length < 10) {
            setActionError('回滚原因至少 10 个字');
            return;
          }
          rollbackMutation.mutate();
        }}
        okText="创建回滚工单"
        confirmLoading={rollbackMutation.isPending}
        destroyOnHidden
        width={640}
      >
        <div className="stack" style={{ gap: 12 }}>
          <Alert
            type="info"
            showIcon
            icon={<SafetyCertificateOutlined />}
            message="回滚是一张新工单，需要重新审批"
            description="系统不会自动生成回滚语句，默认带入原工单填写的回滚 SQL，可在此修改。"
          />
          <div>
            <div className="muted tiny" style={{ marginBottom: 6 }}>
              回滚原因（≥10 字）
            </div>
            <Input.TextArea
              rows={2}
              value={rollbackReason}
              onChange={(event) => setRollbackReason(event.target.value)}
              placeholder="为什么回滚、影响范围"
            />
          </div>
          <div>
            <div className="muted tiny" style={{ marginBottom: 6 }}>
              回滚 SQL
            </div>
            <Input.TextArea
              rows={4}
              value={rollbackSql}
              onChange={(event) => setRollbackSql(event.target.value)}
              placeholder="带出原工单的回滚 SQL，可修改"
            />
          </div>
          {actionError ? <Alert type="error" showIcon message={actionError} /> : null}
        </div>
      </Modal>
    </div>
  );
}

function SchemaDiffView({ diff }: { diff: SchemaDiff }) {
  if (!diff.tables.length) {
    return <p className="muted tiny">两份快照没有可比对的对象。</p>;
  }

  return (
    <div className="stack" style={{ gap: 12, marginTop: 12 }}>
      {diff.tables.map((table) => (
        <TableDiffCard key={table.table} table={table} />
      ))}
    </div>
  );
}

function TableDiffCard({ table }: { table: TableChange }) {
  const groups: Array<{ label: string; fields: FieldChange[] }> = [
    { label: '列', fields: table.columns },
    { label: '索引', fields: table.indexes },
    { label: '触发器', fields: table.triggers },
  ].filter((group) => group.fields.length > 0);

  return (
    <div className="surface-card pad">
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <span className="mono" style={{ fontWeight: 600 }}>
          {table.table}
        </span>
        <Tag color={CHANGE_COLOR[table.status] ?? 'default'}>
          {CHANGE_LABEL[table.status] ?? table.status}
        </Tag>
      </div>
      {groups.length === 0 ? (
        <p className="muted tiny" style={{ margin: '8px 0 0' }}>
          结构未变化        </p>
      ) : (
        groups.map((group) => (
          <div key={group.label} style={{ marginTop: 10 }}>
            <div className="muted-3 tiny" style={{ marginBottom: 4 }}>
              {group.label}
            </div>
            {group.fields.map((field) => (
              <div
                key={`${group.label}-${field.name}`}
                className="row"
                style={{ gap: 8, alignItems: 'baseline', flexWrap: 'wrap' }}
              >
                <Tag color={CHANGE_COLOR[field.status] ?? 'default'} style={{ marginInlineEnd: 0 }}>
                  {CHANGE_LABEL[field.status] ?? field.status}
                </Tag>
                <span className="mono tiny">{field.name}</span>
                {field.status === 'CHANGED' ? (
                  <span className="muted-3 tiny">
                    {field.before ?? '—'} → {field.after ?? '—'}
                  </span>
                ) : null}
              </div>
            ))}
          </div>
        ))
      )}
    </div>
  );
}
