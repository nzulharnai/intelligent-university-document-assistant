import { useEffect, useRef, useState } from 'react'
import { CalendarClock, HardDrive, Loader2, User } from 'lucide-react'
import api, { getBlobErrorMessage } from '../api/axios'
import { formatSize, getFileFormat, isImage, isPreviewable } from '../utils/fileTypes'
import { formatDateTime } from '../utils/dates'
import { isOriginalRestricted, isPiiUnknown } from '../utils/privacy'
import RestrictedOriginalNotice from './RestrictedOriginalNotice'

/**
 * Inline preview for PDF and images (GET /documents/{id}/view); other types get a metadata card. An original with
 * personal data that is closed to this viewer is never requested: a notice points to the masked text instead
 * ({@code onShowText} opens the "Текст" tab).
 */
export default function DocumentPreview({ doc, onShowText }) {
  return isOriginalRestricted(doc)
    ? <RestrictedOriginalNotice onShowText={onShowText} unchecked={isPiiUnknown(doc) && !doc.piiCount} />
    : <OriginalPreview doc={doc} />
}

function OriginalPreview({ doc }) {
  const format = getFileFormat(doc)
  const previewable = isPreviewable(doc)
  // Only fetched for previewable types; otherwise the metadata card is shown straight away.
  const [preview, setPreview] = useState({ loading: previewable, url: null, error: '' })

  // Focus inside the embedded viewer matches no CSS :focus in this page; the window's blur tells us it went there.
  const frameRef = useRef(null)
  const [frameFocused, setFrameFocused] = useState(false)
  useEffect(() => {
    const onBlur = () => setTimeout(() => setFrameFocused(Boolean(frameRef.current) && document.activeElement === frameRef.current))
    const onFocus = () => setFrameFocused(false)
    window.addEventListener('blur', onBlur)
    window.addEventListener('focus', onFocus)
    return () => {
      window.removeEventListener('blur', onBlur)
      window.removeEventListener('focus', onFocus)
    }
  }, [])

  useEffect(() => {
    if (!previewable) return undefined
    let objectUrl = null
    let cancelled = false

    api.get(`/documents/${doc.id}/view`, { responseType: 'blob', timeout: 0 })
      .then(({ data }) => {
        if (cancelled) return
        objectUrl = URL.createObjectURL(data)
        setPreview({ loading: false, url: objectUrl, error: '' })
      })
      .catch(async (err) => {
        const message = await getBlobErrorMessage(err)
        if (!cancelled) setPreview({ loading: false, url: null, error: message })
      })

    return () => {
      cancelled = true
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [doc.id, previewable])

  return (
    <div className="overflow-hidden rounded-card border border-line bg-page-alt">
      {preview.loading ? (
        <div className="flex h-72 items-center justify-center gap-2 text-sm text-fg-muted">
          <Loader2 className="h-4 w-4 animate-spin" /> Загрузка предпросмотра…
        </div>
      ) : preview.url && isImage(doc) ? (
        <div className="flex max-h-[70vh] items-center justify-center p-4">
          <img src={preview.url} alt={doc.originalFileName} className="max-h-[66vh] max-w-full rounded-control object-contain shadow-soft" />
        </div>
      ) : preview.url ? (
        <div className={frameFocused ? 'outline-2 -outline-offset-2 outline-primary' : ''}>
          <iframe ref={frameRef} src={preview.url} title={doc.originalFileName} className="block h-[70vh] w-full bg-surface focus:outline-none" />
        </div>
      ) : (
        <MetadataCard doc={doc} format={format} error={preview.error} />
      )}
    </div>
  )
}

function MetadataCard({ doc, format, error }) {
  const FormatIcon = format.icon
  return (
    <div className="p-6">
      <div className="flex flex-col items-center text-center">
        <span className={`flex h-14 w-14 items-center justify-center rounded-card ${format.tile}`}>
          <FormatIcon className="h-7 w-7" />
        </span>
        <p className="mt-3 text-sm font-medium text-fg">
          {error || 'Предпросмотр доступен для PDF и изображений'}
        </p>
        <p className="mt-1 text-xs text-fg-muted">
          Скачайте файл или задайте вопрос по его тексту во вкладке «Чат с ИИ».
        </p>
      </div>
      <dl className="mx-auto mt-6 grid max-w-2xl gap-3 sm:grid-cols-3">
        <MetaItem icon={HardDrive} label="Размер" value={formatSize(doc.fileSize)} />
        <MetaItem icon={CalendarClock} label="Дата загрузки" value={formatDateTime(doc.uploadedAt)} />
        <MetaItem icon={User} label="Владелец" value={doc.ownerFullName || doc.ownerEmail} title={doc.ownerEmail} />
      </dl>
    </div>
  )
}

function MetaItem({ icon: Icon, label, value, title }) {
  return (
    <div className="rounded-xl border border-line bg-surface p-3">
      <dt className="flex items-center gap-1.5 text-xs font-medium uppercase tracking-wide text-fg-muted">
        <Icon className="h-3.5 w-3.5" />
        {label}
      </dt>
      <dd className="mt-1 truncate text-sm font-medium tabular-nums text-fg" title={title ?? value}>{value}</dd>
    </div>
  )
}
