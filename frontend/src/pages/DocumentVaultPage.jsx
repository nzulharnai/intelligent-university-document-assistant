import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { Download, Eye, FolderLock, FolderOpen, GraduationCap, Loader2, Lock, MessageSquareText, ShieldCheck, Trash2 } from 'lucide-react'
import api, { describeError, getBlobErrorMessage } from '../api/axios'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import useConfirm from '../hooks/useConfirm'
import { ROLE_ADMIN, ROLE_STUDENT } from '../constants/roles'
import { VISIBILITY_LABELS } from '../constants/documents'
import Navbar from '../components/Navbar'
import DocumentUpload from '../components/DocumentUpload'
import ErrorState from '../components/ErrorState'
import useDocumentStatus from '../hooks/useDocumentStatus'
import { isClassifying, withLiveStatus } from '../utils/documentStatus'
import DocumentAiBadge from '../components/DocumentAiBadge'
import PiiBadge from '../components/PiiBadge'
import OcrBadge from '../components/OcrBadge'
import ReclassifyButton from '../components/ReclassifyButton'
import { canDeleteDocument, formatSize, getFileFormat } from '../utils/fileTypes'
import { formatDateTime } from '../utils/dates'
import Badge from '../components/ui/Badge'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import PageHeader from '../components/ui/PageHeader'
import Skeleton, { SkeletonLines } from '../components/ui/Skeleton'
import { badgeClass, tableHeadClass, tableRowClass } from '../components/ui/styles'
import { isOriginalRestricted } from '../utils/privacy'

const PAGE_SIZE = 20

export default function DocumentVaultPage() {
  const { role } = useAuth()
  const { notifyError, showSuccess } = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const isStudent = role === ROLE_STUDENT
  const isAdmin = role === ROLE_ADMIN

  const [page, setPage] = useState(null) // Last loaded page; its content holds all documents loaded so far.
  const [loading, setLoading] = useState(true)
  const [busyId, setBusyId] = useState(null)

  const fetchPage = useCallback((number) => api.get('/documents', { params: { page: number, size: PAGE_SIZE } })
    .then(({ data }) => data), [])

  // A row changed in place (reclassify), without reloading the list.
  const updateDoc = useCallback((id, changes) => setPage((current) => current && ({
    ...current,
    content: current.content.map((doc) => (doc.id === id ? { ...doc, ...changes } : doc)),
  })), [])

  const [error, setError] = useState('')
  // Rows the AI is still classifying are polled (one request for all of them) and shown live.
  const pendingDocs = useMemo(() => (page?.content ?? []).filter(isClassifying), [page])
  const liveStatuses = useDocumentStatus(pendingDocs, { deadlines: false })

  const reload = useCallback(() => fetchPage(0)
    .then((data) => {
      setPage(data)
      setError('')
    })
    .catch((err) => setError(describeError(err).message))
    .finally(() => setLoading(false)), [fetchPage])

  const retry = () => {
    setError('')
    setLoading(true)
    reload()
  }

  useEffect(() => {
    reload()
  }, [reload])

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

  const handleDownload = async (doc) => {
    setBusyId(doc.id)
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
      setBusyId(null)
    }
  }

  const handleDelete = async (doc) => {
    const confirmed = await confirm({
      title: 'Удалить документ?',
      message: `«${doc.originalFileName}» будет удалён вместе с найденными в нём заданиями и задачами студентов. Это действие нельзя отменить.`,
    })
    if (!confirmed) return
    setBusyId(doc.id)
    try {
      await api.delete(`/documents/${doc.id}`)
      await reload()
      showSuccess(`Документ «${doc.originalFileName}» удалён`)
    } catch (err) {
      notifyError(err)
    } finally {
      setBusyId(null)
    }
  }

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6 sm:py-10">
        <PageHeader
          icon={FolderLock}
          title="Хранилище документов"
          subtitle={isStudent
            ? 'Материалы курсов, на которые вы записаны'
            : isAdmin ? 'Все документы системы' : 'Документы курсов, которые вы ведёте'}
        />

        {!isStudent && (
          <div className="mt-8">
            <DocumentUpload onUploaded={reload} />
          </div>
        )}

        <div className="mt-6 flex items-center gap-3 rounded-card border border-success/30 bg-success-soft px-4 py-3 text-sm text-success-fg">
          <ShieldCheck className="h-5 w-5 shrink-0 text-success" aria-hidden="true" />
          <span>
            {isStudent
              ? 'Вы видите только документы своих курсов. Загружает и удаляет документы преподаватель курса.'
              : 'Студенты видят документы только тех курсов, на которые записаны; заметки «Только преподаватель курса» им недоступны.'}
          </span>
        </div>

        <section className="mt-6 overflow-hidden rounded-card border border-line bg-surface shadow-card">
          <div className="flex items-center justify-between gap-3 border-b border-line-subtle px-5 py-4 sm:px-6">
            <h2 className="text-base font-semibold text-fg">Документы</h2>
            {page && (
              <span className="rounded-full bg-page-alt px-2.5 py-0.5 text-xs font-semibold tabular-nums text-fg-secondary">{page.totalElements}</span>
            )}
          </div>
          {error ? (
            <ErrorState title="Не удалось загрузить документы" message={error} onRetry={retry} />
          ) : !page && loading ? (
            <div role="status" className="space-y-4 px-5 py-5 sm:px-6">
              <span className="sr-only">Загрузка списка…</span>
              {[0, 1, 2, 3].map((index) => (
                <div key={index} className="flex items-center gap-3">
                  <Skeleton className="h-9 w-9 shrink-0" rounded="rounded-control" />
                  <SkeletonLines lines={2} className="flex-1" />
                </div>
              ))}
            </div>
          ) : page.content.length === 0 ? (
            <div className="p-5 sm:p-6">
              <EmptyState
                icon={FolderOpen}
                title="Документов пока нет"
                description={isStudent ? 'Запишитесь на курс, чтобы увидеть его материалы.' : 'Загрузите первый файл в один из своих курсов.'}
              />
            </div>
          ) : (
            <>
              {/* Below md: cards instead of the wide table. */}
              <ul className="divide-y divide-line-subtle md:hidden">
                {page.content.map((doc) => (
                  <DocumentCard
                    key={doc.id}
                    doc={withLiveStatus(doc, liveStatuses[doc.id])}
                    busy={busyId === doc.id}
                    onDownload={handleDownload}
                    onDelete={handleDelete}
                    onUpdated={updateDoc}
                  />
                ))}
              </ul>
              <div className="hidden max-h-[75vh] overflow-auto md:block">
                <table className="w-full min-w-[960px] text-left text-sm">
                  <thead className={tableHeadClass}>
                    <tr>
                      <th className="px-4 py-3 font-medium">Документ</th>
                      <th className="px-4 py-3 font-medium">Размер</th>
                      <th className="px-4 py-3 font-medium">Дата загрузки</th>
                      <th className="px-4 py-3 font-medium">Владелец</th>
                      <th className="px-4 py-3 font-medium">Видимость</th>
                      <th className="px-4 py-3 text-right font-medium">Действия</th>
                    </tr>
                  </thead>
                  <tbody>
                    {page.content.map((doc) => (
                      <DocumentRow
                        key={doc.id}
                        doc={withLiveStatus(doc, liveStatuses[doc.id])}
                        busy={busyId === doc.id}
                        onDownload={handleDownload}
                        onDelete={handleDelete}
                        onUpdated={updateDoc}
                      />
                    ))}
                  </tbody>
                </table>
              </div>
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
      </main>
      {confirmDialog}
    </div>
  )
}

