import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Modal,
  Result,
  Select,
  Table,
  Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import {
  DatabaseOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined,
} from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { tempExecApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { TempExecOutcome, TempExecStatementResult } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { RiskBadge } from '@/components/RiskBadge';
import { SqlEditor } from '@/components/SqlEditor';
import { useDatasources, useSystems } from '@/hooks/useOrg';
import { formatDuration } from '@/lib/format';
import { splitSqlStatements } from '@/lib/sql';

export function TempExecPage() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const systemsQuery = useSystems();
  const datasourcesQuery = useDatasources();

  const [datasourceId, setDatasourceId] = useState<number | undefined>();
  const [sql, setSql] = useState('');
  const [confirmOpen, setConfirmOpen] = useState(false);
  const [confirmed, setConfirmed] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [outcome, setOutcome] = useState<TempExecOutcome | null>(null);

  const systemName = useMemo(() => {
    const map = new Map<number, string>();
    (systemsQuery.data ?? []).forEach((system) => map.set(system.id, system.name));
    return map;
  }, [systemsQuery.data]);

  const options = useMemo(
    () =>
      (datasourcesQuery.data ?? []).map((item) => ({
        value: item.id,
        label: `${item.name} · ${item.dbName}`,
        systemId: item.systemId,
      })),
    [datasourcesQuery.data],
  );

  const statements = useMemo(() => splitSqlStatements(sql), [sql]);

  const mutation = useMutation({
    mutationFn: () => tempExecApi.execute(datasourceId as number, statements, true),
    onSuccess: (data) => {
      setOutcome(data);
      setConfirmOpen(false);
      setConfirmed(false);
      if (!data.executed) {
        message.warning('该操作风险等级为 L3，已自动生成工单，等待组长审批');
      } else {
        message.success('执行完成');
      }
    },
    onError: (e) => {
      setConfirmOpen(false);
      setConfirmed(false);
      setError(errorMessage(e));
    },
  });

  const start = () => {
    setError(null);
    if (!datasourceId) {
      setError('请先选择数据源');
      return;
    }
    if (statements.length === 0) {
      setError('请输入至少一条 SQL');
      return;
    }
    setConfirmOpen(true);
    setConfirmed(false);
  };

  const resultColumns: ColumnsType<TempExecStatementResult> = [
    {
      title: '#',
      dataIndex: 'seq',
      width: 56,
      render: (value: number) => <span className="mono muted-3">{value}</span>,
    },
    {
      title: '结果',
      dataIndex: 'success',
      width: 100,
      render: (value: boolean) =>
        value ? <Tag color="success">成功</Tag> : <Tag color="error">失败</Tag>,
    },
    {
      title: '影响行数',
      dataIndex: 'affectedRows',
      width: 110,
      align: 'right',
      render: (value: number) => <span className="mono">{value.toLocaleString('zh-CN')}</span>,
    },
    {
      title: '耗时',
      dataIndex: 'elapsedMs',
      width: 100,
      align: 'right',
      render: (value: number) => <span className="mono">{formatDuration(value)}</span>,
    },
    {
      title: '错误',
      dataIndex: 'errorMessage',
      render: (value: string | null) =>
        value ? <span className="danger-text tiny">{value}</span> : <span className="muted-3">—</span>,
    },
  ];

  const failed = outcome?.executed && outcome.results.some((item) => !item.success);

  return (
    <div className="page">
      <PageHeader
        title="临时执行"
        description="运维/组长专用：不建工单直接执行。L1/L2 本人确认即可，L3 自动转工单交组长审批批"
        extra={
          <Button
            icon={<ReloadOutlined />}
            onClick={() => datasourcesQuery.refetch()}
            loading={datasourcesQuery.isFetching}
          >
            刷新数据源          </Button>
        }
      />

      <Alert
        type="info"
        showIcon
        icon={<SafetyCertificateOutlined />}
        message="每次执行都会留痕：结构快照（DDL） 执行说明 + 全量审计，审计中标记 direct_exec。"
        description="研发与管理员无法使用该通道（接口级 403）；执行前请确认目标库与语句无误。"
      />

      {error ? (
        <Alert
          type="error"
          showIcon
          message="执行未完成"
          description={
            error.includes('权限') || error.includes('403')
              ? `${error}（临时执行仅对运维/组长开放）`
              : error
          }
          closable
          onClose={() => setError(null)}
        />
      ) : null}

      <div className="surface-card pad stack" style={{ gap: 12 }}>
        <div className="toolbar">
          <span className="muted tiny">目标数据源</span>
          <Select
            style={{ minWidth: 340 }}
            showSearch
            optionFilterProp="label"
            placeholder={datasourcesQuery.isLoading ? '加载中…' : '选择数据源'}
            value={datasourceId}
            options={options}
            onChange={setDatasourceId}
            loading={datasourcesQuery.isLoading}
            notFoundContent="没有可用的数据源"
            optionRender={(option) => (
              <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                <span>{option.label}</span>
                <span className="muted-3 tiny">
                  {systemName.get(option.data.systemId) ?? ''}
                </span>
              </div>
            )}
          />
          <div className="spacer" />
          <Tag icon={<DatabaseOutlined />} color={statements.length ? 'blue' : 'default'}>
            {statements.length} 条语句          </Tag>
          <Button type="primary" icon={<PlayCircleOutlined />} onClick={start} disabled={!datasourceId}>
            执行
          </Button>
        </div>

        <SqlEditor value={sql} onChange={setSql} onRun={start} minHeight={220} placeholder="-- 直接执行，不需要建工单" />

        <div className="muted-3 tiny">
          L1/L2 弹框确认后立即执行；L3 会生成一张待组长审批的工单，语句不会先执行。同一数据源写并发为 1。        </div>
      </div>

      {outcome?.executed ? (
        <div className="stack">
          <div className="surface-card pad">
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <h3 className="section-title">执行结果</h3>
              <div className="row">
                <RiskBadge level={outcome.riskLevel} long />
                {outcome.selfApproved ? <Tag color="purple">组长自确认</Tag> : null}
              </div>
            </div>
          </div>
          <div className="surface-card" style={{ overflow: 'hidden' }}>
            <Table<TempExecStatementResult>
              rowKey="seq"
              size="small"
              columns={resultColumns}
              dataSource={outcome.results}
              pagination={false}
            />
          </div>
          {failed ? (
            <Alert
              type="error"
              showIcon
              message="部分语句执行失败"
              description="失败语句之后的内容未执行；如需恢复，请按回滚方案人工处理或发起工单。"
            />
          ) : null}
        </div>
      ) : null}

      {outcome && !outcome.executed ? (
        <Result
          status="info"
          title="已转工单，等待组长审批"
          subTitle={`风险等级 ${outcome.riskLevel}，工单 #${outcome.ticketId}，一条语句都没有下发。`}
          extra={
            <Button type="primary" onClick={() => navigate(`/tickets/${outcome.ticketId}`)}>
              查看工单 #{outcome.ticketId}
            </Button>
          }
        />
      ) : null}

      <Modal
        title="确认临时执行"
        open={confirmOpen}
        onCancel={() => setConfirmOpen(false)}
        onOk={() => {
          if (!confirmed) {
            message.warning('请先勾选确认');
            return;
          }
          mutation.mutate();
        }}
        okText="确认执行"
        okButtonProps={{ danger: true }}
        confirmLoading={mutation.isPending}
        destroyOnHidden
      >
        <div className="stack" style={{ gap: 12 }}>
          <Alert
            type="warning"
            showIcon
            message={`将执行 ${statements.length} 条语句`}
            description="风险等级由后端实时计算：L1/L2 本人确认即执行；L3 会转为待审批工单，不会直接执行。"
          />
          <Alert
            type="info"
            showIcon
            message="执行前会自动抓取结构快照（涉及 DDL 时）"
            description="平台不备份行数据，误操作需依赖回滚 SQL 或云厂商备份恢复。"
          />
          <Checkbox checked={confirmed} onChange={(event) => setConfirmed(event.target.checked)}>
            我已确认目标数据源与语句，知悉执行产生的数据影响
          </Checkbox>
        </div>
      </Modal>
    </div>
  );
}
