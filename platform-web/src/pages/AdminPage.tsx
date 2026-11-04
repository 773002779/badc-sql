import { useMemo, useState } from 'react';
import {
  Alert,
  App,
  Button,
  Empty,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Table,
  Tabs,
  Tag,
} from 'antd';
import type { ColumnsType } from 'antd/es/table';
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { orgApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { DataSourceView, ModuleEntity, SystemEntity } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { useDatasources, useModules, useSystems } from '@/hooks/useOrg';
import { formatNumber } from '@/lib/format';

type TabKey = 'systems' | 'modules' | 'datasources';

export function AdminPage() {
  const { message } = App.useApp();
  const queryClient = useQueryClient();
  const systemsQuery = useSystems();
  const modulesQuery = useModules();
  const datasourcesQuery = useDatasources();

  const [tab, setTab] = useState<TabKey>('systems');
  const [systemFilter, setSystemFilter] = useState<number | undefined>();
  const [systemModal, setSystemModal] = useState(false);
  const [moduleModal, setModuleModal] = useState(false);
  const [datasourceModal, setDatasourceModal] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [systemForm] = Form.useForm<{ name: string }>();
  const [moduleForm] = Form.useForm<{ systemId: number; name: string }>();
  const [datasourceForm] = Form.useForm<{
    systemId: number;
    name: string;
    dbName: string;
    host: string;
    port: number;
    charset: string;
    timeZone: string;
  }>();

  const systems = systemsQuery.data ?? [];
  const modules = modulesQuery.data ?? [];
  const datasources = datasourcesQuery.data ?? [];

  const systemName = useMemo(() => {
    const map = new Map<number, string>();
    systems.forEach((system) => map.set(system.id, system.name));
    return map;
  }, [systems]);

  const refreshAll = () => {
    queryClient.invalidateQueries({ queryKey: ['org'] });
  };

  const createSystemMutation = useMutation({
    mutationFn: (values: { name: string }) => orgApi.createSystem(values.name.trim()),
    onSuccess: () => {
      message.success('系统已创建');
      setSystemModal(false);
      systemForm.resetFields();
      refreshAll();
    },
    onError: (e) => setError(errorMessage(e)),
  });

  const createModuleMutation = useMutation({
    mutationFn: (values: { systemId: number; name: string }) =>
      orgApi.createModule(values.systemId, values.name.trim()),
    onSuccess: () => {
      message.success('模块已创建');
      setModuleModal(false);
      moduleForm.resetFields();
      refreshAll();
    },
    onError: (e) => setError(errorMessage(e)),
  });

  const createDatasourceMutation = useMutation({
    mutationFn: (values: {
      systemId: number;
      name: string;
      dbName: string;
      host: string;
      port: number;
      charset: string;
      timeZone: string;
    }) =>
      orgApi.createDatasource({
        systemId: values.systemId,
        name: values.name.trim(),
        dbName: values.dbName.trim(),
        type: 'mysql',
        host: values.host.trim(),
        port: values.port,
        charset: values.charset,
        timeZone: values.timeZone,
      }),
    onSuccess: () => {
      message.success('数据源已创建，请继续配置账号集');
      setDatasourceModal(false);
      datasourceForm.resetFields();
      refreshAll();
    },
    onError: (e) => setError(errorMessage(e)),
  });

  const deleteDatasourceMutation = useMutation({
    mutationFn: (id: number) => orgApi.deleteDatasource(id),
    onSuccess: () => {
      message.success('数据源已删除');
      refreshAll();
    },
    onError: (e) => setError(errorMessage(e)),
  });

  const openModal = (kind: 'system' | 'module' | 'datasource') => {
    setError(null);
    if (kind === 'system') {
      systemForm.resetFields();
      setSystemModal(true);
    } else if (kind === 'module') {
      moduleForm.resetFields();
      moduleForm.setFieldValue('systemId', systemFilter ?? systems[0]?.id);
      setModuleModal(true);
    } else {
      datasourceForm.resetFields();
      datasourceForm.setFieldsValue({
        systemId: systemFilter ?? systems[0]?.id,
        port: 3306,
        charset: 'utf8mb4',
        timeZone: 'Asia/Shanghai',
      });
      setDatasourceModal(true);
    }
  };

  const systemColumns: ColumnsType<SystemEntity> = [
    { title: 'ID', dataIndex: 'id', width: 90, render: (value: number) => <span className="mono tiny">#{value}</span> },
    { title: '系统名称', dataIndex: 'name' },
    {
      title: '数据源',
      key: 'datasources',
      width: 120,
      render: (_value, record) => (
        <span className="muted">{datasources.filter((item) => item.systemId === record.id).length}</span>
      ),
    },
    {
      title: '模块',
      key: 'modules',
      width: 120,
      render: (_value, record) => (
        <span className="muted">{modules.filter((item) => item.systemId === record.id).length}</span>
      ),
    },
  ];

  const moduleColumns: ColumnsType<ModuleEntity> = [
    { title: 'ID', dataIndex: 'id', width: 90, render: (value: number) => <span className="mono tiny">#{value}</span> },
    { title: '模块名称', dataIndex: 'name' },
    {
      title: '所属系统',
      dataIndex: 'systemId',
      width: 200,
      render: (value: number) => systemName.get(value) ?? `#${value}`,
    },
    {
      title: '已挂载数据源',
      key: 'mounted',
      width: 160,
      render: () => <span className="muted-3 tiny">通过「模块 → 数据源」挂载维护</span>,
    },
  ];

  const datasourceColumns: ColumnsType<DataSourceView> = [
    { title: 'ID', dataIndex: 'id', width: 80, render: (value: number) => <span className="mono tiny">#{value}</span> },
    {
      title: '名称',
      dataIndex: 'name',
      render: (value: string, record) => (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
          <span style={{ fontWeight: 550 }}>{value}</span>
          <span className="muted-3 tiny mono">{record.dbName}</span>
        </div>
      ),
    },
    {
      title: '归属系统',
      dataIndex: 'systemId',
      width: 180,
      render: (value: number) => systemName.get(value) ?? `#${value}`,
    },
    {
      title: '类型',
      dataIndex: 'type',
      width: 100,
      render: (value: string) => <Tag>{value}</Tag>,
    },
    {
      title: '连接信息',
      key: 'connection',
      width: 140,
      render: () => <span className="muted-3 tiny">仅运维可见</span>,
    },
    {
      title: '',
      key: 'action',
      width: 90,
      render: (_value, record) => (
        <Popconfirm
          title="删除数据源"
          description={`确认删除 ${record.name}？该操作不可恢复。`}
          okText="删除"
          okButtonProps={{ danger: true }}
          onConfirm={() => deleteDatasourceMutation.mutate(record.id)}
        >
          <Button type="text" danger size="small" icon={<DeleteOutlined />} />
        </Popconfirm>
      ),
    },
  ];

  return (
    <div className="page">
      <PageHeader
        title="系统配置"
        description="系统、模块与数据源维护；连接串与账号仅运维/管理员可见，研发侧永不返回。"
        extra={
          <>
            <Button icon={<ReloadOutlined />} onClick={refreshAll}>
              刷新
            </Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => openModal(tab === 'systems' ? 'system' : tab === 'modules' ? 'module' : 'datasource')}>
              新建
            </Button>
          </>
        }
      />

      {error ? (
        <Alert
          type="error"
          showIcon
          message="操作未完成"
          description={
            error.includes('权限') || error.includes('管理员') || error.includes('运维')
              ? `${error}（当前账号无权执行该配置操作）`
              : error
          }
          closable
          onClose={() => setError(null)}
        />
      ) : null}

      <div className="surface-card" style={{ overflow: 'hidden' }}>
        <Tabs
          activeKey={tab}
          onChange={(key) => setTab(key as TabKey)}
          tabBarStyle={{ paddingInline: 16, marginBottom: 0 }}
          items={[
            {
              key: 'systems',
              label: `系统（${systems.length}）`,
              children: (
                <Table<SystemEntity>
                  rowKey="id"
                  size="small"
                  columns={systemColumns}
                  dataSource={systems}
                  loading={systemsQuery.isLoading}
                  pagination={false}
                  locale={{ emptyText: <Empty description="还没有系统" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
                />
              ),
            },
            {
              key: 'modules',
              label: `模块（${modules.length}）`,
              children: (
                <div>
                  <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)' }}>
                    <Select
                      allowClear
                      placeholder="全部系统"
                      style={{ width: 220 }}
                      value={systemFilter}
                      onChange={setSystemFilter}
                      options={systems.map((system) => ({ value: system.id, label: system.name }))}
                    />
                  </div>
                  <Table<ModuleEntity>
                    rowKey="id"
                    size="small"
                    columns={moduleColumns}
                    dataSource={
                      systemFilter ? modules.filter((item) => item.systemId === systemFilter) : modules
                    }
                    loading={modulesQuery.isLoading}
                    pagination={false}
                    locale={{ emptyText: <Empty description="还没有模块" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
                  />
                </div>
              ),
            },
            {
              key: 'datasources',
              label: `数据源（${datasources.length}）`,
              children: (
                <div>
                  <div style={{ padding: '12px 16px', borderBottom: '1px solid var(--border)' }}>
                    <span className="muted-3 tiny">
                      共 {formatNumber(datasources.length)} 个数据源；账号集与连通测试请在数据源详情维护。                    </span>
                  </div>
                  <Table<DataSourceView>
                    rowKey="id"
                    size="small"
                    columns={datasourceColumns}
                    dataSource={
                      systemFilter
                        ? datasources.filter((item) => item.systemId === systemFilter)
                        : datasources
                    }
                    loading={datasourcesQuery.isLoading}
                    pagination={false}
                    scroll={{ x: 'max-content' }}
                    locale={{ emptyText: <Empty description="还没有数据源" image={Empty.PRESENTED_IMAGE_SIMPLE} /> }}
                  />
                </div>
              ),
            },
          ]}
        />
      </div>

      <Modal
        title="新建系统"
        open={systemModal}
        onCancel={() => setSystemModal(false)}
        onOk={() => systemForm.submit()}
        okText="创建"
        confirmLoading={createSystemMutation.isPending}
        destroyOnHidden
      >
        <Form form={systemForm} layout="vertical" onFinish={(values) => createSystemMutation.mutate(values)}>
          <Form.Item name="name" label="系统名称" rules={[{ required: true, message: '请输入系统名称' }]}>
            <Input placeholder="例如：订单中心" maxLength={100} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建模块"
        open={moduleModal}
        onCancel={() => setModuleModal(false)}
        onOk={() => moduleForm.submit()}
        okText="创建"
        confirmLoading={createModuleMutation.isPending}
        destroyOnHidden
      >
        <Form form={moduleForm} layout="vertical" onFinish={(values) => createModuleMutation.mutate(values)}>
          <Form.Item name="systemId" label="所属系统" rules={[{ required: true, message: '请选择系统' }]}>
            <Select options={systems.map((system) => ({ value: system.id, label: system.name }))} />
          </Form.Item>
          <Form.Item name="name" label="模块名称" rules={[{ required: true, message: '请输入模块名称' }]}>
            <Input placeholder="例如：订单查询" maxLength={100} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title="新建数据源"
        open={datasourceModal}
        onCancel={() => setDatasourceModal(false)}
        onOk={() => datasourceForm.submit()}
        okText="创建"
        confirmLoading={createDatasourceMutation.isPending}
        destroyOnHidden
        width={620}
      >
        <Form
          form={datasourceForm}
          layout="vertical"
          onFinish={(values) => createDatasourceMutation.mutate(values)}
        >
          <Form.Item name="systemId" label="归属系统" rules={[{ required: true, message: '请选择系统' }]}>
            <Select options={systems.map((system) => ({ value: system.id, label: system.name }))} />
          </Form.Item>
          <div className="grid-2">
            <Form.Item name="name" label="展示名称" rules={[{ required: true, message: '请输入名称' }]}>
              <Input placeholder="例如：订单库-生产" />
            </Form.Item>
            <Form.Item name="dbName" label="库名" rules={[{ required: true, message: '请输入库名' }]}>
              <Input placeholder="例如：order_db" />
            </Form.Item>
          </div>
          <div className="grid-2">
            <Form.Item name="host" label="主机" rules={[{ required: true, message: '请输入主机' }]}>
              <Input placeholder="内网地址" />
            </Form.Item>
            <Form.Item name="port" label="端口" rules={[{ required: true, message: '请输入端口' }]}>
              <InputNumber min={1} max={65535} style={{ width: '100%' }} />
            </Form.Item>
          </div>
          <div className="grid-2">
            <Form.Item name="charset" label="字符集">
              <Input />
            </Form.Item>
            <Form.Item name="timeZone" label="连接时区">
              <Input placeholder="Asia/Shanghai" />
            </Form.Item>
          </div>
          <Alert
            type="info"
            showIcon
            message="创建后需配置 readonly / dml / ddl 账号集，研发才能查询、工单才能执行。"
          />
        </Form>
      </Modal>
    </div>
  );
}
