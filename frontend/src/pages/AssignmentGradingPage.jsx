import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { AlarmClock, ArrowLeft, Award, CalendarClock, ClipboardCheck, Lock, Users } from 'lucide-react'
import api, { describeError } from '../api/axios'
import Navbar from '../components/Navbar'
import ErrorState from '../components/ErrorState'
import GradingDialog from '../components/GradingDialog'
import TaskStateBadge from '../components/TaskStateBadge'
import Badge from '../components/ui/Badge'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import PageHeader from '../components/ui/PageHeader'
import Skeleton from '../components/ui/Skeleton'
import { formatDateTime } from '../utils/dates'

const PAGE_SIZE = 50
// Same names as the backend GradingFilter.
const FILTERS = [
  { value: 'ALL', label: 'Все' },
  { value: 'NOT_SUBMITTED', label: 'Не сдали' },
  { value: 'SUBMITTED', label: 'Ждут оценки' },
  { value: 'LATE', label: 'С опозданием' },
  { value: 'GRADED', label: 'Оценено' },
]

const scoreText = (score) => (score == null ? null : String(Number(score)))

/**
 * The teacher's grading page of one assignment (GET /assignments/{id}/grading): header with deadline and max score,
 * the progress line "Сдали X из Y · с опозданием Z · оценено W", state filters, and the enrolled students with their
 * state, hand-in time, late mark and grade. "Оценить" opens GradingDialog; "Следующий студент" walks down the list.
 */
