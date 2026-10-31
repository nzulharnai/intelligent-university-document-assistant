import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { AlertTriangle, ArrowLeft, Check, ChevronRight, Copy, FileText, FolderOpen, GraduationCap, RefreshCw, ShieldAlert, UserRound, Users, X } from 'lucide-react'
import api, { describeError } from '../api/axios'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { ROLE_ADMIN, ROLE_STUDENT } from '../constants/roles'
import { VISIBILITY_LABELS } from '../constants/documents'
import Navbar from '../components/Navbar'
import ConfirmDialog from '../components/ConfirmDialog'
import ErrorState from '../components/ErrorState'
import CourseChat from '../components/CourseChat'
import CourseAssignments from '../components/CourseAssignments'
import CourseDrafts from '../components/CourseDrafts'
import CourseStudents from '../components/CourseStudents'
import DocumentUpload from '../components/DocumentUpload'
import DocumentAiBadge from '../components/DocumentAiBadge'
import PiiBadge from '../components/PiiBadge'
import OcrBadge from '../components/OcrBadge'
import ReclassifyButton from '../components/ReclassifyButton'
import { canDeleteDocument, getFileFormat } from '../utils/fileTypes'
import { formatDateTime } from '../utils/dates'
import useDocumentStatus from '../hooks/useDocumentStatus'
import { isClassifying, withLiveStatus } from '../utils/documentStatus'
import Badge from '../components/ui/Badge'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import Skeleton, { SkeletonLines } from '../components/ui/Skeleton'

const DOCS_PAGE_SIZE = 10

