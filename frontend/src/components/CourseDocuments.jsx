import { useCallback, useEffect, useId, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle, CalendarClock, CalendarRange, Check, ChevronDown, ClipboardList, Eye, EyeOff, FileText, Filter, FolderOpen, List, Loader2, Pencil, Settings2, Sparkles, Upload, X } from 'lucide-react'
import api, { describeError } from '../api/axios'
import { CATEGORY_OPTIONS } from '../constants/documents'
import { useToast } from '../context/ToastContext'
import { formatDateTime } from '../utils/dates'
import useDocumentStatus from '../hooks/useDocumentStatus'
import { isClassifying, withLiveStatus } from '../utils/documentStatus'
import { pluralRu } from '../utils/plural'
import ConfirmDialog from './ConfirmDialog'
import CourseDocumentRow from './CourseDocumentRow'
import ErrorState from './ErrorState'
import Button from './ui/Button'
import Dialog, { DialogActions } from './ui/Dialog'
import EmptyState from './ui/EmptyState'
import Skeleton, { SkeletonLines } from './ui/Skeleton'
import { controlClass, controlErrorClass, errorTextClass, hintClass, labelClass } from './ui/styles'

const DOCS_PAGE_SIZE = 10
const WEEK_PAGE_SIZE = 20
const MAX_WEEKS = 30
// "Предложить недели": the counters are refreshed this often while the AI works, for at most two minutes.
const SUGGEST_POLL_MS = 4_000
const SUGGEST_POLL_LIMIT_MS = 120_000

const documentsWord = (count) => pluralRu(count, 'документ', 'документа', 'документов')
const assignmentsWord = (count) => pluralRu(count, 'задание', 'задания', 'заданий')
// US-16: the assignments shown inside the weeks; a course rarely has more.
const WEEK_ASSIGNMENTS_SIZE = 100
const NO_FILTERS = { category: '', from: '', to: '' }

/** The filters as GET /documents params (only those set); null while the dates are the wrong way round. */
function filterParams(filters) {
  if (filters.from && filters.to && filters.from > filters.to) return null
  return Object.fromEntries(Object.entries(filters).filter(([, value]) => value))
}

/**
 * The course's documents, "По неделям" (US-16) or "Списком" (as before). The teacher and ADMIN also get "Требуют
 * проверки: N" - documents students do not see because the AI or the OCR failed on them, until a type is set by hand -
 * which shows them as a list. "По неделям" is the default when the course has a week plan or a document with a week.
 */
