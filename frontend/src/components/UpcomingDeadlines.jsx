import { useEffect, useState } from 'react'
import { CalendarCheck, CalendarClock } from 'lucide-react'
import api, { describeError } from '../api/axios'
import ErrorState from './ErrorState'
import TaskItem from './TaskItem'
import EmptyState from './ui/EmptyState'
import Skeleton from './ui/Skeleton'
import { isTaskFinished } from '../utils/tasks'

/**
 * "Upcoming Deadlines": the student's unfinished tasks due from now on, nearest first (GET /tasks/upcoming).
 * {@code statusOverrides} ({ taskId: status }) are changes just made in "Мои задачи": a task marked COMPLETED leaves
 * this list at once, and {@code refreshKey} then reloads it so the next deadline moves up.
 */
export default function UpcomingDeadlines({ limit = 5, refreshKey = 0, statusOverrides = {} }) {
  const [tasks, setTasks] = useState(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    api.get('/tasks/upcoming', { params: { limit } })
      .then(({ data }) => {
        if (cancelled) return
        setTasks(data)
        setError('')
      })
      .catch((err) => !cancelled && setError(describeError(err).message))
    return () => {
      cancelled = true
    }
  }, [limit, refreshKey, attempt])

  const visible = (tasks ?? [])
    .map((task) => (statusOverrides[task.id] ? { ...task, status: statusOverrides[task.id] } : task))
    .filter((task) => !isTaskFinished(task.status))

  return (
    <section className="rounded-card border border-line bg-surface p-5 shadow-card sm:p-6">
      <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
        <CalendarClock className="h-5 w-5 text-warning" aria-hidden="true" />
        Ближайшие дедлайны
      </h2>
      {error ? (
        <ErrorState
          title="Не удалось загрузить дедлайны"
          message={error}
          onRetry={() => {
            setError('')
            setTasks(null)
            setAttempt((value) => value + 1)
          }}
        />
      ) : tasks === null ? (
<div role="status" className="mt-4 space-y-2">
          <span className="sr-only">Загрузка…</span>
          {[0, 1, 2].map((index) => <Skeleton key={index} className="h-20 w-full" rounded="rounded-card" />)}
        </div>
      ) : visible.length === 0 ? (
        <EmptyState compact icon={CalendarCheck} title="Предстоящих дедлайнов нет." className="mt-4" />
      ) : (
        <ul className="mt-4 space-y-2">
          {visible.map((task) => <TaskItem key={task.id} task={task} showDocument />)}
        </ul>
      )}
    </section>
  )
}