export default function CoursePage() {
  const { id } = useParams()
  const { role } = useAuth()
  const [details, setDetails] = useState(null)
  const [error, setError] = useState(null)
  const [docsVersion, setDocsVersion] = useState(0) // Bumped after an upload to reload the list.
  // Bumped after drafts are published, so the course's assignments list shows them.
  const [assignmentsVersion, setAssignmentsVersion] = useState(0)

  const loadDetails = useCallback(() => api.get(`/courses/${id}`).then(({ data }) => setDetails(data)), [id])

  useEffect(() => {
    loadDetails().catch((err) => setError(describeError(err)))
  }, [loadDetails])

  const retryDetails = () => {
    setError(null)
    loadDetails().catch((err) => setError(describeError(err)))
  }

  const course = details?.course
  const isStudent = role === ROLE_STUDENT
  // Only the course's teacher and ADMIN manage its documents; students read them.
  const canManage = Boolean(details?.canManage)
  // The backend answers 403 to anyone who is neither enrolled in the course nor teaches it.
  const notMember = error?.status === 403

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8">
        <Link to="/courses" className="inline-flex items-center gap-1.5 rounded-sm text-sm font-medium text-fg-muted hover:text-primary-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary">
          <ArrowLeft className="h-4 w-4" aria-hidden="true" /> Все курсы
        </Link>

        {error ? (
          <div className="mx-auto mt-16 flex max-w-md flex-col items-center text-center">
            <span className="flex h-12 w-12 items-center justify-center rounded-full bg-page-alt text-fg-muted">
              <ShieldAlert className="h-6 w-6" aria-hidden="true" />
            </span>
            <h1 className="mt-3 text-lg font-semibold text-fg">
              {error.status === 404 ? 'Курс не найден' : notMember ? 'Нет доступа к курсу' : 'Не удалось открыть курс'}
            </h1>
            <p className="mt-1 text-sm text-fg-muted">
              {notMember
                ? (isStudent
                  ? 'Материалы курса доступны только записанным студентам. Чтобы записаться, откройте ссылку-приглашение от преподавателя.'
                  : 'Курс ведёт другой преподаватель. Вы можете управлять только своими курсами.')
                : error.message}
            </p>
            {error.status !== 403 && error.status !== 404 && (
              <Button icon={RefreshCw} onClick={retryDetails} className="mt-4">
                Повторить
              </Button>
            )}
          </div>
        ) : !details ? (
          <div role="status" className="mt-4 space-y-6">
            <span className="sr-only">Загрузка курса…</span>
            <div className="rounded-card border border-line bg-surface p-6 shadow-card">
              <Skeleton className="h-5 w-20" />
              <Skeleton className="mt-3 h-8 w-2/3" />
              <SkeletonLines lines={2} className="mt-4" />
            </div>
            <Skeleton className="h-64 w-full" rounded="rounded-card" />
          </div>
        ) : (
          <>
            <section className="relative mt-4 flex flex-col gap-5 overflow-hidden rounded-card border border-line bg-surface p-5 pt-6 shadow-card sm:p-6 sm:pt-7 lg:flex-row lg:items-start">
              <span className="absolute inset-x-0 top-0 h-1 bg-primary" aria-hidden="true" />
              <span className="hidden h-12 w-12 shrink-0 items-center justify-center rounded-control bg-primary-soft text-primary sm:flex">
                <GraduationCap className="h-6 w-6" aria-hidden="true" />
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex flex-wrap items-center gap-2">
                  {course.code && <span className="rounded-md bg-primary-soft px-1.5 py-0.5 text-xs font-bold tabular-nums text-primary-fg">{course.code}</span>}
                  {role === ROLE_ADMIN && <span className="text-xs tabular-nums text-fg-muted">Курс № {course.id}</span>}
                  {details.enrolled && <Badge tone="success" icon={Check}>Вы записаны</Badge>}
                </div>
                <h1 className="mt-2 break-words text-[1.5rem] leading-8 font-semibold text-fg sm:text-title">{course.title}</h1>
                {course.description && <p className="mt-2 max-w-reading whitespace-pre-wrap text-body text-fg-secondary">{course.description}</p>}
                <p className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-1 text-small text-fg-muted">
                  <span className="flex items-center gap-1.5"><UserRound className="h-3.5 w-3.5" aria-hidden="true" /> Преподаватель: {course.teacherFullName || course.teacherEmail || 'не назначен'}</span>
                  <span className="flex items-center gap-1.5 tabular-nums"><Users className="h-3.5 w-3.5" aria-hidden="true" /> Студентов: {details.studentCount}</span>
                </p>
              </div>
              {canManage && (
                <div className="flex shrink-0 flex-wrap gap-2">
                  <InviteLinkControls courseId={course.id} />
                </div>
              )}
            </section>

            <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
              <div className="space-y-6">
                {canManage && <DocumentUpload course={course} onUploaded={() => setDocsVersion((version) => version + 1)} />}
                <CourseDocuments courseId={course.id} canManage={canManage} version={docsVersion} />
                {canManage && (
                  <CourseDrafts courseId={course.id} version={docsVersion} onPublished={() => setAssignmentsVersion((version) => version + 1)} />
                )}
                <CourseAssignments courseId={course.id} canManage={canManage} version={docsVersion + assignmentsVersion} />
                {canManage && <CourseStudents courseId={course.id} onChange={() => loadDetails().catch(() => {})} />}
              </div>
              <div className="lg:sticky lg:top-24 lg:self-start">
                <CourseChat course={course} />
              </div>
            </div>
          </>
        )}
      </main>
    </div>
  )
}

/**
 * The invitation link /join/{code} that teachers share with students - the only way to join a course. "Новая ссылка"
 * replaces the code, so the old link stops working (e.g. after it leaked).
 */
