import { AlarmClock, Award, CheckCircle2, Circle, CircleDot, FileCheck2 } from 'lucide-react'
import { TASK_STATUS_LABELS } from '../constants/documents'
import Badge from './ui/Badge'

// Badge tone and icon per task status.
const STATUS_STYLES = {
  PENDING: { tone: 'neutral', icon: Circle },
  IN_PROGRESS: { tone: 'primary', icon: CircleDot },
  SUBMITTED: { tone: 'success', icon: FileCheck2 },
  GRADED: { tone: 'success', icon: Award },
  COMPLETED: { tone: 'success', icon: CheckCircle2 },
}

// Handed in after the deadline: its own chip, with a clock, so the color is not the only signal.
const LATE_STYLE = { tone: 'warning', icon: AlarmClock, label: 'Сдано с опозданием' }

/**
 * Look of a task's state: { tone, icon, label }, or null for a status that is not a task status (e.g. an
 * assignment's DRAFT/ACTIVE/CLOSED). A late hand-in waiting for a grade is "Сдано с опозданием".
 */
// eslint-disable-next-line react-refresh/only-export-components
export function taskState(status, late = false) {
  if (status === 'SUBMITTED' && late) return LATE_STYLE
  const style = STATUS_STYLES[status]
  return style ? { ...style, label: TASK_STATUS_LABELS[status] } : null
}

/** The task's state chip: Не начато / В работе / Сдано / Сдано с опозданием / Оценено (icon + color). */
export default function TaskStateBadge({ status, late = false, size, className = '' }) {
  const state = taskState(status, late)
  if (!state) return null
  return <Badge tone={state.tone} icon={state.icon} size={size} className={className}>{state.label}</Badge>
}
