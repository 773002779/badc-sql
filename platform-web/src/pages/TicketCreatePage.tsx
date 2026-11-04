import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import {
  Alert,
  App,
  Button,
  DatePicker,
  Form,
  Input,
  Select,
  Switch,
  Tag,
} from 'antd';
import { ArrowLeftOutlined, SendOutlined, WarningOutlined } from '@ant-design/icons';
import dayjs from 'dayjs';
import { useMutation } from '@tanstack/react-query';
import { ticketApi } from '@/api/endpoints';
import { errorMessage } from '@/api/http';
import type { SubmitTicketRequest } from '@/api/types';
import { PageHeader } from '@/components/PageHeader';
import { SqlEditor } from '@/components/SqlEditor';
import { useDatasources, useSystems } from '@/hooks/useOrg';
import { countStatements, looksDestructive, splitSqlStatements } from '@/lib/sql';

interface FormValues {
  datasourceId: number;
  title: string;
  changeReason: string;
  script: string;
  rollbackSql?: string;
  rollbackPlan?: string;
  expectedExecuteAt: dayjs.Dayjs;
}

const DROP_TABLE_CONFIRMATION = '将从快照重建表结构';

export function TicketCreatePage() {
  const navigate = useNavigate();
  const { message } = App.useApp();
  const [form] = Form.useForm<FormValues>();
  const systemsQuery = useSystems();
  const datasourcesQuery = useDatasources();
  const [serverError, setServerError] = useState<string | null>(null);

  const script = Form.useWatch('script', form) ?? '';
  const statementCount = useMemo(() => countStatements(script), [script]);
  const destructive = useMemo(() => looksDestructive(script), [script]);

  const systemName = useMemo(() => {
    const map = new Map<number, string>();
    (systemsQuery.data ?? []).forEach((system) => map.set(system.id, system.name));
    return map;
  }, [systemsQuery.data]);

  const datasourceOptions = useMemo(
    () =>
      (datasourcesQuery.data ?? []).map((item) => ({
        value: item.id,
        label: `${item.name} · ${item.dbName}`,
        systemId: item.systemId,
      })),
    [datasourcesQuery.data],
  );

  const submitMutation = useMutation({
    mutationFn: (values: FormValues) => {
      const statements = splitSqlStatements(values.script);
      const body: SubmitTicketRequest = {
        datasourceId: values.datasourceId,
        title: values.title.trim(),
        changeReason: values.changeReason.trim(),
        rollbackSql: values.rollbackSql?.trim() || null,
        rollbackPlan: values.rollbackPlan?.trim() || null,
        statements,
        expectedExecuteAt: values.expectedExecuteAt.toISOString(),
      };
      return ticketApi.submit(body);
    },
    onSuccess: (result) => {
      message.success(`工单已提交，风险等级 ${result.riskLevel}`);
      navigate(`/tickets/${result.id}`);
    },
    onError: (error) => setServerError(errorMessage(error)),
  });

  const handleFinish = (values: FormValues) => {
    setServerError(null);
    const statements = splitSqlStatements(values.script);
    if (statements.length === 0) {
      setServerError('请至少填写一条 SQL 语句');
      return;
    }
    if (destructive && !(values.rollbackPlan ?? '').includes(DROP_TABLE_CONFIRMATION)) {
      setServerError(
        `包含 drop / truncate 等结构性破坏语句时，回滚方案里必须写明确认语：${DROP_TABLE_CONFIRMATION}`,
      );
      return;
    }
    submitMutation.mutate(values);
  };

  return (
    <div className="page">
      <PageHeader
        title="新建变更工单"
        description="写操作必须走工单；DML 需填写回滚 SQL，DDL 需填写回滚方案。"
        extra={
          <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/tickets')}>
            返回列表
          </Button>
        }
      />

      {serverError ? (
        <Alert
          type="error"
          showIcon
          message="提交未通过"
          description={serverError}
          closable
          onClose={() => setServerError(null)}
        />
      ) : null}

      <Form<FormValues>
        form={form}
        layout="vertical"
        onFinish={handleFinish}
        requiredMark="optional"
        initialValues={{
          expectedExecuteAt: dayjs().add(1, 'hour').second(0),
          script: '',
        }}
      >
        <div className="surface-card pad stack" style={{ gap: 14 }}>
          <div>
            <h3 className="section-title">基本信息</h3>
            <p className="section-desc">系统与模块由数据源归属自动推导，无需手填。</p>
          </div>

          <div className="grid-2">
            <Form.Item
              name="datasourceId"
              label="目标数据源"
              rules={[{ required: true, message: '请选择数据源' }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                placeholder="选择数据源"
                loading={datasourcesQuery.isLoading}
                options={datasourceOptions}
                notFoundContent="当前账号暂无可见数据源"
                optionRender={(option) => (
                  <div style={{ display: 'flex', justifyContent: 'space-between', gap: 12 }}>
                    <span>{option.label}</span>
                    <span className="muted-3 tiny">
                      {systemName.get(option.data.systemId) ?? ''}
                    </span>
                  </div>
                )}
              />
            </Form.Item>

            <Form.Item
              name="expectedExecuteAt"
              label="期望执行时间"
              rules={[{ required: true, message: '请选择期望执行时间' }]}
              extra="非窗口期执行会自动提升风险等级"
            >
              <DatePicker
                showTime={{ format: 'HH:mm' }}
                format="YYYY-MM-DD HH:mm"
                style={{ width: '100%' }}
                disabledDate={(current) => current && current < dayjs().startOf('day')}
              />
            </Form.Item>
          </div>

          <Form.Item
            name="title"
            label="工单标题"
            rules={[
              { required: true, message: '请填写标题' },
              { max: 200, message: '标题最多 200 字' },
            ]}
          >
            <Input placeholder="例如：修正订单金额异常数据" maxLength={200} showCount />
          </Form.Item>

          <Form.Item
            name="changeReason"
            label="变更说明"
            rules={[
              { required: true, message: '请填写变更说明' },
              { max: 2000, message: '变更说明最多 2000 字' },
            ]}
          >
            <Input.TextArea
              rows={3}
              placeholder="业务背景、影响范围、为什么现在做"
              maxLength={2000}
              showCount
            />
          </Form.Item>
        </div>

        <div className="surface-card pad stack" style={{ gap: 12, marginTop: 16 }}>
          <div className="row" style={{ justifyContent: 'space-between' }}>
            <div>
              <h3 className="section-title">SQL 语句</h3>
              <p className="section-desc">多条语句用分号分隔；提交时按分号拆成独立语句。</p>
            </div>
            <Tag color={statementCount > 0 ? 'blue' : 'default'}>
              已识别 {statementCount} 条
            </Tag>
          </div>

          <Form.Item
            name="script"
            rules={[{ required: true, message: '请填写 SQL 语句' }]}
            style={{ marginBottom: 0 }}
          >
            <SqlEditor value={script} onChange={(value) => form.setFieldValue('script', value)} minHeight={220} />
          </Form.Item>

          {destructive ? (
            <Alert
              type="warning"
              showIcon
              icon={<WarningOutlined />}
              message="包含结构性破坏语句"
              description={`drop table / truncate / rename 等语句固定为 L3，必须组长终审；且数据无法一键回滚，回滚方案中需写明「${DROP_TABLE_CONFIRMATION}」。`}
            />
          ) : null}
        </div>

        <div className="surface-card pad stack" style={{ gap: 12, marginTop: 16 }}>
          <div>
            <h3 className="section-title">回滚方案</h3>
            <p className="section-desc">
              平台只保留结构快照，不备份行数据：DML 必须自带回滚 SQL，DDL 必须写明回滚方案。
            </p>
          </div>

          <Form.Item
            name="rollbackSql"
            label="回滚 SQL（DML 必填）"
            extra="提交时会做语法校验，并检查是否命中工单涉及的表。"
          >
            <Input.TextArea rows={3} placeholder="例如：update t_order set amount = 100 where id = 42;" />
          </Form.Item>

          <Form.Item
            name="rollbackPlan"
            label="回滚方案（DDL 必填）"
            rules={[{ max: 20000, message: '回滚方案最多 20000 字' }]}
            extra={destructive ? `需包含确认语：${DROP_TABLE_CONFIRMATION}` : undefined}
          >
            <Input.TextArea
              rows={3}
              placeholder="回滚步骤、依赖的备份来源、预计耗时与责任人"
              maxLength={20000}
            />
          </Form.Item>

          <Form.Item label="结构快照" style={{ marginBottom: 0 }}>
            <div className="row">
              <Switch checked disabled />
              <span className="muted tiny">变更执行前强制抓取结构快照，不可关闭</span>
            </div>
          </Form.Item>
        </div>

        <div
          className="surface-card pad"
          style={{ marginTop: 16, display: 'flex', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}
        >
          <span className="muted-3 tiny">
            提交后由系统运维一级审核；L3 工单需组长终审，审批通过后由你或运维触发执行。
          </span>
          <div className="row">
            <Button onClick={() => navigate('/tickets')}>取消</Button>
            <Button
              type="primary"
              htmlType="submit"
              icon={<SendOutlined />}
              loading={submitMutation.isPending}
            >
              提交工单
            </Button>
          </div>
        </div>
      </Form>
    </div>
  );
}