const actionClass = 'flex h-8 items-center gap-1.5 rounded-control border px-2.5 text-small font-medium transition-colors focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring disabled:opacity-60'

// File format, AI type, personal data, OCR and course: one aligned row of badges (table and cards).
function DocumentBadges({ doc, format }) {
  return (
    <span className="mt-1.5 flex flex-wrap items-center gap-1.5">
      <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-bold leading-4 ring-1 ring-inset ${format.badge}`}>{format.label}</span>
      <DocumentAiBadge doc={doc} />
      <PiiBadge doc={doc} />
      <OcrBadge doc={doc} />
      {doc.courseCode && <Badge tone="neutral" icon={GraduationCap}>{doc.courseCode}</Badge>}
    </span>
  )
}

function DocumentRow({ doc, busy, onDownload, onDelete, onUpdated }) {
  const format = getFileFormat(doc)
  const FormatIcon = format.icon
  return (
    <tr className={tableRowClass}>
      <td className="max-w-[320px] px-4 py-3">
        <Link to={`/documents/${doc.id}`} className="group flex max-w-full items-center gap-3 rounded-control focus:outline-none focus-visible:ring-4 focus-visible:ring-ring">
          <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-control ${format.tile}`}>
            <FormatIcon className="h-4 w-4" />
          </span>
          <span className="min-w-0">
            <span className="block truncate font-medium text-fg group-hover:text-primary-fg group-hover:underline" title={doc.originalFileName}>
              {doc.originalFileName}
            </span>
            <DocumentBadges doc={doc} format={format} />
          </span>
        </Link>
      </td>
      <td className="whitespace-nowrap px-4 py-3 tabular-nums text-fg-secondary">{formatSize(doc.fileSize)}</td>
      <td className="whitespace-nowrap px-4 py-3 tabular-nums text-fg-secondary">{formatDateTime(doc.uploadedAt)}</td>
      <td className="max-w-[200px] px-4 py-3">
        <span className="block truncate text-fg" title={doc.ownerFullName}>{doc.ownerEmail}</span>
        {doc.isOwner && <span className="mt-0.5 block text-xs text-primary-fg">Вы</span>}
      </td>
      <td className="whitespace-nowrap px-4 py-3">
        <span className={badgeClass('success', 'md')}>
          <Lock aria-hidden="true" />
          {VISIBILITY_LABELS[doc.visibility] ?? doc.visibility}
        </span>
      </td>
      <td className="whitespace-nowrap px-4 py-3">
        <div className="flex justify-end gap-2">
          <Link to={`/documents/${doc.id}`} className={`${actionClass} border-line-strong text-fg hover:border-primary hover:text-primary-fg`}>
            <Eye className="h-3.5 w-3.5" aria-hidden="true" /> Открыть
          </Link>
          {doc.hasExtractedText && (
            <Link to={`/documents/${doc.id}?tab=chat`} className={`${actionClass} border-primary/40 text-primary-fg hover:bg-primary-soft`}>
              <MessageSquareText className="h-3.5 w-3.5" aria-hidden="true" /> Чат с ИИ
            </Link>
          )}
          {!isOriginalRestricted(doc) && (
            <button type="button" onClick={() => onDownload(doc)} disabled={busy} className={`${actionClass} border-line-strong text-fg hover:bg-page-alt`}>
              {busy ? <Loader2 className="h-3.5 w-3.5 animate-spin" aria-hidden="true" /> : <Download className="h-3.5 w-3.5" aria-hidden="true" />} Скачать
            </button>
          )}
          {canDeleteDocument(doc) && (
            <ReclassifyButton doc={doc} onUpdated={(changes) => onUpdated(doc.id, changes)} />
          )}
          {canDeleteDocument(doc) && (
            <button type="button" onClick={() => onDelete(doc)} disabled={busy} className={`${actionClass} border-danger/40 text-danger-fg hover:bg-danger-soft`}>
              <Trash2 className="h-3.5 w-3.5" aria-hidden="true" /> Удалить
            </button>
          )}
        </div>
      </td>
    </tr>
  )
}

