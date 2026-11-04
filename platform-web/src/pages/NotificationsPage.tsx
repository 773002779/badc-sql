import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Empty, Select, Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { notificationApi } from '@/api/endpoints';
import type { NotificationItem } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { StatusTag } from '@/components/StatusTag';
import { formatDateTime, formatNumber } from '@/lib/format';
import {
  CHANNEL_META,
  NOTIFICATION_EVENT_META,
  NOTIFICATION_STATUS_META,
} from '@/lib/labels';

export function NotificationsPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [statusFilter, setStatusFilter] = useState<string | undefined>();

  const notificationsQuery = useQuery({
    queryKey: ['notifications', page, size],
    queryFn: () => notificationApi.mine(page, size),
    placeholderData: (previous) => previous,
  });

  const items = useMemo(() => {
    const list = notificationsQuery.data?.items ?? [];
    return statusFilter ? list.filter((item) => item.status === statusFilter) : list;
  }, [notificationsQuery.data, statusFilter]);

  const columns: ColumnsType<NotificationItem> = [
    {
      title: '事件',
      dataIndex: 'eventType',
      width: 150,
      render: (value: string) => (
        <span>{NOTIFICATION_EVENT_META[value] ?? value}</span>
      ),
    },
    {
      title: '内容',
      dataIndex: 'title',
      render: (title: string, record) => (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span style={{ fontWeight: 550 }}>{title}</span>
          <span className="muted tiny" style={{ whiteSpace: 'pre-wrap' }}>
            {record.body}
          </span>
        </div>
      ),
    },
    {
      title: '渠道',
      dataIndex: 'channel',
      width: 100,
      render: (value: string) => <span className="tiny">{CHANNEL_META[value] ?? value}</span>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: string) => {
        const meta = NOTIFICATION_STATUS_META[value];
        return <StatusTag label={meta?.label ?? value} color={meta?.color ?? 'default'} />;
      },
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      width: 168,
      render: (value: string) => <span className="mono tiny">{formatDateTime(value)}</span>,
    },
    {
      title: '',
      key: 'action',
      width: 110,
      render: (_value, record) =>
        record.ticketId ? (
          <Button
            type="link"
            size="small"
            style={{ paddingInline: 0 }}
            onClick={() => navigate(`/tickets/${record.ticketId}`)}
          >
            查看工单
          </Button>
        ) : record.grantRequestId ? (
          <Button
            type="link"
            size="small"
            style={{ paddingInline: 0 }}
            onClick={() => navigate('/grants')}
          >
            查看申请
          </Button>
        ) : null,
    },
  ];

  const failedCount = (notificationsQuery.data?.items ?? []).filter(
    (item) => item.status === 'FAILED',
  ).length;

  return (
    <div className="page">
      <PageHeader
        title="通知中心"
        description="工单、审批与授权申请的站内消息（企业微信 / 邮件按平台配置投递）。"
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={() => notificationsQuery.refetch()}
            loading={notificationsQuery.isFetching}
          >
            刷新
          </Button>
        }
      />

      {failedCount > 0 ? (
        <div className="surface-card pad" style={{ borderColor: 'var(--danger)' }}>
          <span className="danger-text">当页有 {failedCount} 条通知投递失败</span>
          <span className="muted tiny" style={{ marginLeft: 8 }}>
            鼠标悬停或查看工单详情可了解失败原因
          </span>
        </div>
      ) : null}

      <div className="surface-card" style={{ overflow: 'hidden' }}>
        <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)' }}>
          <div className="toolbar">
            <Select
              allowClear
              placeholder="全部状态"
              style={{ width: 160 }}
              value={statusFilter}
              onChange={setStatusFilter}
              options={Object.entries(NOTIFICATION_STATUS_META).map(([value, meta]) => ({
                value,
                label: meta.label,
              }))}
            />
            <span className="muted-3 tiny">多渠道投递记录，失败不影响工单流程</span>
          </div>
        </div>

        <Table<NotificationItem>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={items}
          loading={notificationsQuery.isLoading}
          locale={{
            emptyText: (
              <Empty description="暂无通知" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ),
          }}
          scroll={{ x: 'max-content' }}
          pagination={{
            current: page,
            pageSize: size,
            total: notificationsQuery.data?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${formatNumber(total)} 条`,
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
