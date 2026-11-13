import { Link, useLocation } from 'react-router-dom'
import { Award, BookOpen, ChevronDown, FileText, Loader2, Lock, MessageSquareText, Sparkles, Trash2 } from 'lucide-react'
import { ASSIGNMENT_TASK_STATUSES, LEGACY_TASK_STATUSES, TASK_STATUS_LABELS } from '../constants/documents'
import { deadlineExcuse, getDeadlineInfo, getTaskType, isOverdue } from '../utils/tasks'
import TaskStateBadge, { taskState } from './TaskStateBadge'
import Badge from './ui/Badge'
import Button from './ui/Button'
import { badgeClass } from './ui/styles'

/**
 * One task - or course assignment - with type and deadline tags. With {@code onStatusChange} the status is a select
 * (own tasks: "Не начато" / "В работе" on an assignment task, also "Выполнено" on a legacy one) until the system sets
 * "Сдано" or "Оценено"; otherwise, and then, a read-only badge when the status is a task status; {@code showDocument} links the source
 * document; {@code onDelete} adds a delete button (assignments, for the course teacher); {@code actions} adds more
 * buttons next to it; {@code extra} is rendered under the tags (submissions). An assignment the AI found in a
 * document has the tag "Найдено ИИ". A closed assignment - or a student's
 * task of one - is marked "Закрыто" (with nothing handed in: the chip "Задание закрыто", never overdue; likewise
 * "Срок прошёл до записи" for a deadline that passed before the student enrolled). A student's overdue task (nothing
 * handed in, deadline passed, assignment open) is highlighted
 * with a red edge and the "Просрочено" tag (icon + text, never the color alone); graded work shows the score
 * "8 / 10" and the teacher's feedback, expandable.
 */
/** Tooltip of "Срок прошёл до записи": whether work is still accepted. */
const beforeEnrollmentHint = (task) => (task.allowLate
  ? 'Срок прошёл до вашей записи на курс. Сдать работу ещё можно — она будет отмечена «с опозданием».'
  : 'Срок прошёл до вашей записи на курс; задание не принимает работы после срока.')

