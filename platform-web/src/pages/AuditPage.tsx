import { useMemo, useState } from 'react';
import { Alert, Button, Empty, Input, Table, Tag, Tooltip } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { ReloadOutlined, SafetyCertificateOutlined } from '@ant-design/icons';
import { useQuery } from '@tanstack/react-query';
import { auditApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { AuditRow } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { useDatasources } from '@/hooks/useOrg';
import { formatNumber } from '@/lib/format';
import { humanize } from '@/lib/labels';

const EVENT_COLORS: Record<string, string> = {
  LOGIN: 'default',
  QUERY: 'blue',
  TICKET_SUBMITTED: 'cyan',
  TICKET_APPROVED: 'green',
  TICKET_REJECTED: 'red',
  TICKET_EXECUTED: 'green',
  TICKET_EXEC_FAILED: 'red',
  SNAPSHOT_CREATED: 'purple',
  DIRECT_EXEC: 'orange',
  GRANT_APPROVED: 'green',
  GRANT_REJECTED: 'red',
};

export function AuditPage() {
  const [limit, setLimit] = useState(100);
  const [keyword, setKeyword] = useState('');
  const datasourcesQuery = useDatasources();

  const auditQuery = useQuery({
    queryKey: ['audit', 'tail', limit],
    queryFn: () => auditApi.tail(limit),
    refetchOnWindowFocus: false,
  });

  const verifyQuery = useQuery({
    queryKey: ['audit', 'verify'],
    queryFn: auditApi.verify,
    retry: false,
  });

  const datasourceName = useMemo(() => {
    const map = new Map<number, string>();
    (datasourcesQuery.data ?? []).forEach((item) => map.set(item.id, item.name));
    return map;
  }, [datasourcesQuery.data]);

  const rows = useMemo(() => {
    const list = auditQuery.data ?? [];
    if (!keyword.trim()) return list;
    const needle = keyword.trim().toLowerCase();
    return list.filter((row) =>
      [row.event_type, row.action, row.object_id, String(row.operator_id ?? '')]
        .join(' ')
        .toLowerCase()
        .includes(needle),
    );
  }, [auditQuery.data, keyword]);

  const verify = verifyQuery.data;
  const verifyError = verifyQuery.error;

  const columns: ColumnsType<AuditRow> = [
    {
      title: 'seq',
      dataIndex: 'seq',
      width: 90,
      render: (value: number) => <span className="mono tiny">{value}</span>,
    },
    {
      title: '事件',
      dataIndex: 'event_type',
      width: 200,
      render: (value: string) => (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span>
            <Tag color={EVENT_COLORS[value] ?? 'default'} style={{ marginInlineEnd: 6 }}>
              {humanize(value)}
            </Tag>
          </span>
          <span className="muted-3 tiny mono">{value}</span>
        </div>
      ),
    },
    {
      title: '动作',
      dataIndex: 'action',
      width: 160,
      render: (value: string) => <span className="mono tiny">{value}</span>,
    },
    {
      title: '操作人',
      dataIndex: 'operator_id',
      width: 100,
      render: (value: number | null) =>
        value ? <span className="mono tiny">#{value}</span> : <span className="muted-3">—</span>,
    },
    {
      title: '执行身份',
      dataIndex: 'exec_identity',
      width: 140,
      render: (value: string | null) => <span className="mono tiny">{value ?? '—'}</span>,
    },
    {
      title: '数据源',
      dataIndex: 'datasource_id',
      width: 150,
      render: (value: number | null) =>
        value ? (
          <span className="tiny">{datasourceName.get(value) ?? `#${value}`}</span>
        ) : (
          <span className="muted-3">—</span>
        ),
    },
    {
      title: '对象',
      dataIndex: 'object_id',
      width: 140,
      render: (value: string | null) => <span className="mono tiny">{value ?? '—'}</span>,
    },
    {
      title: '哈希',
      dataIndex: 'hash',
      width: 140,
      render: (value: string) => (
        <Tooltip title={value}>
          <span className="mono tiny">{value.slice(0, 12)}…</span>
        </Tooltip>
      ),
    },
  ];

  return (
    <div className="page">
      <PageHeader
        title="审计日志"
        description="append-only 哈希链，只追加不可改；仅组长与管理员可读。"
        extra={
          <>
            <Button
              onClick={() => verifyQuery.refetch()}
              loading={verifyQuery.isFetching}
              icon={<SafetyCertificateOutlined />}
            >
              校验完整性
            </Button>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => auditQuery.refetch()}
              loading={auditQuery.isFetching}
            >
              刷新
            </Button>
          </>
        }
      />

      {verify ? (
        verify.valid ? (
          <Alert
            type="success"
            showIcon
            message="哈希链完整"
            description="从创世到当前最后一条事件序号连续、哈希匹配。"
          />
        ) : (
          <Alert
            type="error"
            showIcon
            message={`哈希链在第 ${verify.brokenAtSeq ?? '?'} 条断开`}
            description="审计记录可能被篡改或存在缺号，请立即排查。"
          />
        )
      ) : verifyError ? (
        <Alert
          type="warning"
          showIcon
          message="无法校验审计链"
          description={errorMessage(verifyError)}
        />
      ) : null}

      <div className="surface-card" style={{ overflow: 'hidden' }}>
        <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)' }}>
          <div className="toolbar">
            <Input.Search
              allowClear
              placeholder="按事件 / 动作 / 对象 / 操作人筛选"
              style={{ width: 320 }}
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
            />
            <span className="muted-3 tiny">显示最近 {formatNumber(rows.length)} 条</span>
            <div className="spacer" />
            <Button size="small" onClick={() => setLimit(100)} type={limit === 100 ? 'primary' : 'default'}>
              100 条
            </Button>
            <Button size="small" onClick={() => setLimit(500)} type={limit === 500 ? 'primary' : 'default'}>
              500 条
            </Button>
            <span className="muted-3 tiny">仅展示尾部，导出请联系管理员</span>
          </div>
        </div>

        <Table<AuditRow>
          rowKey="seq"
          size="small"
          columns={columns}
          dataSource={rows}
          loading={auditQuery.isLoading}
          locale={{
            emptyText: (
              <Empty description="暂无审计记录" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            ),
          }}
          scroll={{ x: 'max-content' }}
          pagination={{ pageSize: 50, showSizeChanger: false, size: 'small' }}
          expandable={{
            expandedRowRender: (record) => (
              <div>
                <div className="muted-3 tiny" style={{ marginBottom: 4 }}>
                  payload / 前一哈希
                </div>
                <pre className="sql-preview" style={{ maxHeight: 220, overflow: 'auto', fontSize: 11.5 }}>
                  {record.payload_json
                    ? (() => {
                        try {
                          return JSON.stringify(JSON.parse(record.payload_json), null, 2);
                        } catch {
                          return record.payload_json;
                        }
                      })()
                    : '—'}
                  {`\n\nprev_hash: ${record.prev_hash}`}
                </pre>
              </div>
            ),
          }}
        />
      </div>
    </div>
  );
}
