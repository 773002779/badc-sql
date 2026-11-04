import { useMemo, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Checkbox,
  Empty,
  Form,
  Input,
  Modal,
  Select,
  Table,
  Tabs,
  Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { grantApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { GrantRequestItem } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { StatusTag } from '@/components/StatusTag';
import { useModules, useSystems } from '@/hooks/useOrg';
import { formatDateTime, formatNumber } from '@/lib/format';
import { GRANT_STATUS_META } from '@/lib/labels';

type Scope = 'mine' | 'pending';

interface RequestForm {
  systemId: number;
  moduleId: number;
  canExport: boolean;
  reason: string;
}

export function GrantsPage() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const systemsQuery = useSystems();
  const modulesQuery = useModules();
  const [scope, setScope] = useState<Scope>('mine');
  const [page, setPage] = useState(1);
  const [size, setSize] = useState(20);
  const [formOpen, setFormOpen] = useState(false);
  const [form] = Form.useForm<RequestForm>();
  const [formError, setFormError] = useState<string | null>(null);
  const [reviewTarget, setReviewTarget] = useState<{ item: GrantRequestItem; action: 'approve' | 'reject' } | null>(null);
  const [reviewComment, setReviewComment] = useState('');
  const [reviewError, setReviewError] = useState<string | null>(null);

  const grantsQuery = useQuery({
    queryKey: ['grants', 'me'],
    queryFn: grantApi.mine,
    staleTime: 30_000,
  });

  const requestsQuery = useQuery({
    queryKey: ['grant-requests', scope, page, size],
    queryFn: () => grantApi.requests(scope, page, size),
    placeholderData: (previous) => previous,
  });

  const systems = systemsQuery.data ?? [];
  const modules = modulesQuery.data ?? [];

  const systemName = useMemo(() => {
    const map = new Map<number, string>();
    systems.forEach((system) => map.set(system.id, system.name));
    return map;
  }, [systems]);

  const moduleMeta = useMemo(() => {
    const map = new Map<number, { name: string; systemId: number }>();
    modules.forEach((module) => map.set(module.id, { name: module.name, systemId: module.systemId }));
    return map;
  }, [modules]);

  const submitMutation = useMutation({
    mutationFn: (values: RequestForm) =>
      grantApi.submit(values.moduleId, Boolean(values.canExport), values.reason.trim()),
    onSuccess: () => {
      message.success('申请已提交，等待系统运维审批');
      setFormOpen(false);
      form.resetFields();
      queryClient.invalidateQueries({ queryKey: ['grant-requests'] });
    },
    onError: (error) => setFormError(errorMessage(error)),
  });

  const reviewMutation = useMutation({
    mutationFn: () => {
      if (!reviewTarget) throw new Error('缺少目标申请');
      if (reviewTarget.action === 'approve') {
        return grantApi.approve(reviewTarget.item.id, reviewComment.trim() || undefined);
      }
      return grantApi.reject(reviewTarget.item.id, reviewComment.trim());
    },
    onSuccess: () => {
      message.success(reviewTarget?.action === 'approve' ? '已通过授权申请' : '已驳回授权申请');
      setReviewTarget(null);
      setReviewComment('');
      setReviewError(null);
      queryClient.invalidateQueries({ queryKey: ['grant-requests'] });
      queryClient.invalidateQueries({ queryKey: ['grants', 'me'] });
    },
    onError: (error) => setReviewError(errorMessage(error)),
  });

  const columns: ColumnsType<GrantRequestItem> = [
    {
      title: '模块',
      dataIndex: 'moduleId',
      render: (value: number) => {
        const meta = moduleMeta.get(value);
        return (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
            <span>{meta?.name ?? `模块 #${value}`}</span>
            <span className="muted-3 tiny">
              {meta ? systemName.get(meta.systemId) ?? `系统 #${meta.systemId}` : ''}
            </span>
          </div>
        );
      },
    },
    {
      title: '申请权限',
      dataIndex: 'canExport',
      width: 130,
      render: (value: boolean) => (
        <div className="row" style={{ gap: 4 }}>
          <Tag color="blue">查询</Tag>
          {value ? <Tag color="purple">导出</Tag> : null}
        </div>
      ),
    },
    {
      title: '申请原因',
      dataIndex: 'reason',
      ellipsis: true,
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 110,
      render: (value: string) => {
        const meta = GRANT_STATUS_META[value];
        return <StatusTag label={meta?.label ?? value} color={meta?.color ?? 'default'} />;
      },
    },
    {
      title: '提交时间',
      dataIndex: 'createdAt',
      width: 168,
      render: (value: string) => <span className="mono tiny">{formatDateTime(value)}</span>,
    },
    {
      title: '审批意见',
      dataIndex: 'reviewComment',
      render: (value: string | null) =>
        value ? <span className="tiny">{value}</span> : <span className="muted-3">—</span>,
    },
  ];

  if (scope === 'pending') {
    columns.push({
      title: '',
      key: 'action',
      width: 150,
      render: (_value, record) =>
        record.status === 'PENDING' ? (
          <div className="row" style={{ gap: 4 }}>
            <Button
              size="small"
              type="primary"
              onClick={() => {
                setReviewTarget({ item: record, action: 'approve' });
                setReviewComment('');
                setReviewError(null);
              }}
            >
              通过
            </Button>
            <Button
              size="small"
              danger
              onClick={() => {
                setReviewTarget({ item: record, action: 'reject' });
                setReviewComment('');
                setReviewError(null);
              }}
            >
              驳回
            </Button>
          </div>
        ) : null,
    });
  }

  const items = requestsQuery.data?.items ?? [];

  return (
    <div className="page">
      <PageHeader
        title="权限申请"
        description="研发按系统 → 模块申请查询/导出权限；审批人是该系统的运维。"
        extra={
          <>
            <Button
              icon={<ReloadOutlined />}
              onClick={() => requestsQuery.refetch()}
              loading={requestsQuery.isFetching}
            >
              刷新
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                setFormError(null);
                form.resetFields();
                setFormOpen(true);
              }}
            >
              申请权限
            </Button>
          </>
        }
      />

      <div className="grid-2">
        <div className="stat-card">
          <div className="stat-label">已授权模块</div>
          <div className="stat-value">{grantsQuery.data ? grantsQuery.data.modules.length : '—'}</div>
          <div className="muted-3 tiny">可查询该模块下已挂载的数据源</div>
        </div>
        <div className="stat-card">
          <div className="stat-label">可见数据源</div>
          <div className="stat-value">
            {grantsQuery.data ? grantsQuery.data.datasources.length : '—'}
          </div>
          <div className="muted-3 tiny">未授权的库不会出现在列表与搜索里</div>
        </div>
      </div>

      <div className="surface-card" style={{ overflow: 'hidden' }}>
        <Tabs
          activeKey={scope}
          onChange={(key) => {
            setScope(key as Scope);
            setPage(1);
          }}
          items={[
            { key: 'mine', label: '我的申请' },
            { key: 'pending', label: '待我审批' },
          ]}
          tabBarStyle={{ paddingInline: 16, marginBottom: 0 }}
        />
        <Table<GrantRequestItem>
          rowKey="id"
          size="small"
          columns={columns}
          dataSource={items}
          loading={requestsQuery.isLoading}
          locale={{
            emptyText: (
              <Empty
                description={scope === 'mine' ? '还没有提交过权限申请' : '暂无待审批的申请'}
                image={Empty.PRESENTED_IMAGE_SIMPLE}
              />
            ),
          }}
          scroll={{ x: 'max-content' }}
          pagination={{
            current: page,
            pageSize: size,
            total: requestsQuery.data?.total ?? 0,
            showSizeChanger: true,
            showTotal: (total) => `共 ${formatNumber(total)} 条`,
            onChange: (nextPage, nextSize) => {
              setPage(nextPage);
              setSize(nextSize);
            },
          }}
        />
      </div>

      <Modal
        title="申请模块权限"
        open={formOpen}
        onCancel={() => setFormOpen(false)}
        onOk={() => form.submit()}
        okText="提交申请"
        confirmLoading={submitMutation.isPending}
        destroyOnHidden
      >
        <Form<RequestForm>
          form={form}
          layout="vertical"
          requiredMark={false}
          initialValues={{ canExport: false }}
          onFinish={(values) => {
            setFormError(null);
            submitMutation.mutate(values);
          }}
        >
          <Form.Item name="systemId" label="系统" rules={[{ required: true, message: '请选择系统' }]}>
            <Select
              placeholder="选择系统"
              options={systems.map((system) => ({ value: system.id, label: system.name }))}
              onChange={() => form.setFieldValue('moduleId', undefined)}
            />
          </Form.Item>

          <Form.Item
            noStyle
            shouldUpdate={(previous, current) => previous.systemId !== current.systemId}
          >
            {({ getFieldValue }) => {
              const systemId = getFieldValue('systemId') as number | undefined;
              const options = modules
                .filter((module) => module.systemId === systemId)
                .map((module) => ({ value: module.id, label: module.name }));
              return (
                <Form.Item
                  name="moduleId"
                  label="模块"
                  rules={[{ required: true, message: '请选择模块' }]}
                >
                  <Select
                    placeholder={systemId ? '选择模块' : '请先选择系统'}
                    disabled={!systemId}
                    options={options}
                    notFoundContent="该系统还没有模块"
                  />
                </Form.Item>
              );
            }}
          </Form.Item>

          <Form.Item name="canExport" valuePropName="checked">
            <Checkbox>同时申请导出权限（超过 1000 行需要）</Checkbox>
          </Form.Item>

          <Form.Item
            name="reason"
            label="申请原因"
            rules={[{ required: true, message: '请填写申请原因' }]}
          >
            <Input.TextArea rows={3} placeholder="用于什么业务、需要多久" maxLength={500} showCount />
          </Form.Item>

          {formError ? <Alert type="error" showIcon message={formError} /> : null}
        </Form>
      </Modal>

      <Modal
        title={reviewTarget?.action === 'approve' ? '通过授权申请' : '驳回授权申请'}
        open={Boolean(reviewTarget)}
        onCancel={() => setReviewTarget(null)}
        onOk={() => {
          if (reviewTarget?.action === 'reject' && !reviewComment.trim()) {
            setReviewError('驳回必须填写原因');
            return;
          }
          reviewMutation.mutate();
        }}
        okText={reviewTarget?.action === 'approve' ? '确认通过' : '确认驳回'}
        okButtonProps={{ danger: reviewTarget?.action === 'reject' }}
        confirmLoading={reviewMutation.isPending}
        destroyOnHidden
      >
        <div className="stack" style={{ gap: 12 }}>
          <div className="muted">
            模块：{moduleMeta.get(reviewTarget?.item.moduleId ?? -1)?.name ?? '—'}
            {reviewTarget?.item.canExport ? '（含导出）' : ''}
          </div>
          <div>
            <div className="muted tiny" style={{ marginBottom: 6 }}>
              审批意见{reviewTarget?.action === 'reject' ? '（必填）' : '（可选）'}
            </div>
            <Input.TextArea
              rows={3}
              value={reviewComment}
              onChange={(event) => setReviewComment(event.target.value)}
              placeholder="说明授权范围或驳回原因"
            />
          </div>
          {reviewError ? <Alert type="error" showIcon message={reviewError} /> : null}
        </div>
      </Modal>
    </div>
  );
}
