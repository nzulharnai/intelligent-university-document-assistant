import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { GraduationCap, History, Loader2, MessageSquarePlus, Sparkles, Trash2 } from 'lucide-react'
import api, { describeError } from '../api/axios'
import AiChat from '../components/AiChat'
import ErrorState from '../components/ErrorState'
import Navbar from '../components/Navbar'
import SearchSelect from '../components/SearchSelect'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import PageHeader from '../components/ui/PageHeader'
import Skeleton from '../components/ui/Skeleton'
import { buttonClass, labelClass } from '../components/ui/styles'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import useConfirm from '../hooks/useConfirm'
import { ROLE_STUDENT } from '../constants/roles'
import { formatDateTime } from '../utils/dates'
import { pluralRu } from '../utils/plural'

const ALL_COURSES = { value: '', label: 'Все мои курсы' }
const SUGGESTIONS = ['Какие дедлайны на этой неделе?', 'Когда сдавать лабораторную 2?', 'Как выставляется итоговая оценка?']
const HISTORY_SIZE = 20

const courseLabel = (item) => (item.courseCode ? `${item.courseCode} · ${item.courseTitle}` : item.courseTitle)
const questionsWord = (count) => pluralRu(count, 'вопрос', 'вопроса', 'вопросов')

/**
 * "ИИ-ассистент": questions in plain language over all of the user's courses (POST /ai/ask) or one of them
 * (courseId in the body). The backend searches only the courses the user may access. US-14: every question is saved;
 * "История" lists the user's conversations, opens one to read and continue it (in its own scope) and deletes it.
 * The remaining questions of the hour come from GET /ai/limits.
 */