export default function CourseDocuments({ courseId, canManage, version }) {
  const { notifyError } = useToast()
  const [overview, setOverview] = useState(null) // GET /courses/{id}/weeks; null while loading or unavailable
  const [overviewVersion, setOverviewVersion] = useState(0)
  const [view, setView] = useState(null) // 'weeks' | 'list'; null until the overview decides the default
  const [reviewOnly, setReviewOnly] = useState(false)
  // null until known (or when the count could not be loaded): then no counter is shown.
  const [reviewCount, setReviewCount] = useState(null)
  const [sectionsVersion, setSectionsVersion] = useState(0)
  const [listTotal, setListTotal] = useState(null) // "Списком": the count next to the title, as before
  const [filters, setFilters] = useState(NO_FILTERS) // US-16 AC-5: category and upload dates
  const filtering = Boolean(filters.category || filters.from || filters.to)

  const reloadOverview = useCallback(() => setOverviewVersion((value) => value + 1), [])
  // A week changed: the counters, and the opened sections that may have gained or lost the document.
  const weeksChanged = useCallback(() => {
    reloadOverview()
    setSectionsVersion((value) => value + 1)
  }, [reloadOverview])

  useEffect(() => {
    let cancelled = false
    api.get(`/courses/${courseId}/weeks`)
      .then(({ data }) => {
        if (cancelled) return
        setOverview(data)
        setView((current) => current ?? (data.weeks.some((week) => week.topic || week.documentCount > 0 || week.assignmentCount > 0) ? 'weeks' : 'list'))
      })
      .catch((err) => {
        if (cancelled) return
        // Without the weeks the list still works; say why the weeks view is missing only once.
        setView((current) => current ?? 'list')
        if (overviewVersion > 0) notifyError(err)
      })
    return () => {
      cancelled = true
    }
  }, [courseId, version, overviewVersion, notifyError])

  useEffect(() => {
    if (!canManage) return undefined
    let cancelled = false
    api.get('/documents', { params: { courseId, needsReview: true, size: 1 } })
      .then(({ data }) => !cancelled && setReviewCount(data.totalElements))
      .catch(() => !cancelled && setReviewCount(null))
    return () => {
      cancelled = true
    }
  }, [canManage, courseId, version])

  const toggleReview = () => {
    setReviewOnly((value) => !value)
    setView('list') // The documents to check are shown as a list.
  }

  // Filtered documents are shown as one list: the week sections count all of a week's documents.
  const effectiveView = reviewOnly || filtering || !overview ? 'list' : view
  const weeksCount = canManage ? overview?.weeksCount : undefined

  return (
    <section className="rounded-card border border-line bg-surface shadow-card">
      <div className="flex flex-wrap items-center gap-2 border-b border-line-subtle px-5 py-4 sm:px-6">
        <h2 className="mr-auto flex items-center gap-2 text-base font-semibold text-fg">
          <FileText className="h-4.5 w-4.5 text-primary" aria-hidden="true" /> Документы курса
          {effectiveView === 'list' && !reviewOnly && !filtering && listTotal != null && (
            <span className="rounded-full bg-page-alt px-2 py-0.5 text-xs font-semibold tabular-nums text-fg-secondary">{listTotal}</span>
          )}
        </h2>
        {canManage && (reviewOnly || reviewCount > 0) && (
          <button
            type="button"
            onClick={toggleReview}
            aria-pressed={reviewOnly}
            title={reviewOnly ? 'Показать все документы' : 'Студенты не видят эти документы, пока вы не укажете их тип'}
            className={`inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-semibold tabular-nums ring-1 ring-inset transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${reviewOnly
              ? 'bg-warning text-on-primary ring-warning hover:bg-warning/90'
              : 'bg-warning-soft text-warning-fg ring-warning/30 hover:ring-warning/60'}`}
          >
            <AlertTriangle className="h-3.5 w-3.5" aria-hidden="true" />
            Требуют проверки: {reviewCount ?? 0}
            {reviewOnly && <X className="h-3.5 w-3.5" aria-hidden="true" />}
          </button>
        )}
        {overview && !reviewOnly && !filtering && (
          <div role="group" aria-label="Вид списка документов" className="flex rounded-control border border-line bg-page-alt p-0.5">
            <ViewButton active={effectiveView === 'weeks'} icon={CalendarRange} onClick={() => setView('weeks')}>По неделям</ViewButton>
            <ViewButton active={effectiveView === 'list'} icon={List} onClick={() => setView('list')}>Списком</ViewButton>
          </div>
        )}
      </div>
      {!reviewOnly && <DocumentFilters filters={filters} onChange={setFilters} />}
      {view === null ? (
        <ListSkeleton />
      ) : effectiveView === 'weeks' ? (
        <WeeksView
          courseId={courseId}
          canManage={canManage}
          overview={overview}
          onOverviewChanged={reloadOverview}
          onWeeksChanged={weeksChanged}
          sectionsVersion={sectionsVersion + version}
        />
      ) : (
        <DocumentsList
          courseId={courseId}
          reviewOnly={reviewOnly}
          filters={reviewOnly ? NO_FILTERS : filters}
          version={version}
          weeksCount={weeksCount}
          onWeekChanged={reloadOverview}
          onTotal={setListTotal}
        />
      )}
    </section>
  )
}

function ViewButton({ active, icon: Icon, onClick, children }) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={`inline-flex items-center gap-1.5 rounded-md px-2.5 py-1 text-xs font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${active
        ? 'bg-surface text-primary-fg shadow-soft ring-1 ring-line'
        : 'text-fg-secondary hover:text-fg'}`}
    >
      <Icon className="h-3.5 w-3.5" aria-hidden="true" /> {children}
    </button>
  )
}

function ListSkeleton() {
  return (
    <div role="status" className="space-y-3 px-5 py-4 sm:px-6">
      <span className="sr-only">Загрузка…</span>
      {[0, 1, 2].map((index) => (
        <div key={index} className="flex items-center gap-3">
          <Skeleton className="h-9 w-9 shrink-0" rounded="rounded-control" />
          <SkeletonLines lines={2} className="flex-1" />
        </div>
      ))}
    </div>
  )
}

/**
 * Pages of GET /documents with {@code params}; rows the AI is still classifying are polled and shown live.
 * {@code reloadKey} reloads from the first page.
 */