export default function TaskItem({ task, onStatusChange, onDelete, actions = null, updating = false, showDocument = false, extra = null }) {
  const type = getTaskType(task)
  // A student's task (a task status) with nothing handed in that is not overdue: closed, or deadline before enrollment.
  const excuse = taskState(task.status) ? deadlineExcuse(task) : null
  const deadline = getDeadlineInfo(task.deadline, task.status, undefined, Boolean(excuse))
  const completed = task.status === 'COMPLETED'
  // The excuse chip already says "Задание закрыто"; no second "Закрыто" tag then.
  const closed = (task.status === 'CLOSED' || task.assignmentStatus === 'CLOSED') && excuse !== 'CLOSED'
  // A course assignment (not a student's task) the AI found in a document.
  const aiFound = Boolean(task.sourceDocumentId)
  const documentId = task.documentId ?? task.sourceDocumentId
  const documentName = task.documentName ?? task.sourceDocumentName
  const location = useLocation()
  const status = taskState(task.status, task.late)
  const StatusIcon = status?.icon
  const choices = task.assignmentId ? ASSIGNMENT_TASK_STATUSES : LEGACY_TASK_STATUSES
  // An excused task shows its chip instead of the status select.
  const editable = onStatusChange && choices.includes(task.status) && !excuse
  // Only a student's task (a task status) is overdue; an assignment in the teacher's list keeps the plain tag.
  const overdue = Boolean(status) && isOverdue(task)
  const graded = task.status === 'GRADED' && task.score != null

  return (
    // Laid out by the card's own width (container query): side by side only when the card is wide enough.
    <li className={`@container rounded-card border bg-surface p-3.5 sm:p-4 ${overdue ? 'border-danger/50 border-l-4 border-l-danger' : 'border-line'}`}>
      <div className="flex flex-col gap-3 @lg:flex-row @lg:items-start">
      <div className="min-w-0 flex-1">
        <p className={`text-sm font-semibold ${completed ? 'text-fg-muted line-through' : 'text-fg'}`}>
          {task.title}
        </p>
        {task.description && (
          <p className="mt-0.5 line-clamp-2 text-small text-fg-muted">{task.description}</p>
        )}
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <Badge tone={type.tone} icon={type.icon}>{type.label}</Badge>
          {aiFound && (
            <Badge tone="accent" icon={Sparkles} title="Задание найдено ИИ в документе курса; преподаватель может изменить или закрыть его">
              Найдено ИИ
            </Badge>
          )}
          {closed && (
            <Badge tone="neutral" icon={Lock} title="Задание закрыто: новые решения не принимаются">Закрыто</Badge>
          )}
          <Badge tone={deadline.tone} icon={deadline.icon}>{deadline.label}</Badge>
          {(task.courseCode || task.courseTitle) && (
            <Badge tone="neutral" icon={BookOpen} title={task.courseTitle}>{task.courseCode || task.courseTitle}</Badge>
          )}
          {graded && (
            <Badge tone="success" icon={Award} size="md" className="tabular-nums" title="Оценка преподавателя">
              {Number(task.score)}{task.maxScore != null && ` / ${task.maxScore}`}
            </Badge>
          )}
          {showDocument && documentId && (
            <Link
              to={`/documents/${documentId}`}
              state={{ from: location.pathname }}
              className="inline-flex min-w-0 items-center gap-1 rounded-sm text-[11px] font-medium text-primary-fg hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              title={documentName}
            >
              <FileText className="h-3 w-3 shrink-0" aria-hidden="true" />
              <span className="max-w-48 truncate">{documentName}</span>
            </Link>
          )}
        </div>
        {graded && task.feedback && (
          <details className="group mt-2 rounded-control bg-page-alt px-3 py-2">
            <summary className="flex cursor-pointer list-none items-center gap-1.5 rounded-sm text-small font-medium text-fg-secondary hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary [&::-webkit-details-marker]:hidden">
              <MessageSquareText className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              Отзыв преподавателя
              <ChevronDown className="h-3.5 w-3.5 shrink-0 transition group-open:rotate-180" aria-hidden="true" />
            </summary>
            <p className="mt-1.5 whitespace-pre-line break-words text-sm text-fg">{task.feedback}</p>
          </details>
        )}
        {extra}
      </div>
      <div className="flex flex-wrap items-center gap-2 empty:hidden @lg:shrink-0 @lg:justify-end">
      {editable ? (
        <label className="flex shrink-0 items-center gap-2">
          <span className="sr-only">Статус задачи</span>
          {updating
            ? <Loader2 className="h-3.5 w-3.5 animate-spin text-fg-muted" aria-hidden="true" />
            : StatusIcon && <StatusIcon className="h-4 w-4 text-fg-muted" aria-hidden="true" />}
          <select
            value={task.status}
            disabled={updating}
            onChange={(event) => onStatusChange(task, event.target.value)}
            className={`${badgeClass(status?.tone, 'md')} cursor-pointer border-0 py-1 pr-7 focus:outline-none focus:ring-2 focus:ring-primary disabled:opacity-60`}
          >
            {choices.map((value) => (
              <option key={value} value={value}>{TASK_STATUS_LABELS[value]}</option>
            ))}
          </select>
        </label>
      ) : (
        <TaskStateBadge
          status={task.status}
          late={task.late}
          excuse={excuse}
          title={excuse === 'BEFORE_ENROLLMENT' ? beforeEnrollmentHint(task) : undefined}
          className="self-start"
        />
      )}
      {actions}
      {onDelete && (
        <Button
          variant="danger-ghost"
          size="sm"
          icon={Trash2}
          loading={updating}
          onClick={() => onDelete(task)}
          className="self-start"
          title="Удалить задание у всех студентов курса"
        >
          Удалить
        </Button>
      )}
      </div>
      </div>
    </li>
  )
}
