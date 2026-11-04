import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Empty, Select, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { ticketApi } from '@/api/endpoints';
import type { TicketSummary } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { RiskBadge } from '@/components/RiskBadge';
import { StatusTag } from '@/components/StatusTag';
import { useDatasources } from '@/hooks/useOrg';
import { formatDateTime, formatNumber } from '@/lib/format';
import {
  TICKET_STATUS_META,
  TICKET_TYPE_META,
  ticketStatusColor,
  ticketStatusLabel,
} from '@/lib/labels';

export function TicketListPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();
  const [typeFilter, setTypeFilter] = useState<string | undefined>();
  const datasourcesQuery = useDatasources();

  const ticketsQuery = useQuery({
    queryKey: ['tickets', page, size],
    queryFn: () => ticketApi.list(page, size),
    placeholderData: (previous) => previous,
  });

  const datasourceName = useMemo(() => {
    const map = new Map<number, string>();
    (datasourcesQuery.data ?? []).forEach((item) => map.set(item.id, item.name));
    return map;
  }, [datasourcesQuery.data]);

  const items = useMemo(() => {
    let list = ticketsQuery.data?.items ?? [];
    if (statusFilter) list = list.filter((item) => item.status === statusFilter);
    if (typeFilter) list = list.filter((item) => item.ticketType === typeFilter);
    return list;
  }, [ticketsQuery.data, statusFilter, typeFilter]);

  const columns: ColumnsType<TicketSummary> = [
    {
      title: '工单',
      dataIndex: 'title',
      render: (title: string, record) => (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <a onClick={() => navigate(`/tickets/${record.id}`)} style={{ fontWeight: 550 }}>
            {title || `工单 #${record.id}`}
          </a>
          <span className="muted-3 tiny mono">
            #{record.id} · {TICKET_TYPE_META[record.ticketType] ?? record.ticketType}
          </span>
        </div>
      ),
    },
    {
      title: '数据源',
      dataIndex: 'datasourceId',
      width: 170,
      render: (value: number) => (
        <span className="tiny">{datasourceName.get(value) ?? `#${value}`}</span>
      ),
    },
    {
      title: '风险',
      dataIndex: 'riskLevel',
      width: 120,
      render: (value: string) => <RiskBadge level={value} />,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 120,
      render: (value: string) => (
        <StatusTag label={ticketStatusLabel(value)} color={ticketStatusColor(value)} />
      ),
    },
    {
      title: '提交时间',
      dataIndex: 'createdAt',
      width: 168,
      render: (value: string) => <span className="mono tiny">{formatDateTime(value)}</span>,
    },
    {
      title: '期望执行',
      dataIndex: 'expectedExecuteAt',
      width: 168,
      render: (value: string | null) => (
        <span className="mono tiny">{formatDateTime(value)}</span>
      ),
    },
    {
      title: '',
      key: 'action',
      width: 80,
      render: (_value, record) => (
        <Button type="link" size="small" onClick={() => navigate(`/tickets/${record.id}`)}>
          详情
        </Button>
      ),
    },
  ];

  return (
    <div className="page">
      <PageHeader
        title="变更工单"
        description="写操作只能经由工单：提交 → 审批 → 执行，全程留结构快照与审计。"
        extra={
          <>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => ticketsQuery.refetch()}
              loading={ticketsQuery.isFetching}
            >
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate('/tickets/new')}>
              新建工单
            </Button>
          </>
        }
      />

      <div className="surface-card pad">
        <div className="toolbar">
          <Select
            allowClear
            placeholder="全部状态"
            style={{ width: 170 }}
            value={statusFilter}
            onChange={setStatusFilter}
            options={Object.entries(TICKET_STATUS_META).map(([value, meta]) => ({
              value,
              label: meta.label,
            }))}
          />
          <Select
            allowClear
            placeholder="全部类型"
            style={{ width: 170 }}
            value={typeFilter}
            onChange={setTypeFilter}
            options={Object.entries(TICKET_TYPE_META).map(([value, label]) => ({
              value,
              label,
            }))}
          />
          <span className="muted-3 tiny">筛选仅作用于当前页</span>
        </div>
      </div>

      <div className="surface-card" style={{ overflow: 'hidden' }}>
        <Table<TicketSummary>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={items}
          loading={ticketsQuery.isLoading}
          locale={{
            emptyText: (
              <Empty
                description={
                  <span className="muted">
                    还没有工单
                    <br />
                    <span className="tiny muted-3">写操作请从「新建工单」提交</span>
                  </span>
                }
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            ),
          }}
          scroll={{ x: 'max-content' }}
          pagination={{
            current: page,
            pageSize: size,
            total: ticketsQuery.data?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${formatNumber(total)} 张工单`,
            onChange: (nextPage, nextSize) => {
              setPage(nextPage);
              setSize(nextSize);
            },
          }}
        />
      </div>
    </div>
  );
}
