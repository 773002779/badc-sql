import { useMemo } from 'react';
import { Table } from 'antd';
import type { ColumnsType } from 'antd/es/table';
import type { QueryResult } from '@/api/types';

interface ResultTableProps {
  result: QueryResult;
}

type Row = { __key: number; [key: string]: unknown };

function renderCell(value: unknown) {
  if (value === null || value === undefined) {
    return <span className="cell-null">NULL</span>;
  }
  if (typeof value === 'object') {
    const hexLen = (value as { hexLen?: number }).hexLen;
    if (typeof hexLen === 'number') {
      return <span className="muted-3 mono">BLOB({hexLen} B)</span>;
    }
    return <span className="mono">{JSON.stringify(value)}</span>;
  }
  if (typeof value === 'boolean') {
    return <span className="mono">{String(value)}</span>;
  }
  const text = String(value);
  return (
    <span
      title={text.length > 120 ? text : undefined}
      style={{ display: 'inline-block', maxWidth: 480, verticalAlign: 'top' }}
    >
      {text}
    </span>
  );
}

/** 查询结果表格：列来自后端返回的列元数据，NULL 与空串区分展示。 */
export function ResultTable({ result }: ResultTableProps) {
  const columns = useMemo<ColumnsType<Row>>(() => {
    const dataColumns: ColumnsType<Row> = result.columns.map((column, index) => ({
      title: (
        <span>
          {column.name}
          <span className="muted-3 tiny" style={{ marginLeft: 6 }}>
            {column.type}
          </span>
        </span>
      ),
      key: `col-${index}`,
      dataIndex: String(index),
      ellipsis: false,
      render: renderCell,
      width: 160,
    }));

    return [
      {
        title: '#',
        key: '__rowNo',
        width: 56,
        fixed: 'left',
        render: (_value, _row, index) => (
          <span className="muted-3 mono">{index + 1}</span>
        ),
      },
      ...dataColumns,
    ];
  }, [result.columns]);

  const dataSource = useMemo<Row[]>(
    () =>
      result.rows.map((row, rowIndex) => {
        const record: Row = { __key: rowIndex };
        row.forEach((cell, colIndex) => {
          record[String(colIndex)] = cell;
        });
        return record;
      }),
    [result.rows],
  );

  return (
    <Table<Row>
      size="small"
      rowKey="__key"
      columns={columns}
      dataSource={dataSource}
      pagination={{
        defaultPageSize: 50,
        pageSizeOptions: [20, 50, 100, 200],
        showSizeChanger: true,
        showTotal: (total, range) => `${range[0]}-${range[1]} / 共 ${total} 行`,
        size: 'small',
      }}
      scroll={{ x: 'max-content' }}
      locale={{ emptyText: '没有返回数据' }}
    />
  );
}
