import { AlarmClock, Award, CalendarX2, CheckCircle2, Circle, CircleDot, FileCheck2, Lock } from 'lucide-react'
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

// Nothing handed in, but not overdue either (utils/tasks deadlineExcuse): neutral chips, each with its own icon.
const EXCUSE_STYLES = {
  CLOSED: { tone: 'neutral', icon: Lock, label: 'Задание закрыто' },
  BEFORE_ENROLLMENT: { tone: 'neutral', icon: CalendarX2, label: 'Срок прошёл до записи' },
}

/**
 * Look of a task's state: { tone, icon, label }, or null for a status that is not a task status (e.g. an
 * assignment's DRAFT/ACTIVE/CLOSED). A late hand-in waiting for a grade is "Сдано с опозданием"; {@code excuse}
 * ('CLOSED' / 'BEFORE_ENROLLMENT' from deadlineExcuse) gives "Задание закрыто" / "Срок прошёл до записи".
 */
// eslint-disable-next-line react-refresh/only-export-components
export function taskState(status, late = false, excuse = null) {
  if (STATUS_STYLES[status] && EXCUSE_STYLES[excuse]) return EXCUSE_STYLES[excuse]
  if (status === 'SUBMITTED' && late) return LATE_STYLE
  const style = STATUS_STYLES[status]
  return style ? { ...style, label: TASK_STATUS_LABELS[status] } : null
}

/**
 * The task's state chip: Не начато / В работе / Сдано / Сдано с опозданием / Оценено, or Задание закрыто / Срок прошёл
 * до записи for {@code excuse} (icon + color); {@code title} is an optional tooltip.
 */
export default function TaskStateBadge({ status, late = false, excuse = null, size, title, className = '' }) {
  const state = taskState(status, late, excuse)
  if (!state) return null
  return <Badge tone={state.tone} icon={state.icon} size={size} title={title} className={className}>{state.label}</Badge>
}