export default function AssistantPage() {
  const { user } = useAuth()
  const { notifyError } = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const isStudent = user.role === ROLE_STUDENT
  const [courseCount, setCourseCount] = useState(null) // null while loading
  const [error, setError] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [scope, setScope] = useState(ALL_COURSES)
  // The chat on screen: a new one, or a saved conversation { id, turns }. A new session key remounts the chat.
  const [session, setSession] = useState({ key: 'new-0', conversation: null })
  const [activeId, setActiveId] = useState(null)
  const [openingId, setOpeningId] = useState(null)
  const [history, setHistory] = useState(null) // the first page of GET /ai/conversations; null while loading
  const [historyVersion, setHistoryVersion] = useState(0)
  const [limits, setLimits] = useState(null)

  useEffect(() => {
    let cancelled = false
    api.get('/courses', { params: { size: 1 } })
      .then(({ data }) => {
        if (cancelled) return
        setCourseCount(data.totalElements)
        setError(null)
      })
      .catch((err) => !cancelled && setError(describeError(err)))
    return () => {
      cancelled = true
    }
  }, [attempt])

  useEffect(() => {
    let cancelled = false
    api.get('/ai/conversations', { params: { size: HISTORY_SIZE } })
      .then(({ data }) => !cancelled && setHistory(data))
      .catch(() => !cancelled && setHistory({ content: [], totalElements: 0, failed: true }))
    api.get('/ai/limits')
      .then(({ data }) => !cancelled && setLimits(data))
      .catch(() => !cancelled && setLimits(null))
    return () => {
      cancelled = true
    }
  }, [historyVersion])

  const refreshHistory = useCallback(() => setHistoryVersion((value) => value + 1), [])

  // "Все мои курсы" heads the first page; a search lists matching courses only.
  const fetchScopes = useCallback((term) => api.get('/courses', { params: { size: 20, ...(term ? { q: term } : {}) } })
    .then(({ data }) => {
      const courses = data.content.map((item) => ({
        value: String(item.id),
        label: item.code ? `${item.code} · ${item.title}` : item.title,
      }))
      return term ? courses : [ALL_COURSES, ...courses]
    }), [])

  const startNew = (nextScope = scope) => {
    setScope(nextScope)
    setActiveId(null)
    setSession({ key: `new-${Date.now()}`, conversation: null })
  }

  const open = async (item) => {
    if (openingId) return
    setOpeningId(item.id)
    try {
      const { data } = await api.get(`/ai/conversations/${item.id}`)
      setScope(data.courseId ? { value: String(data.courseId), label: courseLabel(data) } : ALL_COURSES)
      setActiveId(data.id)
      setSession({ key: `c${data.id}-${Date.now()}`, conversation: { id: data.id, turns: data.turns } })
    } catch (err) {
      notifyError(err)
      refreshHistory()
    } finally {
      setOpeningId(null)
    }
  }

  const remove = async (item) => {
    const confirmed = await confirm({
      title: 'Удалить разговор?',
      message: `«${item.title}» и ответы ИИ в нём удалятся без возможности восстановления.`,
      confirmLabel: 'Удалить',
    })
    if (!confirmed) return
    try {
      await api.delete(`/ai/conversations/${item.id}`)
      if (item.id === activeId) startNew()
    } catch (err) {
      notifyError(err)
    } finally {
      refreshHistory()
    }
  }

  const courseId = scope.value ? Number(scope.value) : undefined

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6 sm:py-10">
        <PageHeader
          icon={Sparkles}
          title="ИИ-ассистент"
          subtitle={isStudent
            ? 'Вопросы обычным языком по документам и заданиям курсов, на которые вы записаны'
            : 'Вопросы обычным языком по документам и заданиям ваших курсов'}
        />

        <div className="mt-6">
          {error ? (
            <ErrorState
              title="Не удалось загрузить курсы"
              message={error.message}
              onRetry={() => {
                setError(null)
                setCourseCount(null)
                setAttempt((value) => value + 1)
              }}
            />
          ) : courseCount === null ? (
            <div role="status" className="space-y-3">
              <span className="sr-only">Загрузка…</span>
              <Skeleton className="h-11 w-full max-w-sm" rounded="rounded-control" />
              <Skeleton className="h-96 w-full" rounded="rounded-card" />
            </div>
          ) : courseCount === 0 ? (
            <EmptyState
              icon={GraduationCap}
              title={isStudent ? 'Вы пока не записаны ни на один курс' : 'У вас пока нет курсов'}
              description="ИИ-ассистент отвечает только по документам ваших курсов."
              action={<Link to="/courses" className={buttonClass({ variant: 'primary' })}>Курсы</Link>}
            />
          ) : (
            <div className="grid grid-cols-1 gap-6 lg:grid-cols-[17rem_minmax(0,1fr)]">
              <HistoryPanel
                history={history}
                activeId={activeId}
                openingId={openingId}
                onOpen={open}
                onRemove={remove}
                onNew={() => startNew(ALL_COURSES)}
              />
              <div className="min-w-0 space-y-4">
                <div className="flex flex-wrap items-end justify-between gap-3">
                  <div className="w-full max-w-sm">
                    <label htmlFor="assistant-scope" className={labelClass}>Где искать ответ</label>
                    <SearchSelect
                      id="assistant-scope"
                      value={scope}
                      // Another scope is another conversation: a saved one keeps the scope it was started with.
                      onChange={(option) => startNew(option ?? ALL_COURSES)}
                      fetchOptions={fetchScopes}
                      placeholder="Все мои курсы"
                      searchPlaceholder="Название или код курса"
                      emptyText="Курсы не найдены"
                      ariaLabel="Где искать ответ"
                    />
                  </div>
                  <LimitsNote limits={limits} />
                </div>
                <AiChat
                  key={session.key}
                  endpoint="/ai/ask"
                  courseId={courseId}
                  conversation={session.conversation}
                  onConversation={(id) => {
                    setActiveId(id)
                    refreshHistory()
                  }}
                  onAnswered={refreshHistory}
                  title={courseId ? `Вопрос по курсу «${scope.label}»` : 'Вопрос по всем моим курсам'}
                  subtitle={courseId
                    ? 'Поиск по доступным вам документам и заданиям этого курса'
                    : 'Поиск по доступным вам документам и заданиям всех ваших курсов'}
                  placeholder="Например: когда сдавать лабораторную 2?"
                  suggestions={SUGGESTIONS}
                  showDocuments
                />
              </div>
            </div>
          )}
        </div>
      </main>
      {confirmDialog}
    </div>
  )
}

