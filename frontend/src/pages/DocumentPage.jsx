import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { ArrowLeft, Download, Eye, FileText, GraduationCap, Loader2, LockKeyhole, MessageSquareText, RefreshCw, ShieldAlert, Sparkles, Trash2 } from 'lucide-react'
import api, { describeError, getBlobErrorMessage } from '../api/axios'
import { useToast } from '../context/ToastContext'
import useConfirm from '../hooks/useConfirm'
import Navbar from '../components/Navbar'
import DocumentPreview from '../components/DocumentPreview'
import AnalysisPanel from '../components/AnalysisPanel'
import DocumentChat from '../components/DocumentChat'
import DocumentAiBadge from '../components/DocumentAiBadge'
import ReclassifyButton from '../components/ReclassifyButton'
import { VISIBILITY_LABELS } from '../constants/documents'
import { canDeleteDocument, formatSize, getFileFormat } from '../utils/fileTypes'
import { hasProtectedOriginal, isOriginalRestricted, isPiiUnknown } from '../utils/privacy'
import DocumentTextPanel from '../components/DocumentTextPanel'
import PiiBadge from '../components/PiiBadge'
import SetCategoryDialog from '../components/SetCategoryDialog'
import OcrBadge from '../components/OcrBadge'
import { formatDateTime } from '../utils/dates'
import { safeInternalPath } from '../utils/postLoginRedirect'
import useDocumentStatus from '../hooks/useDocumentStatus'
import { isClassifying, withLiveStatus } from '../utils/documentStatus'
import Skeleton, { SkeletonLines } from '../components/ui/Skeleton'
import { badgeClass } from '../components/ui/styles'

const TABS = [
  { id: 'preview', label: 'Просмотр', icon: Eye },
  { id: 'text', label: 'Текст', icon: FileText },
  { id: 'analysis', label: 'Анализ ИИ и задачи', icon: Sparkles },
  { id: 'chat', label: 'Чат с ИИ', icon: MessageSquareText },
]

// Where the document was opened from (state.from of the link): a course page, the dashboard or the vault.
// A direct link has no state and falls back to the vault.
function backTarget(state) {
  const from = safeInternalPath(state?.from)
  if (from?.startsWith('/courses/')) return { to: from, label: 'К курсу' }
  if (from === '/dashboard') return { to: from, label: 'Панель' }
  return { to: '/documents', label: 'Хранилище документов' }
}