const iconActionClass = 'flex h-9 w-9 shrink-0 items-center justify-center rounded-control border transition-colors focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring disabled:opacity-60'

function DocumentCard({ doc, busy, onDownload, onDelete, onUpdated }) {
  const format = getFileFormat(doc)
  const FormatIcon = format.icon
  return (
    <li className="px-4 py-4">
      <Link to={`/documents/${doc.id}`} className="group flex min-w-0 items-start gap-3 rounded-control focus:outline-none focus-visible:ring-4 focus-visible:ring-ring">
        <span className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-control ${format.tile}`}>
          <FormatIcon className="h-4 w-4" />
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium text-fg group-hover:text-primary-fg" title={doc.originalFileName}>
            {doc.originalFileName}
          </span>
          <DocumentBadges doc={doc} format={format} />
          <span className="mt-1 block truncate text-xs tabular-nums text-fg-secondary">
            {formatDateTime(doc.uploadedAt)} · {formatSize(doc.fileSize)}
          </span>
        </span>
      </Link>
      <div className="mt-2 flex flex-wrap justify-end gap-2">
        {doc.hasExtractedText && (
          <Link to={`/documents/${doc.id}?tab=chat`} aria-label="Чат с ИИ" title="Чат с ИИ" className={`${iconActionClass} border-primary/40 text-primary-fg hover:bg-primary-soft`}>
            <MessageSquareText className="h-4 w-4" />
          </Link>
        )}
        {!isOriginalRestricted(doc) && (
          <button type="button" onClick={() => onDownload(doc)} disabled={busy} aria-label="Скачать" title="Скачать" className={`${iconActionClass} border-line-strong text-fg hover:bg-page-alt`}>
            {busy ? <Loader2 className="h-4 w-4 animate-spin" /> : <Download className="h-4 w-4" />}
          </button>
        )}
        {canDeleteDocument(doc) && (
          <ReclassifyButton doc={doc} onUpdated={(changes) => onUpdated(doc.id, changes)} compact />
        )}
        {canDeleteDocument(doc) && (
          <button type="button" onClick={() => onDelete(doc)} disabled={busy} aria-label="Удалить" title="Удалить" className={`${iconActionClass} border-danger/40 text-danger-fg hover:bg-danger-soft`}>
            <Trash2 className="h-4 w-4" />
          </button>
        )}
      </div>
    </li>
  )
}
