import { useCallback, useEffect, useId, useState } from 'react'
import { AlertTriangle, Award, ListTodo, RotateCcw, SearchX, Send } from 'lucide-react'
import api, { describeError } from '../api/axios'
import { useToast } from '../context/ToastContext'
import ErrorState from './ErrorState'
import TaskItem from './TaskItem'
import TaskSubmissions from './TaskSubmissions'
import Button from './ui/Button'
import EmptyState from './ui/EmptyState'
import Skeleton from './ui/Skeleton'
import { controlClass } from './ui/styles'

const PAGE_SIZE = 10
// Same names as the backend TaskStateFilter.
const FILTERS = [
  { value: 'ALL', label: 'Все' },
  { value: 'TO_DO', label: 'К сдаче' },
  { value: 'OVERDUE', label: 'Просрочено' },
  { value: 'NOT_STARTED', label: 'Не начато' },
  { value: 'IN_PROGRESS', label: 'В работе' },
  { value: 'SUBMITTED', label: 'Сдано' },
  { value: 'LATE', label: 'С опозданием' },
  { value: 'GRADED', label: 'Оценено' },
]

// The numbers above the list; each one applies its filter.
const SUMMARY = [
  { key: 'toDo', filter: 'TO_DO', label: 'К сдаче', icon: Send, tone: 'text-primary-fg' },
  { key: 'overdue', filter: 'OVERDUE', label: 'Просрочено', icon: AlertTriangle, tone: 'text-danger-fg' },
  { key: 'graded', filter: 'GRADED', label: 'Оценено', icon: Award, tone: 'text-success-fg' },
]

/**
 * "Мои задачи": GET /tasks/my, nearest deadline first (no deadline last), filtered by state and course, paged, with
 * status changes and submissions. Above it "К сдаче: N · Просрочено: M · Оценено: K" (GET /tasks/my/summary); a
 * number applies its filter. {@code onStatusChanged(task)} tells the page, so "Ближайшие дедлайны" can follow at once.
 */
