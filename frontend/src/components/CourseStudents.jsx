import { useCallback, useEffect, useState } from 'react'
import { Loader2, UserMinus, Users } from 'lucide-react'
import api, { describeError } from '../api/axios'
import { useToast } from '../context/ToastContext'
import useConfirm from '../hooks/useConfirm'
import ErrorState from './ErrorState'

const PAGE_SIZE = 20

/** The course's students, for its teacher and ADMIN; removing a student also removes their tasks of the course. */
export default function CourseStudents({ courseId, onChange }) {
  const { notifyError, showSuccess } = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [page, setPage] = useState(null)
  const [busyId, setBusyId] = useState(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)

  const [loadingMore, setLoadingMore] = useState(false)

  const fetchPage = useCallback((number) => api.get(`/courses/${courseId}/students`, { params: { page: number, size: PAGE_SIZE } })
    .then(({ data }) => data), [courseId])

  // The first page; after an unenrollment the list starts over from it.
  const load = useCallback(() => fetchPage(0).then((data) => {
    setPage(data)
    setError('')
  }), [fetchPage])

  const loadMore = async () => {
    setLoadingMore(true)
    try {
      const next = await fetchPage(page.page + 1)
      setPage((current) => ({ ...next, content: [...current.content, ...next.content] }))
    } catch (err) {
      notifyError(err)
    } finally {
      setLoadingMore(false)
    }
  }

  useEffect(() => {
    load().catch((err) => setError(describeError(err).message))
  }, [load, attempt])

  const retry = () => {
    setError('')
    setPage(null)
    setAttempt((value) => value + 1)
  }

  const remove = async (student) => {
    const confirmed = await confirm({
      title: 'Отчислить студента?',
      message: `${student.fullName || student.email} будет отчислен с курса, его задачи по курсу будут удалены.`,
      confirmLabel: 'Отчислить',
    })
    if (!confirmed) return
    setBusyId(student.id)
    try {
      await api.delete(`/courses/${courseId}/students/${student.id}`)
      await load()
      onChange?.()
      showSuccess('Студент отчислен с курса')
    } catch (err) {
      notifyError(err)
    } finally {
      setBusyId(null)
    }
  }

  return (
    <section className="rounded-card border border-line bg-surface shadow-card">
      <h2 className="flex items-center gap-2 border-b border-line-subtle px-5 py-4 text-base font-semibold text-fg sm:px-6">
        <Users className="h-4 w-4 text-primary-fg" /> Студенты курса
        {page && <span className="rounded-full bg-page-alt px-2 py-0.5 text-xs font-semibold text-fg-secondary">{page.totalElements}</span>}
      </h2>
      {error ? (
        <ErrorState title="Не удалось загрузить список студентов" message={error} onRetry={retry} />
      ) : !page ? (
        <div className="flex items-center justify-center gap-2 py-6 text-sm text-fg-muted"><Loader2 className="h-4 w-4 animate-spin" /> Загрузка…</div>
      ) : page.content.length === 0 ? (
        <p className="px-5 py-6 text-center text-sm text-fg-muted">Пока никто не записан. Отправьте студентам ссылку на курс.</p>
      ) : (
        <ul className="divide-y divide-line-subtle">
          {page.content.map((student) => (
            <li key={student.id} className="flex items-center gap-3 px-5 py-2.5">
              <span className="min-w-0 flex-1">
                <span className="block truncate text-sm font-medium text-fg">{student.fullName || '—'}</span>
                <span className="block truncate text-xs text-fg-muted">{student.email}</span>
              </span>
              <button
                type="button"
                onClick={() => remove(student)}
                disabled={busyId === student.id}
                className="inline-flex items-center gap-1 rounded-control border border-line px-2 py-1 text-xs font-medium text-fg-secondary transition hover:border-danger/30 hover:text-danger-fg disabled:opacity-60"
                title="Отчислить с курса"
                aria-label={`Отчислить с курса: ${student.fullName || student.email}`}
              >
                {busyId === student.id ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <UserMinus className="h-3.5 w-3.5" />}
              </button>
            </li>
          ))}
        </ul>
      )}
      {page && !error && !page.last && (
        <button
          type="button"
          onClick={loadMore}
          disabled={loadingMore}
          className="flex w-full items-center justify-center gap-2 border-t border-line-subtle py-2.5 text-sm font-medium text-primary-fg hover:bg-page-alt disabled:opacity-60"
        >
          {loadingMore && <Loader2 className="h-4 w-4 animate-spin" />} Показать ещё
        </button>
      )}
      {confirmDialog}
    </section>
  )
}