function InviteLinkControls({ courseId }) {
  const { notifyError, showSuccess } = useToast()
  const [code, setCode] = useState(null)
  const [copied, setCopied] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const [regenerating, setRegenerating] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    api.get(`/courses/${courseId}/invite`)
      .then(({ data }) => {
        if (cancelled) return
        setCode(data.inviteCode)
        setLoadError('')
      })
      .catch((err) => !cancelled && setLoadError(describeError(err).message))
    return () => {
      cancelled = true
    }
  }, [courseId, attempt])

  useEffect(() => {
    if (!copied) return undefined
    const timer = setTimeout(() => setCopied(false), 2000)
    return () => clearTimeout(timer)
  }, [copied])

  const link = code ? `${window.location.origin}/join/${code}` : ''

  const copy = () => {
    if (!navigator.clipboard) {
      window.prompt('Скопируйте ссылку для студентов:', link)
      return
    }
    navigator.clipboard.writeText(link).then(() => setCopied(true)).catch(() => window.prompt('Скопируйте ссылку для студентов:', link))
  }

  const regenerate = async () => {
    setRegenerating(true)
    try {
      const { data } = await api.post(`/courses/${courseId}/invite/regenerate`)
      setCode(data.inviteCode)
      setConfirming(false)
      showSuccess('Создана новая ссылка. Старая больше не действует.')
    } catch (err) {
      notifyError(err)
    } finally {
      setRegenerating(false)
    }
  }

  if (loadError && !code) {
    return (
      <ErrorState
        compact
        message="Не удалось загрузить ссылку"
        onRetry={() => {
          setLoadError('')
          setAttempt((value) => value + 1)
        }}
        className="max-w-xs"
      />
    )
  }
  return (
    <>
      <Button variant="secondary" icon={copied ? Check : Copy} onClick={copy} disabled={!code} title={link || 'Загрузка ссылки…'}>
        {copied ? 'Скопировано' : 'Ссылка для студентов'}
      </Button>
      <Button variant="outline" icon={RefreshCw} onClick={() => setConfirming(true)} disabled={!code} title="Сгенерировать новую ссылку" aria-label="Новая ссылка для студентов">
        <span className="hidden sm:inline">Новая ссылка</span>
      </Button>
      {confirming && (
        <ConfirmDialog
          title="Сгенерировать новую ссылку?"
          confirmLabel="Сгенерировать"
          loading={regenerating}
          onConfirm={regenerate}
          onCancel={() => setConfirming(false)}
        >
          Текущая ссылка-приглашение сразу перестанет работать. Уже записанные студенты останутся на курсе;
          новым студентам нужно будет отправить новую ссылку.
        </ConfirmDialog>
      )}
    </>
  )
}

/**
 * The course's documents. The teacher and ADMIN also get "Требуют проверки: N" - documents students do not see because
 * the AI or the OCR failed on them, until a type is set by hand - which filters the list to them.
 */
