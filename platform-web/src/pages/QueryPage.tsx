import { useEffect, useMemo, useState } from 'react';
import { Alert, Button, Empty, Select, Segmented, Spin, Tooltip } from 'antd';
import { ClearOutlined, PlayCircleOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation } from '@tanstack/react-query';
import { queryApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { QueryResult } from '@/api/types';
import { ResultTable } from '@/components/ResultTable';
import { SqlEditor } from '@/components/SqlEditor';
import { PageHeader } from '@/components/PageHeader';
import { useDatasources, useSystems } from '@/hooks/useOrg';
import { formatDuration, formatNumber } from '@/lib/format';

const SQL_KEY = 'badcsql.query.sql';
const DS_KEY = 'badcsql.query.datasource';

const SAMPLE_SQL = 'select 1 as demo;';

export function QueryPage() {
  const systemsQuery = useSystems();
  const datasourcesQuery = useDatasources();

  const [datasourceId, setDatasourceId] = useState<number | undefined>(() => {
    const raw = localStorage.getItem(DS_KEY);
    return raw ? Number(raw) : undefined;
  });
  const [sql, setSql] = useState(() => localStorage.getItem(SQL_KEY) ?? '');
  const [result, setResult] = useState<QueryResult | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [view, setView] = useState<'result' | 'sql'>('result');

  const datasources = datasourcesQuery.data ?? [];
  const systems = systemsQuery.data ?? [];

  const systemName = useMemo(() => {
    const map = new Map<number, string>();
    systems.forEach((system) => map.set(system.id, system.name));
    return map;
  }, [systems]);

  const options = useMemo(() => {
    const groups = new Map<number, typeof datasources>();
    datasources.forEach((item) => {
      const list = groups.get(item.systemId) ?? [];
      list.push(item);
      groups.set(item.systemId, list);
    });
    return [...groups.entries()].map(([systemId, items]) => ({
      label: systemName.get(systemId) ?? `系统 #${systemId}`,
      options: items.map((item) => ({
        value: item.id,
        label: `${item.name} · ${item.dbName}`,
      })),
    }));
  }, [datasources, systemName]);

  // 已保存的数据源可能因权限回收而不可见：回落到第一项，避免选了个看不见的库。
  useEffect(() => {
    if (!datasources.length) return;
    if (datasourceId && datasources.some((item) => item.id === datasourceId)) return;
    setDatasourceId(datasources[0].id);
  }, [datasources, datasourceId]);

  useEffect(() => {
    if (datasourceId) localStorage.setItem(DS_KEY, String(datasourceId));
  }, [datasourceId]);

  useEffect(() => {
    localStorage.setItem(SQL_KEY, sql);
  }, [sql]);

  const runMutation = useMutation({
    mutationFn: () => queryApi.run(datasourceId as number, sql.trim()),
    onSuccess: (data) => {
      setResult(data);
      setError(null);
      setView('result');
    },
    onError: (e) => {
      setError(errorMessage(e));
      setResult(null);
    },
  });

  const canRun = Boolean(datasourceId) && sql.trim().length > 0 && !runMutation.isPending;

  const handleRun = () => {
    if (!canRun) {
      if (!datasourceId) setError('请先选择数据源');
      else if (!sql.trim()) setError('请输入 SQL');
      return;
    }
    runMutation.mutate();
  };

  const handleClear = () => {
    setSql('');
    setResult(null);
    setError(null);
  };

  const selectedDatasource = datasources.find((item) => item.id === datasourceId);

  return (
    <div className="page">
      <PageHeader
        title="查询工作台"
        description="只读查询走平台账号，结果不落库；写操作请提交变更工单。"
        extra={
          <>
            <Button icon={<ClearOutlined />} onClick={handleClear} disabled={!sql && !result}>
              清空
            </Button>
            <Button
              type="primary"
              icon={<PlayCircleOutlined />}
              onClick={handleRun}
              loading={runMutation.isPending}
              disabled={!canRun}
            >
              运行查询
            </Button>
          </>
        }
      />

      <div className="surface-card pad stack" style={{ gap: 12 }}>
        <div className="toolbar">
          <span className="muted tiny">数据源</span>
          <Select
            style={{ minWidth: 320 }}
            showSearch
            optionFilterProp="label"
            placeholder={datasourcesQuery.isLoading ? '加载中…' : '选择数据源'}
            value={datasourceId}
            options={options}
            onChange={setDatasourceId}
            loading={datasourcesQuery.isLoading}
            notFoundContent={
              datasourcesQuery.isLoading ? <Spin size="small" /> : '当前账号暂无可见数据源'
            }
          />
          {selectedDatasource ? (
            <span className="muted-3 tiny">
              {systemName.get(selectedDatasource.systemId) ?? `系统 #${selectedDatasource.systemId}`}
              {' · '}
              {selectedDatasource.type}
              {' · '}
              {selectedDatasource.dbName}
            </span>
          ) : null}
          <div className="spacer" />
          <Tooltip title="重新加载可见数据源">
            <Button
              type="text"
              icon={<ReloadOutlined />}
              onClick={() => datasourcesQuery.refetch()}
              loading={datasourcesQuery.isFetching}
            />
          </Tooltip>
        </div>

        <SqlEditor value={sql} onChange={setSql} onRun={handleRun} placeholder={SAMPLE_SQL} />

        <div className="row" style={{ justifyContent: 'space-between' }}>
          <span className="muted-3 tiny">
            仅接受 select / show / desc / explain；Ctrl + Enter 运行。研发查询自动追加 LIMIT 1001。
          </span>
          <Button
            type="link"
            size="small"
            onClick={() => setSql(SAMPLE_SQL)}
            style={{ paddingInline: 0 }}
          >
            填入示例
          </Button>
        </div>
      </div>

      {error ? (
        <Alert
          type="error"
          showIcon
          message="查询失败"
          description={error}
          closable
          onClose={() => setError(null)}
        />
      ) : null}

      {result ? (
        <div className="surface-card" style={{ overflow: 'hidden' }}>
          <div className="result-meta">
            <span>
              共 <strong>{formatNumber(result.rows.length)}</strong> 行
            </span>
            <span>耗时 {formatDuration(result.elapsedMs)}</span>
            <span className="mono">resultId {result.resultId.slice(0, 8)}</span>
            <div className="spacer" style={{ flex: 1 }} />
            <Segmented
              size="small"
              value={view}
              onChange={(value) => setView(value as 'result' | 'sql')}
              options={[
                { label: '结果', value: 'result' },
                { label: 'SQL', value: 'sql' },
              ]}
            />
          </div>

          {result.truncated ? (
            <Alert
              type="warning"
              showIcon
              banner
              message="结果已按行数上限截断，仅展示前若干行。需要完整数据请联系运维导出。"
            />
          ) : null}

          {view === 'sql' ? (
            <div style={{ padding: 14 }}>
              <pre className="sql-preview">{sql}</pre>
            </div>
          ) : result.rows.length === 0 ? (
            <div style={{ padding: 32 }}>
              <Empty description="查询成功，但没有返回数据" image={Empty.PRESENTED_IMAGE_SIMPLE} />
            </div>
          ) : (
            <ResultTable result={result} />
          )}
        </div>
      ) : null}

      {!result && !error && !runMutation.isPending ? (
        <div className="surface-card" style={{ padding: '48px 0' }}>
          <Empty
            image={Empty.PRESENTED_IMAGE_SIMPLE}
            description={
              <span className="muted">
                选择数据源并输入 SQL 开始查询
                <br />
                <span className="tiny muted-3">结果仅保留 30 分钟，过期后请重新查询</span>
              </span>
            }
          />
        </div>
      ) : null}
    </div>
  );
}
