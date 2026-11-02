import { AlarmClock, AlertTriangle, CalendarClock, CalendarOff, ClipboardList, FlaskConical, FolderKanban, GraduationCap, ListChecks, PenLine, Users } from 'lucide-react'
import { formatDateTime } from './dates'
// Tasks carry no type of their own; the label is guessed from the words the AI used in the title and description.
// Each type has a badge tone and an icon (see Badge).
const TASK_TYPES = [
  { pattern: /лаборатор|лаб\.|\blab/i, label: 'Лабораторная', tone: 'accent', icon: FlaskConical },
  { pattern: /проект|project/i, label: 'Проект', tone: 'primary', icon: FolderKanban },
  { pattern: /экзам|зач[её]т|итогов|exam|final/i, label: 'Экзамен', tone: 'danger', icon: GraduationCap },
  { pattern: /контрольн|тест|quiz|midterm|рубежн/i, label: 'Контроль', tone: 'warning', icon: ListChecks },
  { pattern: /эссе|реферат|доклад|отч[её]т|essay|report/i, label: 'Письменная работа', tone: 'neutral', icon: PenLine },
  { pattern: /практи|семинар|practice|seminar/i, label: 'Практика', tone: 'success', icon: Users },
]

const DEFAULT_TYPE = { label: 'Задание', tone: 'neutral', icon: ClipboardList }

export function getTaskType(task) {
  const text = `${task.title ?? ''} ${task.description ?? ''}`
  return TASK_TYPES.find((type) => type.pattern.test(text)) ?? DEFAULT_TYPE
}

const HOUR_MS = 60 * 60 * 1000
const DAY_MS = 24 * HOUR_MS

/** The deadline has passed by the browser's clock: a hint for the UI only, the server decides. */
export const isPastDeadline = (deadline, now = Date.now()) => Boolean(deadline) && now > new Date(deadline).getTime()

/** The student is done with it: handed in, graded, or (legacy) completed. No "overdue", not upcoming. */
export const isTaskFinished = (status) => status === 'SUBMITTED' || status === 'GRADED' || status === 'COMPLETED'

// Badge tone and icon per deadline state: the icon tells overdue from soon without the color.
const TONES = {
  overdue: { tone: 'danger', icon: AlertTriangle },
  soon: { tone: 'warning', icon: AlarmClock },
  later: { tone: 'neutral', icon: CalendarClock },
  none: { tone: 'neutral', icon: CalendarOff },
}

/** "1 день", "2 дня", "5 дней": the Russian plural of {@code n}. */
export function plural(n, one, few, many) {
  const tens = n % 100
  const units = n % 10
  if (tens >= 11 && tens <= 14) return `${n} ${many}`
  if (units === 1) return `${n} ${one}`
  if (units >= 2 && units <= 4) return `${n} ${few}`
  return `${n} ${many}`
}

/**
 * Why a student's task with nothing handed in is never overdue, like the server's OVERDUE rule: 'CLOSED' (the
 * assignment takes no more work) or 'BEFORE_ENROLLMENT' (the deadline had passed when the student enrolled); null
 * otherwise, and always for handed-in work.
 */
export function deadlineExcuse(task) {
  if (isTaskFinished(task.status)) return null
  if (task.assignmentStatus === 'CLOSED') return 'CLOSED'
  if (task.deadlineBeforeEnrollment) return 'BEFORE_ENROLLMENT'
  return null
}

/** Not handed in and the deadline has passed: highlighted in "Мои задачи". A browser-side hint, like the server's rule. */
export const isOverdue = (task, now = Date.now()) =>
  !isTaskFinished(task.status) && !deadlineExcuse(task) && isPastDeadline(task.deadline, now)

// Calendar days from the day of {@code now} to the day of {@code date}, both as local wall-clock days.
const calendarDaysBetween = (now, date) => {
  const start = new Date(now)
  start.setHours(0, 0, 0, 0)
  const end = new Date(date)
  end.setHours(0, 0, 0, 0)
  return Math.round((end.getTime() - start.getTime()) / DAY_MS)
}

/**
 * Deadline tag with a relative hint: "Просрочено на 1 день" / "на 3 ч" (unless finished, see isTaskFinished),
 * "Сегодня до 23:59", "Завтра до 18:00", "Через 2 дня · <date>" within a week, otherwise the date ({@code excused}:
 * always the plain date, see deadlineExcuse); tone and icon are
 * the badge's look (see TONES). The backend sends local date-time without a zone, which Date parses as local time,
 * as intended; the time of day is shown as sent.
 */
export function getDeadlineInfo(deadline, status, now = Date.now(), excused = false) {
  if (!deadline) return { label: 'Без срока', ...TONES.none }
  const date = new Date(deadline)
  const formatted = formatDateTime(deadline)
  const time = String(deadline).slice(11, 16)
  const diff = date.getTime() - now
  // Excused (see deadlineExcuse): the plain date, never "Просрочено".
  if (isTaskFinished(status) || excused) return { label: formatted, ...TONES.later }
  if (diff < 0) {
    const days = Math.floor(-diff / DAY_MS)
    const late = days >= 1 ? plural(days, 'день', 'дня', 'дней') : `${Math.max(1, Math.floor(-diff / HOUR_MS))} ч`
    return { label: `Просрочено на ${late} · ${formatted}`, ...TONES.overdue }
  }
  const days = calendarDaysBetween(now, date)
  if (days === 0) return { label: `Сегодня до ${time}`, ...TONES.soon }
  if (days === 1) return { label: `Завтра до ${time}`, ...TONES.soon }
  if (days <= 7) {
    return { label: `Через ${plural(days, 'день', 'дня', 'дней')} · ${formatted}`, ...(days <= 3 ? TONES.soon : TONES.later) }
  }
  return { label: formatted, ...TONES.later }
}