function CourseDocuments({ courseId, canManage, version }) {
  const { notifyError } = useToast()
  const [page, setPage] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [reviewOnly, setReviewOnly] = useState(false)
  // null until known (or when the count could not be loaded): then no counter is shown.
  const [reviewCount, setReviewCount] = useState(null)

  const fetchPage = useCallback((number) => api.get('/documents', {
    params: { courseId, page: number, size: DOCS_PAGE_SIZE, ...(reviewOnly ? { needsReview: true } : {}) },
  }).then(({ data }) => data), [courseId, reviewOnly])

  useEffect(() => {
    if (!canManage) return undefined
    let cancelled = false
    api.get('/documents', { params: { courseId, needsReview: true, size: 1 } })
      .then(({ data }) => !cancelled && setReviewCount(data.totalElements))
      .catch(() => !cancelled && setReviewCount(null))
    return () => {
      cancelled = true
    }
  }, [canManage, courseId, version, attempt])

  const toggleReview = () => {
    setPage(null)
    setLoading(true)
    setReviewOnly((value) => !value)
  }

  useEffect(() => {
    let cancelled = false
    fetchPage(0)
      .then((data) => {
        if (cancelled) return
        setPage(data)
        setError('')
      })
      .catch((err) => !cancelled && setError(describeError(err).message))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [fetchPage, version, attempt])

  const retry = () => {
    setError('')
    setLoading(true)
    setAttempt((value) => value + 1)
  }

  const updateDoc = useCallback((id, changes) => setPage((current) => current && ({
    ...current,
    content: current.content.map((doc) => (doc.id === id ? { ...doc, ...changes } : doc)),
  })), [])

  // Rows the AI is still classifying are polled (one request for all of them) and shown live.
  const pendingDocs = useMemo(() => (page?.content ?? []).filter(isClassifying), [page])
  const liveStatuses = useDocumentStatus(pendingDocs, { deadlines: false })

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

  return (
    <section className="rounded-card border border-line bg-surface shadow-card">
      <div className="flex flex-wrap items-center gap-2 border-b border-line-subtle px-5 py-4 sm:px-6">
        <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
          <FileText className="h-4.5 w-4.5 text-primary" aria-hidden="true" /> Документы курса
          {page && !reviewOnly && <span className="rounded-full bg-page-alt px-2 py-0.5 text-xs font-semibold tabular-nums text-fg-secondary">{page.totalElements}</span>}
        </h2>
        {canManage && (reviewOnly || reviewCount > 0) && (
          <button
            type="button"
            onClick={toggleReview}
            aria-pressed={reviewOnly}
            title={reviewOnly ? 'Показать все документы' : 'Студенты не видят эти документы, пока вы не укажете их тип'}
            className={`ml-auto inline-flex items-center gap-1 rounded-full px-2.5 py-1 text-xs font-semibold tabular-nums ring-1 ring-inset transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${reviewOnly
              ? 'bg-warning text-on-primary ring-warning hover:bg-warning/90'
              : 'bg-warning-soft text-warning-fg ring-warning/30 hover:ring-warning/60'}`}
          >
            <AlertTriangle className="h-3.5 w-3.5" aria-hidden="true" />
            Требуют проверки: {reviewCount ?? 0}
            {reviewOnly && <X className="h-3.5 w-3.5" aria-hidden="true" />}
          </button>
        )}
      </div>
      {error ? (
        <ErrorState title="Не удалось загрузить документы курса" message={error} onRetry={retry} />
      ) : !page && loading ? (
        <div role="status" className="space-y-3 px-5 py-4 sm:px-6">
          <span className="sr-only">Загрузка…</span>
          {[0, 1, 2].map((index) => (
            <div key={index} className="flex items-center gap-3">
              <Skeleton className="h-9 w-9 shrink-0" rounded="rounded-control" />
              <SkeletonLines lines={2} className="flex-1" />
            </div>
          ))}
        </div>
      ) : page?.content.length === 0 ? (
        <div className="p-5 sm:p-6">
          <EmptyState
            compact
            icon={FolderOpen}
            title={reviewOnly ? 'Документов, которые требуют проверки, нет.' : 'Доступных вам документов в курсе пока нет.'}
          />
        </div>
      ) : page && (
        <>
          <ul className="divide-y divide-line-subtle">
            {page.content.map((row) => {
              const doc = withLiveStatus(row, liveStatuses[row.id])
              const format = getFileFormat(doc)
              const FormatIcon = format.icon
              return (
                <li key={doc.id} className="flex items-center gap-2 pr-3 transition-colors hover:bg-page">
                  <Link to={`/documents/${doc.id}`} state={{ from: `/courses/${courseId}` }} className="group flex min-w-0 flex-1 items-center gap-3 py-3 pl-4 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary sm:pl-6">
                    <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-control ${format.tile}`}><FormatIcon className="h-4 w-4" aria-hidden="true" /></span>
                    <span className="min-w-0 flex-1">
                      <span className="block truncate text-sm font-medium text-fg group-hover:text-primary-fg">{doc.originalFileName}</span>
                      <span className="mt-1 flex min-w-0 flex-wrap items-center gap-x-1.5 gap-y-1 text-xs text-fg-muted">
                        <DocumentAiBadge doc={doc} />
                        <PiiBadge doc={doc} />
                        <OcrBadge doc={doc} />
                        <span className="truncate tabular-nums">{VISIBILITY_LABELS[doc.visibility]} · {formatDateTime(doc.uploadedAt)}</span>
                      </span>
                    </span>
                    <ChevronRight className="h-4 w-4 shrink-0 text-fg-muted group-hover:text-primary" aria-hidden="true" />
                  </Link>
                  {canDeleteDocument(doc) && (
                    <ReclassifyButton doc={doc} onUpdated={(changes) => updateDoc(doc.id, changes)} compact />
                  )}
                </li>
              )
            })}
          </ul>
          {!page.last && (
            <div className="border-t border-line-subtle p-3">
              <Button variant="ghost" onClick={loadMore} loading={loading} className="w-full">
                Показать ещё
              </Button>
            </div>
          )}
        </>
      )}
    </section>
  )
}