export default function AssignmentGradingPage() {
  const { courseId, id } = useParams()
  const [filter, setFilter] = useState('ALL')
  const [size, setSize] = useState(PAGE_SIZE)
  const [data, setData] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [attempt, setAttempt] = useState(0) // Bumped by "Повторить" and after a grade, to load again.
  const [gradingId, setGradingId] = useState(null)

  const fetchGrading = useCallback(() => api.get(`/assignments/${id}/grading`, { params: { state: filter, page: 0, size } })
    .then((response) => response.data), [id, filter, size])

  // Loading is switched on by the handlers that change what is shown, not here: no synchronous setState in the effect.
  useEffect(() => {
    let cancelled = false
    fetchGrading()
      .then((result) => {
        if (cancelled) return
        setData(result)
        setError(null)
      })
      .catch((err) => !cancelled && setError(describeError(err)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [fetchGrading, attempt])

  const selectFilter = (value) => {
    if (value === filter) return
    setLoading(true)
    setSize(PAGE_SIZE)
    setFilter(value)
  }

  const retry = () => {
    setLoading(true)
    setError(null)
    setAttempt((value) => value + 1)
  }

  const students = data?.students.content ?? []
  const index = students.findIndex((student) => student.taskId === gradingId)
  const grading = index >= 0 ? students[index] : null
  const nextStudent = index >= 0 ? students[index + 1] : undefined

  // The saved row goes in at once; the reload brings the counts and the filter up to date.
  const saved = (row) => {
    setData((current) => current && {
      ...current,
      students: { ...current.students, content: current.students.content.map((item) => (item.taskId === row.taskId ? row : item)) },
    })
    setAttempt((value) => value + 1)
  }

  const assignment = data?.assignment
  const summary = data?.summary
  const notAllowed = error?.status === 403 || error?.status === 404

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8">
        <Link to={`/courses/${courseId}#assignments`} className="inline-flex items-center gap-1.5 rounded-sm text-sm font-medium text-fg-muted hover:text-primary-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary">
          <ArrowLeft className="h-4 w-4" aria-hidden="true" /> К курсу
        </Link>

        {error && !data ? (
          <ErrorState
            className="mt-8"
            title={error.status === 404 ? 'Задание не найдено' : error.status === 403 ? 'Нет доступа к оценкам' : 'Не удалось загрузить оценки'}
            message={error.message}
            onRetry={notAllowed ? undefined : retry}
          />
        ) : !data ? (
          <div role="status" aria-label="Загрузка оценок" className="mt-6 space-y-4">
            <Skeleton className="h-9 w-2/3" />
            <Skeleton className="h-4 w-1/2" />
            <div className="space-y-2 pt-4">
              {[0, 1, 2, 3].map((key) => <Skeleton key={key} className="h-16 w-full" rounded="rounded-card" />)}
            </div>
          </div>
        ) : (
          <>
            <PageHeader
              className="mt-4"
              icon={ClipboardCheck}
              title={assignment.title}
              subtitle={[assignment.courseCode, assignment.courseTitle].filter(Boolean).join(' · ')}
            >
              <div className="mt-3 flex flex-wrap items-center gap-1.5">
                <Badge tone="neutral" icon={CalendarClock} size="md">
                  {assignment.deadline ? `Срок: ${formatDateTime(assignment.deadline)}` : 'Без срока'}
                </Badge>
                <Badge tone="primary" icon={Award} size="md">
                  {assignment.maxScore == null ? 'Без максимального балла' : `Макс. балл: ${assignment.maxScore}`}
                </Badge>
                <Badge tone={assignment.allowLate ? 'neutral' : 'warning'} icon={AlarmClock} size="md">
                  {assignment.allowLate ? 'После срока принимается' : 'После срока не принимается'}
                </Badge>
                {assignment.status === 'CLOSED' && <Badge tone="neutral" icon={Lock} size="md">Закрыто</Badge>}
              </div>
            </PageHeader>

            <section className="mt-6 rounded-card border border-line bg-surface p-5 shadow-card sm:p-6" aria-labelledby="grading-title">
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div className="min-w-0">
                  <h2 id="grading-title" className="flex items-center gap-2 text-base font-semibold text-fg">
                    <Users className="h-5 w-5 text-primary" aria-hidden="true" /> Работы студентов
                  </h2>
                  <p className="mt-1 text-sm tabular-nums text-fg-secondary">
                    Сдали {summary.submitted} из {summary.students} · с опозданием {summary.late} · оценено {summary.graded}
                  </p>
                </div>
                <div className="flex flex-wrap gap-1 rounded-control bg-page-alt p-1" role="group" aria-label="Фильтр по состоянию">
                  {FILTERS.map((item) => (
                    <button
                      key={item.value}
                      type="button"
                      onClick={() => selectFilter(item.value)}
                      aria-pressed={filter === item.value}
                      className={`rounded-md px-2.5 py-1 text-xs font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${
                        filter === item.value ? 'bg-surface text-primary-fg shadow-soft' : 'text-fg-secondary hover:text-fg'
                      }`}
                    >
                      {item.label}
                    </button>
                  ))}
                </div>
              </div>
              {summary.students > 0 && (
                <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-page-alt" aria-hidden="true">
                  <div className="h-full rounded-full bg-primary transition-[width]" style={{ width: `${Math.round((summary.submitted / summary.students) * 100)}%` }} />
                </div>
              )}

              {error && <ErrorState compact className="mt-4" message={error.message} onRetry={retry} />}

              {students.length === 0 ? (
                <EmptyState
                  compact
                  className="mt-4"
                  icon={Users}
                  title={filter === 'ALL' ? 'На курсе пока нет студентов с этим заданием.' : 'Нет студентов с таким состоянием.'}
                />
              ) : (
                <ul className={`mt-4 divide-y divide-line-subtle rounded-control border border-line transition-opacity ${loading ? 'opacity-60' : ''}`}>
                  {students.map((student) => (
                    <li key={student.taskId} className="flex flex-col gap-2 px-3 py-3 sm:flex-row sm:items-center sm:gap-4 sm:px-4">
                      <div className="min-w-0 flex-1">
                        <p className="truncate text-sm font-semibold text-fg">{student.studentFullName || student.studentEmail}</p>
                        {student.studentFullName && <p className="truncate text-small text-fg-muted">{student.studentEmail}</p>}
                      </div>
                      <div className="flex flex-wrap items-center gap-x-3 gap-y-1.5 sm:justify-end">
                        <TaskStateBadge status={student.status} late={student.late} />
                        {student.status === 'GRADED' && student.late && (
                          <Badge tone="warning" icon={AlarmClock}>С опозданием</Badge>
                        )}
                        <span className="text-small tabular-nums text-fg-muted">
                          {student.submittedAt ? formatDateTime(student.submittedAt) : 'Не сдано'}
                        </span>
                        {student.status === 'GRADED' && (
                          <span className="text-sm font-semibold tabular-nums text-fg">
                            {scoreText(student.score)}{assignment.maxScore != null && <span className="font-normal text-fg-muted"> / {assignment.maxScore}</span>}
                          </span>
                        )}
                        <Button
                          size="sm"
                          variant={student.status === 'SUBMITTED' ? 'primary' : 'outline'}
                          onClick={() => setGradingId(student.taskId)}
                          aria-label={`${student.status === 'GRADED' ? 'Изменить оценку' : 'Оценить'}: ${student.studentFullName || student.studentEmail}`}
                        >
                          {student.status === 'GRADED' ? 'Изменить' : 'Оценить'}
                        </Button>
                      </div>
                    </li>
                  ))}
                </ul>
              )}
              {!data.students.last && (
                <Button
                  variant="outline"
                  className="mt-3 w-full"
                  loading={loading}
                  onClick={() => {
                    setLoading(true)
                    setSize((value) => value + PAGE_SIZE)
                  }}
                >
                  Показать ещё
                </Button>
              )}
            </section>
          </>
        )}
      </main>
      {grading && (
        <GradingDialog
          key={grading.taskId}
          student={grading}
          maxScore={assignment.maxScore}
          onClose={() => setGradingId(null)}
          onSaved={saved}
          onNext={nextStudent ? () => setGradingId(nextStudent.taskId) : undefined}
        />
      )}
    </div>
  )
}