function usePagedDocuments(params, reloadKey, enabled = true) {
  const { notifyError } = useToast()
  const [page, setPage] = useState(null)
  const [error, setError] = useState('')
  // The request whose answer (page or error) is on screen; another key means the first page is being loaded.
  const [loadedKey, setLoadedKey] = useState(null)
  const [loadingMore, setLoadingMore] = useState(false)
  const [attempt, setAttempt] = useState(0)
  const paramsKey = JSON.stringify(params)
  const requestKey = `${paramsKey}|${reloadKey}|${attempt}`

  const fetchPage = useCallback((number) => api.get('/documents', {
    params: { ...JSON.parse(paramsKey), page: number },
  }).then(({ data }) => data), [paramsKey])

  useEffect(() => {
    if (!enabled) return undefined
    let cancelled = false
    fetchPage(0)
      .then((data) => {
        if (cancelled) return
        setPage(data)
        setError('')
      })
      .catch((err) => !cancelled && setError(describeError(err).message))
      .finally(() => !cancelled && setLoadedKey(requestKey))
    return () => {
      cancelled = true
    }
  }, [fetchPage, requestKey, enabled])

  const loading = (enabled && loadedKey !== requestKey) || loadingMore

  const retry = () => {
    setError('')
    setAttempt((value) => value + 1)
  }

  const updateDoc = useCallback((id, changes) => setPage((current) => current && ({
    ...current,
    content: current.content.map((doc) => (doc.id === id ? { ...doc, ...changes } : doc)),
  })), [])

  const pendingDocs = useMemo(() => (page?.content ?? []).filter(isClassifying), [page])
  const liveStatuses = useDocumentStatus(pendingDocs, { deadlines: false })

  const loadMore = async () => {
    setLoadingMore(true)
    try {
      const next = await fetchPage(page.page + 1)
      setPage({ ...next, content: [...page.content, ...next.content] })
    } catch (err) {
      notifyError(err)
    } finally {
      setLoadingMore(false)
    }
  }

  const rows = (page?.content ?? []).map((row) => withLiveStatus(row, liveStatuses[row.id]))
  return { page, rows, loading, error, retry, updateDoc, loadMore }
}

/**
 * US-16 AC-5: category and upload dates ("с" and "по", both days included). While any is set the documents are one
 * list; "Сбросить" clears them.
 */