export default function MyTasks({ onStatusChanged }) {
  const { notifyError } = useToast()
  const courseSelectId = useId()
  const [filter, setFilter] = useState('ALL')
  const [courseId, setCourseId] = useState('')
  const [courses, setCourses] = useState([])
  const [page, setPage] = useState(null) // Last loaded page; its content holds all tasks loaded so far.
  const [summary, setSummary] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [attempt, setAttempt] = useState(0) // Bumped by "Повторить" and after a change under a filter.
  const [summaryVersion, setSummaryVersion] = useState(0) // Bumped after every change.
  const [updatingId, setUpdatingId] = useState(null)
  const filtered = filter !== 'ALL' || courseId !== ''

  const fetchPage = useCallback((number) => api.get('/tasks/my', {
    params: {
      page: number,
      size: PAGE_SIZE,
      ...(filter !== 'ALL' ? { state: filter } : {}),
      ...(courseId ? { courseId } : {}),
    },
  }).then(({ data }) => data), [filter, courseId])

  // Loading is switched on by the handlers (and initially), not here: no synchronous setState in the effect.
  useEffect(() => {
    let cancelled = false
    fetchPage(0)
      .then((data) => {
        if (cancelled) return
        setPage(data)
        setError(null)
      })
      .catch((err) => !cancelled && setError(describeError(err)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [fetchPage, attempt])

  // The numbers follow every change; a failure only hides them.
  useEffect(() => {
    let cancelled = false
    api.get('/tasks/my/summary')
      .then(({ data }) => !cancelled && setSummary(data))
      .catch(() => !cancelled && setSummary(null))
    return () => {
      cancelled = true
    }
  }, [attempt, summaryVersion])

  // The student's courses for the course filter; without them the filter is simply not shown.
  useEffect(() => {
    let cancelled = false
    api.get('/courses', { params: { size: 100, sort: 'title' } })
      .then(({ data }) => !cancelled && setCourses(data.content))
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [])

  const selectFilter = (value) => {
    if (value === filter) return
    setLoading(true)
    setFilter(value)
  }

  const selectCourse = (value) => {
    setLoading(true)
    setCourseId(value)
  }

  const resetFilters = () => {
    setLoading(true)
    setFilter('ALL')
    setCourseId('')
  }

  const retry = () => {
    setLoading(true)
    setError(null)
    setAttempt((value) => value + 1)
  }

  const loadMore = async () => {
    setLoading(true)
    try {
      const next = await fetchPage(page.page + 1)
      setPage({ ...next, content: [...page.content, ...next.content] })
    } catch (err) {
      notifyError(err)
    } finally {
      setLoading(false)
    }
  }

  // Puts the task's new state in the list (keeping the pages loaded so far). Under a filter the task may no longer
  // match, so the list loads again from the first page; the summary loads again either way.
  const replaceTask = (data) => {
    onStatusChanged?.(data)
    if (filter !== 'ALL') {
      setLoading(true)
      setAttempt((value) => value + 1)
    } else {
      setPage((current) => ({
        ...current,
        content: current.content.map((item) => (item.id === data.id ? data : item)),
      }))
    }
    setSummaryVersion((value) => value + 1)
  }

  const changeStatus = async (task, status) => {
    setUpdatingId(task.id)
    try {
      const { data } = await api.put(`/tasks/${task.id}/status`, { status })
      replaceTask(data)
    } catch (err) {
      notifyError(err)
    } finally {
      setUpdatingId(null)
    }
  }

  return (
    <section className="rounded-card border border-line bg-surface p-5 shadow-card sm:p-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
          <ListTodo className="h-5 w-5 text-primary" aria-hidden="true" />
          Мои задачи
          {page && <span className="rounded-full bg-page-alt px-2 py-0.5 text-xs font-semibold tabular-nums text-fg-secondary">{page.totalElements}</span>}
        </h2>
        {summary && (
          <p className="flex flex-wrap items-center gap-x-1 gap-y-1 text-sm text-fg-secondary">
            {SUMMARY.map((item, index) => (
              <span key={item.key} className="inline-flex items-center gap-1">
                {index > 0 && <span aria-hidden="true" className="text-fg-muted">·</span>}
                <button
                  type="button"
                  onClick={() => selectFilter(item.filter)}
                  aria-pressed={filter === item.filter}
                  aria-label={`${item.label}: ${summary[item.key]} — показать`}
                  className={`inline-flex items-center gap-1 rounded-md px-1.5 py-0.5 font-medium transition hover:bg-page-alt focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${
                    filter === item.filter ? 'bg-page-alt' : ''
                  }`}
                >
                  <item.icon className={`h-3.5 w-3.5 ${item.tone}`} aria-hidden="true" />
                  {item.label}: <span className={`tabular-nums ${item.key === 'overdue' && summary.overdue > 0 ? 'font-semibold text-danger-fg' : 'text-fg'}`}>{summary[item.key]}</span>
                </button>
              </span>
            ))}
          </p>
        )}
      </div>

      <div className="mt-4 flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
        <div className="flex flex-wrap gap-1 rounded-control bg-page-alt p-1" role="group" aria-label="Фильтр по состоянию">
          {FILTERS.map((item) => (
            <button
              key={item.value}
              type="button"
              aria-pressed={filter === item.value}
              onClick={() => selectFilter(item.value)}
              className={`rounded-md px-2.5 py-1 text-xs font-medium transition focus:outline-none focus-visible:ring-2 focus-visible:ring-primary ${
                filter === item.value
                  ? 'bg-surface text-primary-fg shadow-soft ring-1 ring-line'
                  : 'text-fg-secondary hover:text-fg'
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>
        {courses.length > 1 && (
          <div className="sm:w-56 sm:shrink-0">
            <label htmlFor={courseSelectId} className="sr-only">Курс</label>
            <select
              id={courseSelectId}
              value={courseId}
              onChange={(event) => selectCourse(event.target.value)}
              className={`${controlClass} py-1.5`}
            >
              <option value="">Все курсы</option>
              {courses.map((course) => (
                <option key={course.id} value={course.id}>{course.code ? `${course.code} · ${course.title}` : course.title}</option>
              ))}
            </select>
          </div>
        )}
      </div>

      {error && !page ? (
        <ErrorState className="mt-4" title="Не удалось загрузить задачи" message={error.message} onRetry={retry} />
      ) : !page ? (
        <div role="status" className="mt-4 space-y-2">
          <span className="sr-only">Загрузка задач…</span>
          {[0, 1, 2].map((index) => <Skeleton key={index} className="h-20 w-full" rounded="rounded-card" />)}
        </div>
      ) : (
        <>
          {error && <ErrorState compact className="mt-4" message={error.message} onRetry={retry} />}
          {page.content.length === 0 ? (
            filtered ? (
              <EmptyState
                compact
                icon={SearchX}
                title="По фильтру ничего не найдено"
                action={<Button variant="outline" size="sm" icon={RotateCcw} onClick={resetFilters}>Сбросить фильтры</Button>}
                className="mt-4"
              />
            ) : (
              <EmptyState
                compact
                icon={ListTodo}
                title="Задач нет"
                description="Они появятся, когда преподаватель выдаст задания или ИИ найдёт их в силлабусах ваших курсов."
                className="mt-4"
              />
            )
          ) : (
            <>
              <ul className={`mt-4 space-y-2 transition-opacity ${loading ? 'opacity-60' : ''}`}>
                {page.content.map((task) => (
                  <TaskItem
                    key={task.id}
                    task={task}
                    showDocument
                    onStatusChange={changeStatus}
                    updating={updatingId === task.id}
                    extra={task.assignmentId && (
                      <TaskSubmissions task={task} onSubmitted={(changes) => replaceTask({ ...task, ...changes })} />
                    )}
                  />
                ))}
              </ul>
              {!page.last && (
                <Button variant="outline" onClick={loadMore} loading={loading} className="mt-3 w-full">
                  Показать ещё
                </Button>
              )}
            </>
          )}
        </>
      )}
    </section>
  )
}