export default function DocumentPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const location = useLocation()
  const back = backTarget(location.state)
  const { notifyError, showSuccess } = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [searchParams, setSearchParams] = useSearchParams()
  const tab = TABS.some((item) => item.id === searchParams.get('tab')) ? searchParams.get('tab') : 'preview'
  const openTab = (tabId) => setSearchParams(tabId === 'preview' ? {} : { tab: tabId }, { replace: true, state: location.state })

  const [doc, setDoc] = useState(null)
  const [error, setError] = useState(null) // { status, message }
  const [busy, setBusy] = useState(false)
  const [attempt, setAttempt] = useState(0) // Bumped by "Повторить".

  useEffect(() => {
    let cancelled = false
    api.get(`/documents/${id}`)
      .then(({ data }) => !cancelled && setDoc(data))
      .catch((err) => !cancelled && setError(describeError(err)))
    return () => {
      cancelled = true
    }
  }, [id, attempt])

  const retry = () => {
    setError(null)
    setAttempt((value) => value + 1)
  }

  // While the AI classifies the document (after upload or a reclassify), poll it - including the deadline analysis
  // that follows, so the analysis tab sees the new assignments.
  const pending = useMemo(() => (doc && isClassifying(doc) ? [doc] : []), [doc])
  const liveStatuses = useDocumentStatus(pending)
  const liveDoc = doc ? withLiveStatus(doc, liveStatuses[doc.id]) : null

  const download = async () => {
    setBusy(true)
    try {
      const { data } = await api.get(`/documents/${doc.id}/download`, { responseType: 'blob', timeout: 0 })
      const url = URL.createObjectURL(data)
      const link = document.createElement('a')
      link.href = url
      link.download = doc.originalFileName
      link.click()
      URL.revokeObjectURL(url)
    } catch (err) {
      await getBlobErrorMessage(err) // Parses the Blob error body in place, so notifyError sees the JSON message.
      notifyError(err)
    } finally {
      setBusy(false)
    }
  }

  const remove = async () => {
    const confirmed = await confirm({
      title: 'Удалить документ?',
      message: `«${doc.originalFileName}» будет удалён вместе с его задачами. Это действие нельзя отменить.`,
    })
    if (!confirmed) return
    setBusy(true)
    try {
      await api.delete(`/documents/${doc.id}`)
      showSuccess(`Документ «${doc.originalFileName}» удалён`)
      navigate(back.to, { replace: true })
    } catch (err) {
      notifyError(err)
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto max-w-6xl px-4 py-6 sm:px-6 sm:py-8">
        <Link to={back.to} className="inline-flex items-center gap-1.5 rounded-sm text-sm font-medium text-fg-muted hover:text-primary-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary">
          <ArrowLeft className="h-4 w-4" aria-hidden="true" /> {back.label}
        </Link>

        {error ? (
          <OpenError error={error} onRetry={retry} />
        ) : !doc ? (
          <div role="status" className="mt-4 space-y-6">
            <span className="sr-only">Загрузка документа…</span>
            <div className="flex gap-4 rounded-card border border-line bg-surface p-6 shadow-card">
              <Skeleton className="h-12 w-12 shrink-0" rounded="rounded-control" />
              <div className="flex-1">
                <Skeleton className="h-7 w-2/3" />
                <SkeletonLines lines={2} className="mt-4" />
              </div>
            </div>
            <Skeleton className="h-[50vh] w-full" rounded="rounded-card" />
          </div>
        ) : (
          <>
            <DocumentHeader
              doc={liveDoc}
              busy={busy}
              onDownload={download}
              onDelete={remove}
              onUpdated={(changes) => setDoc((current) => ({ ...current, ...changes }))}
            />

            <div className="mt-8 flex gap-1 overflow-x-auto rounded-control border border-line bg-page-alt p-1" role="tablist">
              {TABS.map(({ id: tabId, label, icon: Icon }) => (
                <button
                  key={tabId}
                  type="button"
                  role="tab"
                  aria-selected={tab === tabId}
                  onClick={() => openTab(tabId)}
                  className={`flex shrink-0 items-center gap-2 rounded-md px-3.5 py-2 text-sm font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${
                    tab === tabId
                      ? 'bg-surface text-primary-fg shadow-soft ring-1 ring-line'
                      : 'text-fg-secondary hover:bg-surface/60 hover:text-fg'
                  }`}
                >
                  <Icon className="h-4 w-4" aria-hidden="true" /> {label}
                </button>
              ))}
            </div>

            <div className="mt-6" role="tabpanel">
              {tab === 'preview' && <DocumentPreview doc={liveDoc} onShowText={() => openTab('text')} />}
              {tab === 'text' && <DocumentTextPanel doc={liveDoc} />}
              {tab === 'analysis' && <AnalysisPanel doc={liveDoc} />}
              {tab === 'chat' && <DocumentChat doc={liveDoc} />}
            </div>
          </>
        )}
      </main>
      {confirmDialog}
    </div>
  )
}

