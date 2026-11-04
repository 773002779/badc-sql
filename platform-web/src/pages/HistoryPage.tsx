import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Button, Empty, Table, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined, ThunderboltOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { queryApi } from '@/api/endpoints';
import type { QueryHistoryItem } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { StatusTag } from '@/components/StatusTag';
import { useDatasources } from '@/hooks/useOrg';
import { formatDateTime, formatDuration, formatNumber } from '@/lib/format';

const SQL_KEY = 'badcsql.query.sql';

export function HistoryPage() {
  const navigate = useNavigate();
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const datasourcesQuery = useDatasources();

  const historyQuery = useQuery({
    queryKey: ['history', page, size],
    queryFn: () => queryApi.history(page, size),
    placeholderData: (previous) => previous,
  });

  const datasourceName = useMemo(() => {
    const map = new Map<number, string>();
    (datasourcesQuery.data ?? []).forEach((item) => map.set(item.id, item.name));
    return map;
  }, [datasourcesQuery.data]);

  const reuse = (item: QueryHistoryItem) => {
    localStorage.setItem(SQL_KEY, item.sql_text);
    if (item.datasource_id) {
      localStorage.setItem('badcsql.query.datasource', String(item.datasource_id));
    }
    navigate('/query');
  };

  const columns: ColumnsType<QueryHistoryItem> = [
    {
      title: '时间',
      dataIndex: 'created_at',
      width: 168,
      render: (value: string) => <span className="mono tiny">{formatDateTime(value)}</span>,
    },
    {
      title: '数据源',
      dataIndex: 'datasource_id',
      width: 160,
      render: (value: number) => (
        <span>{datasourceName.get(value) ?? <span className="muted-3">#{value}</span>}</span>
      ),
    },
    {
      title: 'SQL',
      dataIndex: 'sql_text',
      ellipsis: true,
      render: (value: string) => (
        <Tooltip title={<pre style={{ margin: 0, whiteSpace: 'pre-wrap', maxWidth: 520 }}>{value}</pre>}>
          <span className="mono" style={{ fontSize: 12.5 }}>
            {value}
          </span>
        </Tooltip>
      ),
    },
    {
      title: '行数',
      dataIndex: 'row_count',
      width: 90,
      align: 'right',
      render: (value: number) => <span className="mono">{formatNumber(value)}</span>,
    },
    {
      title: '耗时',
      dataIndex: 'elapsed_ms',
      width: 100,
      align: 'right',
      render: (value: number) => <span className="mono">{formatDuration(value)}</span>,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (value: string) =>
        value === 'SUCCESS' ? (
          <StatusTag label="成功" color="success" />
        ) : (
          <StatusTag label={value} color="error" />
        ),
    },
    {
      title: '',
      key: 'action',
      width: 100,
      render: (_value, record) => (
        <Button
          type="link"
          size="small"
          icon={<ThunderboltOutlined />}
          onClick={() => reuse(record)}
          style={{ paddingInline: 0 }}
        >
          再次查询
        </Button>
      ),
    },
  ];

  const items = historyQuery.data?.items ?? [];

  return (
    <div className="page">
      <PageHeader
        title="历史记录"
        description="个人查询历史保留 90 天；研发只看到自己有权限的数据源。"
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={() => historyQuery.refetch()}
            loading={historyQuery.isFetching}
          >
            刷新
          </Button>
        }
      />

      <div className="surface-card" style={{ overflow: 'hidden' }}>
        <Table<QueryHistoryItem>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={items}
          loading={historyQuery.isLoading}
          locale={{
            emptyText: <Empty description="还没有查询记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />,
          }}
          scroll={{ x: 'max-content' }}
          pagination={{
            current: page,
            pageSize: size,
            total: historyQuery.data?.total ?? 0,
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
