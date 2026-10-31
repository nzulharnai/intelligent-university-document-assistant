import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { Award, ClipboardList, Loader2, Lock, LockOpen, Pencil, Plus } from 'lucide-react'
import api, { describeError } from '../api/axios'
import { useToast } from '../context/ToastContext'
import useConfirm from '../hooks/useConfirm'
import TaskItem from './TaskItem'
import TaskSubmissions from './TaskSubmissions'
import ErrorState from './ErrorState'
import AssignmentEditDialog from './AssignmentEditDialog'

const PAGE_SIZE = 20
const inputClass = 'w-full rounded-control border border-line-strong bg-surface px-3 py-2 text-sm text-fg outline-none transition placeholder:text-fg-muted focus:border-primary focus:ring-4 focus:ring-ring'

/**
 * The course's assignments (found by the AI in its documents or added by hand). Every enrolled student gets each one
 * as a task. With {@code canManage} (course teacher, ADMIN) assignments can be added, edited, closed, reopened and
 * deleted.
 */
export default function CourseAssignments({ courseId, canManage, version = 0 }) {
  const { notifyError, showSuccess } = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [page, setPage] = useState(null)
  const [busyId, setBusyId] = useState(null)
  const [form, setForm] = useState({ title: '', description: '', deadline: '' })
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0) // Bumped by "Повторить" to run the load again.
  const [editing, setEditing] = useState(null) // the assignment in the edit dialog

  const [loadingMore, setLoadingMore] = useState(false)

  const fetchPage = useCallback((number) => api.get(`/courses/${courseId}/assignments`, { params: { page: number, size: PAGE_SIZE } })
    .then(({ data }) => data), [courseId])

  // The first page; after a create or delete the list starts over from it.
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
  }, [load, version, attempt])

  const retry = () => {
    setError('')
    setPage(null)
    setAttempt((value) => value + 1)
  }

  // Coming from an upload's "К заданиям курса" link (/courses/:id#assignments): scroll here once the list is in.
  const loaded = page !== null
  useEffect(() => {
    if (loaded && window.location.hash === '#assignments') {
      document.getElementById('assignments')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }
  }, [loaded])

  const create = async (event) => {
    event.preventDefault()
    setSaving(true)
    try {
      await api.post(`/courses/${courseId}/assignments`, {
        title: form.title.trim(),
        description: form.description.trim() || null,
        // datetime-local gives "2026-10-15T23:59"; the backend expects seconds as well.
        deadline: form.deadline ? `${form.deadline}:00` : null,
      })
      setForm({ title: '', description: '', deadline: '' })
      await load()
      showSuccess('Задание создано и выдано всем студентам курса')
    } catch (err) {
      notifyError(err)
    } finally {
      setSaving(false)
    }
  }

  const remove = async (assignment) => {
    const confirmed = await confirm({
      title: 'Удалить задание?',
      message: `«${assignment.title}» исчезнет из задач всех студентов курса.`,
    })
    if (!confirmed) return
    setBusyId(assignment.id)
    try {
      await api.delete(`/assignments/${assignment.id}`)
      await load()
      showSuccess('Задание удалено')
    } catch (err) {
      notifyError(err)
    } finally {
      setBusyId(null)
    }
  }

  const update = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }))

  // A closed assignment stays with its students' work but takes no more submissions; reopening hands it out again.
  const setClosed = async (assignment, close) => {
    const confirmed = await confirm(close ? {
      title: 'Закрыть задание?',
      message: `«${assignment.title}» останется в курсе вместе с решениями студентов, но новые решения приниматься не будут.`,
      confirmLabel: 'Закрыть',
    } : {
      title: 'Открыть задание снова?',
      message: `Студенты снова смогут сдавать решения к «${assignment.title}»; те, у кого задачи ещё нет, получат её.`,
      confirmLabel: 'Открыть снова',
      tone: 'primary',
    })
    if (!confirmed) return
    setBusyId(assignment.id)
    try {
      await api.post(`/assignments/${assignment.id}/${close ? 'close' : 'reopen'}`)
      await load()
      showSuccess(close ? 'Задание закрыто' : 'Задание снова открыто')
    } catch (err) {
      notifyError(err)
    } finally {
      setBusyId(null)
    }
  }

  const actionClass = 'inline-flex shrink-0 items-center gap-1 self-start rounded-control border px-2 py-1 text-xs font-medium transition disabled:opacity-60'
  const manageActions = (assignment) => (
    <>
      <Link
        to={`/courses/${courseId}/assignments/${assignment.id}`}
        aria-label={`Оценки по заданию «${assignment.title}»`}
        title="Работы студентов и оценки"
        className={`${actionClass} border-primary/40 text-primary-fg hover:bg-primary-soft focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary`}
      >
        <Award className="h-3.5 w-3.5" aria-hidden="true" /> Оценки
      </Link>
      <button
        type="button"
        onClick={() => setEditing(assignment)}
        disabled={busyId === assignment.id}
        aria-label={`Изменить «${assignment.title}»`}
        title="Изменить название, требования или дедлайн"
        className={`${actionClass} border-line-strong text-fg hover:bg-page-alt`}
      >
        <Pencil className="h-3.5 w-3.5" /> <span className="hidden sm:inline">Изменить</span>
      </button>
      {assignment.status === 'CLOSED' ? (
        <button
          type="button"
          onClick={() => setClosed(assignment, false)}
          disabled={busyId === assignment.id}
          className={`${actionClass} border-success/30 text-success-fg hover:bg-success-soft`}
        >
          <LockOpen className="h-3.5 w-3.5" /> Открыть снова
        </button>
      ) : (
        <button
          type="button"
          onClick={() => setClosed(assignment, true)}
          disabled={busyId === assignment.id}
          className={`${actionClass} border-warning/30 text-warning-fg hover:bg-warning-soft`}
        >
          <Lock className="h-3.5 w-3.5" /> Закрыть
        </button>
      )}
    </>
  )

  return (
    <section id="assignments" className="scroll-mt-20 rounded-card border border-line bg-surface shadow-card">
      <h2 className="flex items-center gap-2 border-b border-line-subtle px-5 py-4 text-base font-semibold text-fg sm:px-6">
        <ClipboardList className="h-4 w-4 text-primary-fg" /> Задания курса
        {page && <span className="rounded-full bg-page-alt px-2 py-0.5 text-xs font-semibold text-fg-secondary">{page.totalElements}</span>}
      </h2>
      <div className="p-4">
        {error ? (
          <ErrorState title="Не удалось загрузить задания" message={error} onRetry={retry} />
        ) : !page ? (
          <div className="flex items-center justify-center gap-2 py-6 text-sm text-fg-muted"><Loader2 className="h-4 w-4 animate-spin" /> Загрузка…</div>
        ) : page.content.length === 0 ? (
          <p className="py-4 text-center text-sm text-fg-muted">
            Заданий пока нет. ИИ находит их в загруженных силлабусах и заданиях{canManage ? ', или добавьте задание вручную' : ''}.
          </p>
        ) : (
          <ul className="space-y-2">
            {page.content.map((assignment) => (
              <TaskItem
                key={assignment.id}
                task={assignment}
                showDocument
                onDelete={canManage ? remove : undefined}
                actions={canManage && manageActions(assignment)}
                updating={busyId === assignment.id}
                extra={canManage && <TaskSubmissions key={assignment.id} assignmentId={assignment.id} />}
              />
            ))}
          </ul>
        )}
        {page && !error && !page.last && (
          <button
            type="button"
            onClick={loadMore}
            disabled={loadingMore}
            className="mt-3 flex w-full items-center justify-center gap-2 rounded-control border border-line-strong py-2 text-sm font-medium text-fg transition hover:bg-page-alt disabled:opacity-60"
          >
            {loadingMore && <Loader2 className="h-4 w-4 animate-spin" />} Показать ещё
          </button>
        )}

        {canManage && (
          <form onSubmit={create} className="mt-4 space-y-2 rounded-xl border border-dashed border-line-strong p-3">
            <p className="flex items-center gap-1.5 text-xs font-semibold text-fg-secondary"><Plus className="h-3.5 w-3.5" /> Новое задание</p>
            <input value={form.title} onChange={update('title')} required maxLength={255} placeholder="Название, например «Practice 04»" className={inputClass} aria-label="Название задания" />
            <textarea value={form.description} onChange={update('description')} rows={2} maxLength={2000} placeholder="Требования (необязательно)" className={`${inputClass} resize-none`} aria-label="Требования" />
            <div className="flex gap-2">
              <input type="datetime-local" value={form.deadline} onChange={update('deadline')} className={inputClass} aria-label="Дедлайн" />
              <button type="submit" disabled={saving || !form.title.trim()} className="flex shrink-0 items-center gap-2 rounded-control bg-primary px-3 text-sm font-semibold text-on-primary transition hover:bg-primary-hover disabled:opacity-60">
                {saving && <Loader2 className="h-4 w-4 animate-spin" />} Выдать
              </button>
            </div>
          </form>
        )}
      </div>
      {confirmDialog}
      {editing && <AssignmentEditDialog assignment={editing} onClose={() => setEditing(null)} onSaved={() => load().catch(notifyError)} />}
    </section>
  )
}
