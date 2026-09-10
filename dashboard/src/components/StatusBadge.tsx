import { formatEnumLabel } from '../utils/labels'

const tones: Record<string, string> = {
  SETTLED: 'success', APPROVED: 'success', MATCH: 'success',
  PROCESSING: 'info', SCREENING: 'info', CREATED: 'info', OPEN: 'info',
  HELD: 'warning', CLOSE_MATCH: 'warning', DRAFT: 'warning',
  FAILED: 'danger', BLOCKED: 'danger', NO_MATCH: 'danger',
  RESOLVED: 'neutral', APPROVED_RULE: 'success',
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={`status status-${tones[status] ?? 'neutral'}`}>{formatEnumLabel(status)}</span>
}
