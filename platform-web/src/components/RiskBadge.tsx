interface RiskBadgeProps {
  level?: string | null;
  /** 展示为「中风险」这类完整词 */
  long?: boolean;
}

const LABELS: Record<string, string> = {
  L0: '只读',
  L1: '低风险',
  L2: '中风险',
  L3: '高风险',
};

const SHORT: Record<string, string> = {
  L0: 'L0',
  L1: 'L1',
  L2: 'L2',
  L3: 'L3',
};

/** 风险等级徽标：L0 只读 / L1 低 / L2 中 / L3 高。 */
export function RiskBadge({ level, long = false }: RiskBadgeProps) {
  const key = (level ?? '').toUpperCase();
  if (!LABELS[key]) return <span className="muted-3">—</span>;
  return (
    <span className={`risk-badge risk-${key}`} title={LABELS[key]}>
      {long ? LABELS[key] : `${SHORT[key]} · ${LABELS[key]}`}
    </span>
  );
}
