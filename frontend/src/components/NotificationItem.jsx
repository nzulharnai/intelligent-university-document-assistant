import { AlarmClock, Award, BadgeCheck, Bell, CalendarClock, ClipboardList, XCircle } from 'lucide-react'
import { formatDateTime } from '../utils/dates'

// Same names as the backend NotificationType; each has its own icon, so the type never depends on color.
const TYPE_ICONS = {
  ASSIGNMENT_PUBLISHED: ClipboardList,
  DEADLINE_CHANGED: CalendarClock,
  DEADLINE_REMINDER_24H: AlarmClock,
  TASK_GRADED: Award,
  TEACHER_REQUEST_APPROVED: BadgeCheck,
  TEACHER_REQUEST_REJECTED: XCircle,
}

/**
 * One notification as a button: icon, title, text, time; unread ones are bold with a dot and "новое" for screen
 * readers. {@code onOpen(notification)} marks it read and follows its link.
 */
export default function NotificationItem({ notification, onOpen, compact = false }) {
  const Icon = TYPE_ICONS[notification.type] ?? Bell
  const unread = !notification.readAt
  return (
    <button
      type="button"
      onClick={() => onOpen(notification)}
      className={`flex w-full items-start gap-3 rounded-control px-3 py-2.5 text-left transition-colors hover:bg-page-alt focus:outline-none focus-visible:ring-4 focus-visible:ring-ring ${unread ? 'bg-primary-soft/40' : ''}`}
    >
      <span className={`mt-0.5 flex h-8 w-8 shrink-0 items-center justify-center rounded-full ${unread ? 'bg-primary-soft text-primary' : 'bg-page-alt text-fg-muted'}`}>
        <Icon className="h-4 w-4" aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1">
        <span className={`block text-sm ${unread ? 'font-semibold text-fg' : 'font-medium text-fg-secondary'}`}>
          {unread && <span className="sr-only">Новое: </span>}
          {notification.title}
        </span>
        {notification.body && (
          <span className={`mt-0.5 block text-small text-fg-muted ${compact ? 'line-clamp-2' : 'whitespace-pre-line break-words'}`}>
            {notification.body}
          </span>
        )}
        <span className="mt-1 block text-[11px] tabular-nums text-fg-muted">{formatDateTime(notification.createdAt)}</span>
      </span>
      {unread && <span className="mt-2 h-2 w-2 shrink-0 rounded-full bg-primary" aria-hidden="true" />}
    </button>
  )
}