/** US-14 AC-5: how many questions are left this hour; a warning once few are left. */
function LimitsNote({ limits }) {
  if (!limits || limits.remainingThisHour < 0) return null
  const left = limits.remainingThisHour
  const low = left <= 3
  return (
    <p role="status" className={`text-xs ${low ? 'font-medium text-warning-fg' : 'text-fg-muted'}`}>
      {left === 0
        ? 'Лимит вопросов на этот час исчерпан'
        : `Осталось ${left} ${questionsWord(left)} из ${limits.perHour} в этот час`}
    </p>
  )
}

/** "История": the user's saved conversations, the latest first; open one to continue it, or delete it. */
function HistoryPanel({ history, activeId, openingId, onOpen, onRemove, onNew }) {
  return (
    <aside aria-label="История разговоров" className="flex max-h-[38rem] flex-col overflow-hidden rounded-card border border-line bg-surface shadow-card lg:sticky lg:top-24">
      <div className="flex items-center justify-between gap-2 border-b border-line-subtle px-4 py-3">
        <h2 className="flex items-center gap-2 text-sm font-semibold text-fg">
          <History className="h-4 w-4 text-primary" aria-hidden="true" /> История
        </h2>
        <Button size="sm" variant="secondary" icon={MessageSquarePlus} onClick={onNew}>Новый</Button>
      </div>
      {history === null ? (
        <div role="status" className="space-y-2 p-4">
          <span className="sr-only">Загрузка…</span>
          {[0, 1, 2].map((index) => <Skeleton key={index} className="h-10 w-full" rounded="rounded-control" />)}
        </div>
      ) : history.content.length === 0 ? (
        <p className="px-4 py-5 text-xs text-fg-muted">
          {history.failed
            ? 'Не удалось загрузить историю.'
            : 'Здесь появятся ваши разговоры с ИИ. Их можно открыть и продолжить позже.'}
        </p>
      ) : (
        <ul className="flex-1 divide-y divide-line-subtle overflow-y-auto">
          {history.content.map((item) => {
            const active = item.id === activeId
            return (
              <li key={item.id} className={`group flex items-start gap-1 pr-2 ${active ? 'bg-primary-soft' : 'hover:bg-page'}`}>
                <button
                  type="button"
                  onClick={() => onOpen(item)}
                  aria-current={active || undefined}
                  className="min-w-0 flex-1 px-4 py-2.5 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary"
                >
                  <span className={`line-clamp-2 break-words text-sm ${active ? 'font-semibold text-primary-fg' : 'font-medium text-fg'}`}>
                    {openingId === item.id && <Loader2 className="mr-1 inline h-3.5 w-3.5 animate-spin" aria-hidden="true" />}
                    {item.title}
                  </span>
                  <span className="mt-0.5 block truncate text-[11px] text-fg-muted">
                    {item.courseId ? courseLabel(item) : 'Все мои курсы'} · {item.turnCount} {questionsWord(item.turnCount)}
                  </span>
                  <span className="block text-[11px] tabular-nums text-fg-muted">{formatDateTime(item.updatedAt)}</span>
                </button>
                <Button
                  size="sm"
                  variant="danger-ghost"
                  iconOnly
                  icon={Trash2}
                  onClick={() => onRemove(item)}
                  aria-label={`Удалить разговор «${item.title}»`}
                  title="Удалить разговор"
                  className="mt-2 opacity-70 group-hover:opacity-100"
                />
              </li>
            )
          })}
        </ul>
      )}
      {history?.totalElements > HISTORY_SIZE && (
        <p className="border-t border-line-subtle px-4 py-2 text-[11px] text-fg-muted">
          Показаны последние {HISTORY_SIZE} из {history.totalElements}
        </p>
      )}
    </aside>
  )
}
