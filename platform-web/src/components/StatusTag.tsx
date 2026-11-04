import { Tag } from 'antd';

type TagColor = 'default' | 'processing' | 'success' | 'warning' | 'error' | 'blue' | 'purple' | 'cyan';

interface StatusTagProps {
  label: string;
  color?: TagColor;
  dot?: boolean;
}

/** 统一的状态标签：细边框 + 弱底色，避免整页都是高饱和色块。 */
export function StatusTag({ label, color = 'default', dot = false }: StatusTagProps) {
  return (
    <Tag
      color={color}
      bordered
      style={{
        marginInlineEnd: 0,
        borderRadius: 6,
        fontSize: 12,
        lineHeight: '20px',
        paddingInline: 8,
      }}
    >
      {dot ? <span style={{ marginRight: 4 }}>•</span> : null}
      {label}
    </Tag>
  );
}