function DocumentHeader({ doc, busy, onDownload, onDelete, onUpdated }) {
  const format = getFileFormat(doc)
  const FormatIcon = format.icon
  return (
    <section className="mt-4 flex flex-col gap-5 rounded-card border border-line bg-surface p-5 shadow-card sm:p-6 lg:flex-row lg:items-start">
      <span className={`hidden h-12 w-12 shrink-0 items-center justify-center rounded-control sm:flex ${format.tile}`}>
        <FormatIcon className="h-6 w-6" aria-hidden="true" />
      </span>
      <div className="min-w-0 flex-1">
        <h1 className="break-words text-[1.375rem] leading-8 font-semibold text-fg sm:text-title">{doc.originalFileName}</h1>
        <p className="mt-1 text-sm tabular-nums text-fg-muted">
          {formatSize(doc.fileSize)} · {formatDateTime(doc.uploadedAt)} · {doc.ownerFullName || doc.ownerEmail}
          {doc.isOwner && <span className="text-primary-fg"> (вы)</span>}
        </p>
        <div className="mt-4 flex flex-wrap items-center gap-1.5">
          <span className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-bold leading-5 ring-1 ring-inset ${format.badge}`}>{format.label}</span>
          <DocumentAiBadge doc={doc} />
          <span className={badgeClass('success', 'md')}>
            <LockKeyhole aria-hidden="true" /> {VISIBILITY_LABELS[doc.visibility] ?? doc.visibility}
          </span>
          {doc.courseId && (
            <Link
              to={`/courses/${doc.courseId}`}
              className={`${badgeClass('primary', 'md')} max-w-full hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary`}
            >
              <GraduationCap aria-hidden="true" /> <span className="truncate">{doc.courseCode ? `${doc.courseCode} · ` : ''}{doc.courseTitle}</span>
            </Link>
          )}
          {!doc.hasExtractedText && (
            <span className={badgeClass('warning', 'md')}>
              <FileText aria-hidden="true" /> Текст не распознан
            </span>
          )}
          <PiiBadge doc={doc} size="large" />
          <OcrBadge doc={doc} size="large" />
        </div>
        {(doc.ocrStatus === 'FAILED' || doc.aiStatus === 'FAILED') && canDeleteDocument(doc) && <OcrReview doc={doc} onUpdated={onUpdated} />}
        {hasProtectedOriginal(doc) && canDeleteDocument(doc) && <OriginalAccess doc={doc} onUpdated={onUpdated} />}
      </div>
      <div className="flex shrink-0 flex-wrap gap-2">
        {canDeleteDocument(doc) && <ReclassifyButton doc={doc} onUpdated={onUpdated} size="large" />}
        {canDeleteDocument(doc) && (
          <button
            type="button"
            onClick={onDelete}
            disabled={busy}
            className="flex h-10 items-center gap-2 rounded-control border border-danger/40 px-4 text-sm font-medium text-danger-fg transition-colors hover:bg-danger-soft focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring disabled:opacity-60"
          >
            <Trash2 className="h-4 w-4" aria-hidden="true" /> Удалить
          </button>
        )}
        {isOriginalRestricted(doc) ? (
          <span className="flex min-h-10 items-center gap-2 rounded-control border border-line bg-page-alt px-3.5 py-2 text-sm text-fg-secondary">
            <LockKeyhole className="h-4 w-4" aria-hidden="true" />
            {isPiiUnknown(doc) && !doc.piiCount ? 'Скан ещё не проверен на личные данные' : 'Оригинал доступен только преподавателю'}
          </span>
        ) : (
          <button
            type="button"
            onClick={onDownload}
            disabled={busy}
            className="flex h-10 items-center gap-2 rounded-control bg-primary px-4 text-sm font-semibold text-on-primary shadow-soft transition-colors hover:bg-primary-hover focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring disabled:opacity-60"
          >
            {busy ? <Loader2 className="h-4 w-4 animate-spin" aria-hidden="true" /> : <Download className="h-4 w-4" aria-hidden="true" />} Скачать
          </button>
        )}
      </div>
    </section>
  )
}

/**
 * Course teacher / ADMIN, when the scan's text could not be recognized or the AI could not type the document ("Нужна
 * проверка"; students do not see it until a type is set): set the type by hand, or for a scan run the recognition
 * again (the processing job is queued anew; the page then follows its progress).
 */
function OcrReview({ doc, onUpdated }) {
  const { notifyError, showSuccess } = useToast()
  const [choosing, setChoosing] = useState(false)
  const [retrying, setRetrying] = useState(false)
  const ocrFailed = doc.ocrStatus === 'FAILED'

  const retry = async () => {
    setRetrying(true)
    try {
      const { data } = await api.post(`/documents/${doc.id}/ocr/retry`)
      onUpdated(data)
      showSuccess('Скан поставлен на повторное распознавание')
    } catch (err) {
      notifyError(err)
    } finally {
      setRetrying(false)
    }
  }

  const action = 'inline-flex items-center gap-1.5 rounded-md border px-2.5 py-1 font-medium transition disabled:opacity-60'
  return (
    <div className="mt-3 rounded-control border border-warning/30 bg-warning-soft px-3 py-2.5 text-xs text-warning-fg">
      <p className="font-semibold">
        {ocrFailed ? 'Нужна проверка: текст скана распознать не удалось.' : 'Нужна проверка: ИИ не определил тип документа.'}
      </p>
      <p className="mt-0.5">
        {doc.aiStatus === 'MANUAL' ? '' : 'Студенты не видят документ, пока вы не укажете его тип. '}
        {ocrFailed ? 'Укажите тип документа сами или запустите распознавание ещё раз.' : 'Укажите тип документа сами.'}
      </p>
      <div className="mt-2 flex flex-wrap gap-2">
        <button
          type="button"
          onClick={() => setChoosing(true)}
          disabled={retrying}
          className={`${action} border-warning/30 bg-surface text-fg hover:bg-warning-soft`}
        >
          Указать тип вручную
        </button>
        {ocrFailed && (
          <button
            type="button"
            onClick={retry}
            disabled={retrying}
            className={`${action} border-warning/30 bg-surface text-fg hover:bg-warning-soft`}
          >
            {retrying ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}
            Повторить распознавание
          </button>
        )}
      </div>
      {choosing && <SetCategoryDialog doc={doc} onClose={() => setChoosing(false)} onSaved={onUpdated} />}
    </div>
  )
}

/**
 * Course teacher / ADMIN: the "Разрешить студентам оригинал" switch. Either change is confirmed first; allowing it
 * makes every opening of the file by a student an entry in the system log.
 */
function OriginalAccess({ doc, onUpdated }) {
  const { notifyError, showSuccess } = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [saving, setSaving] = useState(false)
  const allowed = doc.originalAllowedForStudents
  // A scan without recognized text: personal data are not known to be absent, nothing is masked yet.
  const unchecked = isPiiUnknown(doc) && !doc.piiCount

  const toggle = async () => {
    const confirmed = await confirm(allowed ? {
      title: 'Закрыть оригинал для студентов?',
      message: unchecked
        ? 'Студенты не смогут открыть скан, пока его текст не распознан и не проверен на личные данные.'
        : 'Студенты снова будут видеть только текст, в котором личные данные скрыты.',
      confirmLabel: 'Закрыть',
      tone: 'primary',
    } : {
      title: 'Разрешить студентам оригинал?',
      message: unchecked
        ? 'Скан не проверен на личные данные: студенты курса смогут открыть и скачать его как есть. Каждое открытие попадёт в журнал.'
        : 'Студенты курса смогут открыть и скачать файл вместе с личными данными. Каждое открытие попадёт в журнал.',
      confirmLabel: 'Разрешить',
      tone: 'primary',
    })
    if (!confirmed) return
    setSaving(true)
    try {
      const { data } = await api.post(`/documents/${doc.id}/${allowed ? 'deny-original' : 'allow-original'}`)
      onUpdated({ originalAllowedForStudents: data.originalAllowedForStudents })
      showSuccess(data.originalAllowedForStudents ? 'Студенты могут открыть оригинал' : 'Студентам снова доступен только текст без личных данных')
    } catch (err) {
      notifyError(err)
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="mt-3 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-fg-secondary">
      <button
        type="button"
        role="switch"
        aria-checked={allowed}
        onClick={toggle}
        disabled={saving}
        className="inline-flex items-center gap-2 rounded-control font-medium text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:opacity-60"
      >
        <span
          aria-hidden="true"
          className={`relative inline-flex h-5 w-9 shrink-0 items-center rounded-full transition ${allowed ? 'bg-primary' : 'bg-line'}`}
        >
          <span className={`inline-block h-4 w-4 rounded-full bg-surface shadow transition ${allowed ? 'translate-x-4' : 'translate-x-0.5'}`} />
        </span>
        Разрешить студентам оригинал
        {saving && <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" />}
      </button>
      <span>
        {allowed ? 'Студенты могут открыть файл; каждое открытие записывается в журнал.'
          : unchecked ? 'Скан не проверен на личные данные: студенты не могут открыть оригинал.'
            : 'Студенты видят только текст со скрытыми личными данными.'}
      </span>
      {confirmDialog}
    </div>
  )
}

function OpenError({ error, onRetry }) {
  const forbidden = error.status === 403
  return (
    <div className="mx-auto mt-16 flex max-w-md flex-col items-center text-center">
      <span className={`flex h-14 w-14 items-center justify-center rounded-card ${forbidden ? 'bg-warning-soft text-warning' : 'bg-page-alt text-fg-muted'}`}>
        <ShieldAlert className="h-7 w-7" />
      </span>
      <h1 className="mt-4 text-lg font-semibold text-fg">
        {forbidden ? 'Нет доступа к документу' : error.status === 404 ? 'Документ не найден' : 'Не удалось открыть документ'}
      </h1>
      <p className="mt-1 text-sm text-fg-muted">
        {forbidden ? 'Документ виден только участникам курса или преподавателям. Обратитесь к владельцу документа.' : error.message}
      </p>
      {!forbidden && error.status !== 404 && (
        <button
          type="button"
          onClick={onRetry}
          className="mt-4 inline-flex items-center gap-1.5 rounded-control bg-primary px-3.5 py-2 text-sm font-semibold text-on-primary transition hover:bg-primary-hover"
        >
          <RefreshCw className="h-4 w-4" /> Повторить
        </button>
      )}
    </div>
  )
}