function DocumentFilters({ filters, onChange }) {
  const categoryId = useId()
  const fromId = useId()
  const toId = useId()
  const errorId = useId()
  const set = (key) => (event) => onChange({ ...filters, [key]: event.target.value })
  const active = Boolean(filters.category || filters.from || filters.to)
  const wrongOrder = filterParams(filters) === null
  const smallControl = 'h-8 rounded-control border border-line-strong bg-surface px-2 text-xs text-fg outline-none transition focus:border-primary focus:ring-4 focus:ring-ring'

  return (
    <div role="group" aria-label="Фильтр документов" className="flex flex-wrap items-end gap-x-3 gap-y-2 border-b border-line-subtle px-5 py-3 sm:px-6">
      <Filter className="mb-2 h-3.5 w-3.5 text-fg-muted" aria-hidden="true" />
      <div className="flex flex-col gap-1">
        <label htmlFor={categoryId} className="text-[11px] font-medium text-fg-muted">Категория</label>
        <select id={categoryId} value={filters.category} onChange={set('category')} className={smallControl}>
          <option value="">Все категории</option>
          {CATEGORY_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
        </select>
      </div>
      <div className="flex flex-col gap-1">
        <label htmlFor={fromId} className="text-[11px] font-medium text-fg-muted">Загружены с</label>
        <input id={fromId} type="date" value={filters.from} max={filters.to || undefined} onChange={set('from')}
          aria-invalid={wrongOrder || undefined} aria-describedby={wrongOrder ? errorId : undefined} className={smallControl} />
      </div>
      <div className="flex flex-col gap-1">
        <label htmlFor={toId} className="text-[11px] font-medium text-fg-muted">по</label>
        <input id={toId} type="date" value={filters.to} min={filters.from || undefined} onChange={set('to')}
          aria-invalid={wrongOrder || undefined} aria-describedby={wrongOrder ? errorId : undefined} className={smallControl} />
      </div>
      {active && (
        <Button size="sm" variant="ghost" icon={X} onClick={() => onChange(NO_FILTERS)}>Сбросить</Button>
      )}
      {wrongOrder && <p id={errorId} role="alert" className="w-full text-xs text-danger-fg">Дата «с» позже даты «по».</p>}
    </div>
  )
}

/** "Списком": the documents as before, newest first, 10 at a time; {@code filters} narrow them (US-16 AC-5). */
function DocumentsList({ courseId, reviewOnly, filters, version, weeksCount, onWeekChanged, onTotal }) {
  const filter = filterParams(filters)
  const filtered = filter !== null && Object.keys(filter).length > 0
  const filterKey = JSON.stringify(filter ?? {})
  const params = useMemo(() => ({
    courseId,
    size: DOCS_PAGE_SIZE,
    ...(reviewOnly ? { needsReview: true } : {}),
    ...JSON.parse(filterKey),
  }), [courseId, reviewOnly, filterKey])
  const { page, rows, loading, error, retry, updateDoc, loadMore } = usePagedDocuments(params, version, filter !== null)
  const total = page?.totalElements ?? null
  useEffect(() => {
    onTotal?.(total)
  }, [total, onTotal])

  if (error) return <ErrorState title="Не удалось загрузить документы курса" message={error} onRetry={retry} />
  if (!page && loading) return <ListSkeleton />
  if (page?.content.length === 0) {
    return (
      <div className="p-5 sm:p-6">
        <EmptyState
          compact
          icon={FolderOpen}
          title={reviewOnly
            ? 'Документов, которые требуют проверки, нет.'
            : filtered ? 'По фильтру ничего не найдено.' : 'Доступных вам документов в курсе пока нет.'}
        />
      </div>
    )
  }
  if (!page) return null
  return (
    <>
      <ul className="divide-y divide-line-subtle">
        {rows.map((doc) => (
          <CourseDocumentRow
            key={doc.id}
            doc={doc}
            courseId={courseId}
            weeksCount={weeksCount}
            onUpdated={(changes) => updateDoc(doc.id, changes)}
            onWeekChanged={onWeekChanged}
          />
        ))}
      </ul>
      {!page.last && (
        <div className="border-t border-line-subtle p-3">
          <Button variant="ghost" onClick={loadMore} loading={loading} className="w-full">Показать ещё</Button>
        </div>
      )}
    </>
  )
}

/**
 * "По неделям": collapsible "Неделя N · тема" sections, then "Без недели"; a section loads its documents when opened.
 * A student sees only the weeks with documents they can see (the backend sends only those) and changes nothing; the
 * teacher sees every week, empty ones dimmed, and edits topics, weeks and hints.
 */
function WeeksView({ courseId, canManage, overview, onOverviewChanged, onWeeksChanged, sectionsVersion }) {
  const { notifyError, showSuccess } = useToast()
  const [assignmentsVersion, setAssignmentsVersion] = useState(0)
  const assignments = useWeekAssignments(courseId, `${sectionsVersion}|${assignmentsVersion}`)
  const assignmentsChanged = useCallback(() => {
    setAssignmentsVersion((value) => value + 1)
    onOverviewChanged()
  }, [onOverviewChanged])
  const [confirmAccept, setConfirmAccept] = useState(false)
  const [accepting, setAccepting] = useState(false)
  const [suggesting, setSuggesting] = useState(false)
  const [aiWorking, setAiWorking] = useState(null) // { documents } while the AI suggests weeks
  const [settingsOpen, setSettingsOpen] = useState(false)

  // While the AI suggests weeks the counters are refreshed every few seconds, for at most two minutes.
  useEffect(() => {
    if (!aiWorking) return undefined
    const interval = setInterval(onOverviewChanged, SUGGEST_POLL_MS)
    const stop = setTimeout(() => setAiWorking(null), SUGGEST_POLL_LIMIT_MS)
    return () => {
      clearInterval(interval)
      clearTimeout(stop)
    }
  }, [aiWorking, onOverviewChanged])

  const acceptAll = async () => {
    setAccepting(true)
    try {
      const { data } = await api.post(`/courses/${courseId}/weeks/accept-all`)
      showSuccess(`Принято подсказок: ${data.accepted}`)
      setConfirmAccept(false)
      onWeeksChanged()
    } catch (err) {
      notifyError(err)
    } finally {
      setAccepting(false)
    }
  }

  const suggest = async () => {
    setSuggesting(true)
    try {
      const { data } = await api.post(`/courses/${courseId}/weeks/suggest`)
      if (data.documents > 0) {
        setAiWorking({ documents: data.documents })
      } else {
        showSuccess('Все документы уже распределены по неделям или имеют подсказку')
      }
    } catch (err) {
      notifyError(err)
    } finally {
      setSuggesting(false)
    }
  }

  const pending = overview.pendingSuggestions ?? 0
  const hasPlan = overview.weeks.some((week) => week.topic)
  const withoutWeekAssignments = overview.withoutWeekAssignmentCount ?? 0
  const sections = [
    ...overview.weeks.map((week) => ({ key: week.week, week })),
    ...(overview.withoutWeekCount > 0 || withoutWeekAssignments > 0 || canManage ? [{ key: 'none', week: null }] : []),
  ]
  const assignmentsOf = (week) => (assignments ?? []).filter((item) => (item.weekNumber ?? null) === (week ? week.week : null))

  return (
    <div>
      {canManage && (
        <div className="flex flex-wrap items-center gap-2 border-b border-line-subtle px-5 py-3 sm:px-6">
          {pending > 0 && (
            <Button size="sm" icon={Check} onClick={() => setConfirmAccept(true)}>
              Принять все подсказки ({pending})
            </Button>
          )}
          <Button size="sm" variant="secondary" icon={Sparkles} onClick={suggest} loading={suggesting} disabled={Boolean(aiWorking)}>
            Предложить недели
          </Button>
          <Button size="sm" variant="ghost" icon={Settings2} onClick={() => setSettingsOpen(true)}>
            Настроить недели
          </Button>
          {aiWorking && (
            <p role="status" className="flex w-full items-center gap-1.5 text-xs text-primary-fg">
              <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" />
              ИИ подбирает недели для {aiWorking.documents} {documentsWord(aiWorking.documents)}
            </p>
          )}
        </div>
      )}
      {canManage && !hasPlan && (
        <p className="flex items-start gap-2 border-b border-line-subtle bg-primary-soft px-5 py-3 text-sm text-primary-fg sm:px-6">
          <Upload className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
          Загрузите силлабус — ИИ составит план недель и предложит неделю для каждого документа
        </p>
      )}
      {sections.length === 0 ? (
        <div className="p-5 sm:p-6">
          <EmptyState compact icon={FolderOpen} title="Доступных вам документов в курсе пока нет." />
        </div>
      ) : (
        <ul className="divide-y divide-line-subtle">
          {sections.map(({ key, week }) => (
            <WeekSection
              key={key}
              courseId={courseId}
              week={week}
              count={week ? week.documentCount : overview.withoutWeekCount}
              assignmentCount={week ? (week.assignmentCount ?? 0) : withoutWeekAssignments}
              assignments={assignments === null ? null : assignmentsOf(week)}
              canManage={canManage}
              weeksCount={canManage ? overview.weeksCount : undefined}
              reloadKey={sectionsVersion}
              onTopicSaved={onOverviewChanged}
              onWeekChanged={onWeeksChanged}
              onAssignmentsChanged={assignmentsChanged}
            />
          ))}
        </ul>
      )}
      {confirmAccept && (
        <ConfirmDialog
          title="Принять все подсказки ИИ?"
          tone="primary"
          confirmLabel="Принять все"
          loading={accepting}
          onConfirm={acceptAll}
          onCancel={() => setConfirmAccept(false)}
        >
          {pending} {documentsWord(pending)} перейдут в недели, которые предложил ИИ. Студенты увидят их в этих неделях.
          Неделю любого документа можно потом изменить.
        </ConfirmDialog>
      )}
      {settingsOpen && (
        <WeeksSettingsDialog
          courseId={courseId}
          weeksCount={overview.weeksCount}
          onClose={() => setSettingsOpen(false)}
          onSaved={() => {
            setSettingsOpen(false)
            onWeeksChanged()
          }}
        />
      )}
    </div>
  )
}

/**
 * The course's assignments for the weeks view (US-16 AC-1): one request, grouped by week on the client. null while
 * loading; on failure the weeks still show their documents, without assignments.
 */
function useWeekAssignments(courseId, reloadKey) {
  // The previous list stays while a reload runs, so the sections do not flicker.
  const [items, setItems] = useState(null)
  useEffect(() => {
    let cancelled = false
    api.get(`/courses/${courseId}/assignments`, { params: { size: WEEK_ASSIGNMENTS_SIZE } })
      .then(({ data }) => !cancelled && setItems(data.content))
      .catch(() => !cancelled && setItems([]))
    return () => {
      cancelled = true
    }
  }, [courseId, reloadKey])
  return items
}

function WeekSection({
  courseId, week, count, assignmentCount, assignments, canManage, weeksCount, reloadKey, onTopicSaved, onWeekChanged,
  onAssignmentsChanged,
}) {
  const { notifyError, showSuccess } = useToast()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState(false)
  const [togglingHidden, setTogglingHidden] = useState(false)
  const panelId = useId()
  const params = useMemo(() => ({ courseId, week: week ? week.week : 'none', size: WEEK_PAGE_SIZE }), [courseId, week])
  const { page, rows, loading, error, retry, updateDoc, loadMore } = usePagedDocuments(params, reloadKey, open)
  const empty = count === 0 && assignmentCount === 0
  const hidden = Boolean(week?.hidden)
  const title = week ? `Неделя ${week.week}` : 'Без недели'

  // US-16 AC-4: hide the week (and its documents) from students, or show it again.
  const toggleHidden = async () => {
    setTogglingHidden(true)
    try {
      await api.put(`/courses/${courseId}/weeks/${week.week}/visibility`, { hidden: !hidden })
      showSuccess(hidden ? `Неделя ${week.week} снова видна студентам` : `Неделя ${week.week} скрыта от студентов`)
      onTopicSaved()
    } catch (err) {
      notifyError(err)
    } finally {
      setTogglingHidden(false)
    }
  }

  return (
    <li>
      <div className={`flex items-center gap-1 pr-3 ${empty || hidden ? 'text-fg-muted' : 'text-fg'} ${hidden ? 'bg-page-alt/60' : ''}`}>
        {editing ? (
          <TopicEditor
            courseId={courseId}
            week={week}
            onDone={(saved) => {
              setEditing(false)
              if (saved) onTopicSaved()
            }}
          />
        ) : (
          <button
            type="button"
            onClick={() => setOpen((value) => !value)}
            aria-expanded={open}
            aria-controls={panelId}
            className="flex min-w-0 flex-1 items-center gap-2 py-3 pl-4 text-left focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary sm:pl-6"
          >
            <ChevronDown className={`h-4 w-4 shrink-0 transition-transform ${open ? 'rotate-180' : ''}`} aria-hidden="true" />
            <span className={`min-w-0 flex-1 break-words text-sm ${empty ? 'font-normal' : 'font-semibold'}`}>
              {title}{week?.topic ? <span className="font-normal text-fg-secondary"> · {week.topic}</span> : null}
              {hidden && (
                <span className="ml-2 inline-flex items-center gap-1 rounded-full bg-warning-soft px-2 py-0.5 align-middle text-[11px] font-medium text-warning-fg">
                  <EyeOff className="h-3 w-3" aria-hidden="true" /> Скрыта от студентов
                </span>
              )}
            </span>
            {assignmentCount > 0 && (
              <span className="inline-flex shrink-0 items-center gap-1 rounded-full bg-success-soft px-2 py-0.5 text-xs font-semibold tabular-nums text-success-fg" title="Задания недели">
                <ClipboardList className="h-3 w-3" aria-hidden="true" />
                {assignmentCount}
                <span className="sr-only"> {assignmentsWord(assignmentCount)}</span>
              </span>
            )}
            <span className={`shrink-0 rounded-full px-2 py-0.5 text-xs font-semibold tabular-nums ${count === 0 ? 'bg-page-alt text-fg-muted' : 'bg-primary-soft text-primary-fg'}`}>
              {count}
              <span className="sr-only"> {documentsWord(count)}</span>
            </span>
          </button>
        )}
        {canManage && week && !editing && (
          <>
            <Button
              size="sm"
              variant="ghost"
              iconOnly
              icon={hidden ? Eye : EyeOff}
              onClick={toggleHidden}
              loading={togglingHidden}
              aria-label={hidden ? `Показать неделю ${week.week} студентам` : `Скрыть неделю ${week.week} от студентов`}
              title={hidden ? 'Показать студентам' : 'Скрыть от студентов'}
            />
            <Button size="sm" variant="ghost" iconOnly icon={Pencil} onClick={() => setEditing(true)} aria-label={`Изменить тему недели ${week.week}`} title="Изменить тему" />
          </>
        )}
      </div>
      {open && (
        <div id={panelId} className="border-t border-line-subtle bg-page/40">
          {hidden && (
            <p className="flex items-center gap-1.5 border-b border-line-subtle px-5 py-2 text-xs text-warning-fg sm:px-6">
              <EyeOff className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              Студенты не видят эту неделю и её документы — ни на странице курса, ни в поиске, ни в ответах ИИ.
            </p>
          )}
          {assignments?.length > 0 && (
            <WeekAssignments
              courseId={courseId}
              assignments={assignments}
              canManage={canManage}
              weeksCount={weeksCount}
              onChanged={onAssignmentsChanged}
            />
          )}
          {error ? (
            <ErrorState compact title="Не удалось загрузить документы недели" message={error} onRetry={retry} />
          ) : !page && loading ? (
            <ListSkeleton />
          ) : page?.content.length === 0 ? (
            assignments?.length > 0 ? null : (
              <p className="px-5 py-4 text-sm text-fg-muted sm:px-6">В этой неделе пока ничего нет.</p>
            )
          ) : page && (
            <>
              <ul className="divide-y divide-line-subtle">
                {rows.map((doc) => (
                  <CourseDocumentRow
                    key={doc.id}
                    doc={doc}
                    courseId={courseId}
                    weeksCount={weeksCount}
                    onUpdated={(changes) => updateDoc(doc.id, changes)}
                    onWeekChanged={onWeekChanged}
                  />
                ))}
              </ul>
              {!page.last && (
                <div className="border-t border-line-subtle p-3">
                  <Button variant="ghost" onClick={loadMore} loading={loading} className="w-full">Показать ещё</Button>
                </div>
              )}
            </>
          )}
        </div>
      )}
    </li>
  )
}

/**
 * US-16 AC-1: the assignments of a week section. The teacher moves one to another week (or "Без недели") with the
 * select and opens its grades; a student sees the title, the deadline and whether it is closed.
 */
function WeekAssignments({ courseId, assignments, canManage, weeksCount, onChanged }) {
  return (
    <div className="border-b border-line-subtle">
      <p className="flex items-center gap-1.5 px-5 pt-3 text-[11px] font-semibold uppercase tracking-wide text-fg-muted sm:px-6">
        <ClipboardList className="h-3 w-3" aria-hidden="true" /> Задания ({assignments.length})
      </p>
      <ul className="divide-y divide-line-subtle">
        {assignments.map((assignment) => (
          <WeekAssignmentRow
            key={assignment.id}
            courseId={courseId}
            assignment={assignment}
            canManage={canManage}
            weeksCount={weeksCount}
            onChanged={onChanged}
          />
        ))}
      </ul>
    </div>
  )
}

function WeekAssignmentRow({ courseId, assignment, canManage, weeksCount, onChanged }) {
  const { notifyError, showSuccess } = useToast()
  const selectId = useId()
  const [busy, setBusy] = useState(false)
  const closed = assignment.status === 'CLOSED'

  const move = async (value) => {
    const week = value === '' ? null : Number(value)
    setBusy(true)
    try {
      await api.patch(`/assignments/${assignment.id}/week`, { week })
      showSuccess(`«${assignment.title}» — ${week ? `неделя ${week}` : 'без недели'}`)
      onChanged()
    } catch (err) {
      notifyError(err)
    } finally {
      setBusy(false)
    }
  }

  const titleNode = canManage ? (
    <Link
      to={`/courses/${courseId}/assignments/${assignment.id}`}
      className="rounded-sm font-medium text-fg hover:text-primary-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
    >
      {assignment.title}
    </Link>
  ) : <span className="font-medium text-fg">{assignment.title}</span>

  return (
    <li className="flex flex-wrap items-center gap-x-3 gap-y-1.5 px-5 py-2.5 text-sm sm:px-6">
      <span className="flex h-7 w-7 shrink-0 items-center justify-center rounded-control bg-success-soft text-success-fg">
        <ClipboardList className="h-3.5 w-3.5" aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block break-words">{titleNode}</span>
        <span className="mt-0.5 flex flex-wrap items-center gap-x-2 text-xs text-fg-muted">
          <span className="inline-flex items-center gap-1">
            <CalendarClock className="h-3 w-3" aria-hidden="true" />
            {assignment.deadline ? `до ${formatDateTime(assignment.deadline)}` : 'Срок не указан'}
          </span>
          {closed && <span className="rounded-full bg-page-alt px-1.5 py-0.5 text-[11px] font-medium">Закрыто</span>}
        </span>
      </span>
      {canManage && weeksCount > 0 && (
        <span className="flex items-center gap-2">
          <label htmlFor={selectId} className="text-xs text-fg-muted">Неделя</label>
          <select
            id={selectId}
            value={assignment.weekNumber ?? ''}
            disabled={busy}
            onChange={(event) => move(event.target.value)}
            aria-label={`Неделя задания ${assignment.title}`}
            className="h-8 rounded-control border border-line-strong bg-surface px-2 text-xs text-fg outline-none transition focus:border-primary focus:ring-4 focus:ring-ring disabled:opacity-60"
          >
            <option value="">Без недели</option>
            {Array.from({ length: weeksCount }, (_, index) => index + 1).map((week) => (
              <option key={week} value={week}>Неделя {week}</option>
            ))}
          </select>
        </span>
      )}
    </li>
  )
}

/** The week's topic in the section header: Enter saves, Escape cancels (an empty topic clears it). */
function TopicEditor({ courseId, week, onDone }) {
  const { notifyError } = useToast()
  const [value, setValue] = useState(week.topic ?? '')
  const [saving, setSaving] = useState(false)
  const inputRef = useRef(null)

  useEffect(() => {
    inputRef.current?.focus()
    inputRef.current?.select()
  }, [])

  const save = async () => {
    setSaving(true)
    try {
      await api.put(`/courses/${courseId}/weeks/${week.week}`, { topic: value.trim() || null })
      onDone(true)
    } catch (err) {
      notifyError(err)
      setSaving(false)
    }
  }

  return (
    <div className="flex min-w-0 flex-1 flex-wrap items-center gap-2 py-2 pl-4 sm:pl-6">
      <span className="text-sm font-semibold text-fg">Неделя {week.week}</span>
      <input
        ref={inputRef}
        value={value}
        maxLength={300}
        disabled={saving}
        onChange={(event) => setValue(event.target.value)}
        onKeyDown={(event) => {
          if (event.key === 'Enter') {
            event.preventDefault()
            save()
          } else if (event.key === 'Escape') {
            event.preventDefault()
            onDone(false)
          }
        }}
        placeholder="Тема недели"
        aria-label={`Тема недели ${week.week}. Enter — сохранить, Escape — отменить`}
        className={`${controlClass} h-8 min-w-0 flex-1 basis-40 py-1 text-sm`}
      />
      <Button size="sm" icon={Check} onClick={save} loading={saving} aria-label="Сохранить тему" iconOnly />
      <Button size="sm" variant="ghost" icon={X} onClick={() => onDone(false)} disabled={saving} aria-label="Отменить" iconOnly />
    </div>
  )
}

/** "Настроить недели": the number of weeks, 1..30; WEEK_IN_USE (a later week has documents) is shown under the field. */
function WeeksSettingsDialog({ courseId, weeksCount, onClose, onSaved }) {
  const [value, setValue] = useState(String(weeksCount))
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)
  const inputId = useId()
  const errorId = useId()

  const number = Number(value)
  const valid = Number.isInteger(number) && number >= 1 && number <= MAX_WEEKS

  const submit = async (event) => {
    event.preventDefault()
    if (!valid) {
      setError(`Укажите число от 1 до ${MAX_WEEKS}`)
      return
    }
    setSaving(true)
    try {
      await api.put(`/courses/${courseId}/weeks`, { weeksCount: number })
      onSaved()
    } catch (err) {
      setError(describeError(err).message)
      setSaving(false)
    }
  }

  return (
    <Dialog title="Настроить недели" icon={Settings2} onClose={onClose} busy={saving}>
      <form onSubmit={submit} className="mt-4 space-y-4" noValidate>
        <div>
          <label htmlFor={inputId} className={labelClass}>Число недель в курсе</label>
          <input
            id={inputId}
            type="number"
            inputMode="numeric"
            min={1}
            max={MAX_WEEKS}
            value={value}
            onChange={(event) => {
              setValue(event.target.value)
              setError('')
            }}
            aria-invalid={Boolean(error) || undefined}
            aria-describedby={error ? errorId : undefined}
            className={`${controlClass} ${error ? controlErrorClass : ''}`}
          />
          {error ? (
            <p id={errorId} className={errorTextClass}>{error}</p>
          ) : (
            <p className={hintClass}>От 1 до {MAX_WEEKS}. Темы недель после нового числа удалятся.</p>
          )}
        </div>
        <DialogActions>
          <Button variant="outline" onClick={onClose} disabled={saving}>Отмена</Button>
          <Button type="submit" loading={saving}>Сохранить</Button>
        </DialogActions>
      </form>
    </Dialog>
  )
}
