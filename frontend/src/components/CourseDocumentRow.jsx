import { useId, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Check, ChevronRight, Sparkles } from 'lucide-react'
import api from '../api/axios'
import { useToast } from '../context/ToastContext'
import { VISIBILITY_LABELS } from '../constants/documents'
import { getFileFormat, canDeleteDocument } from '../utils/fileTypes'
import { formatDateTime } from '../utils/dates'
import DocumentAiBadge from './DocumentAiBadge'
import PiiBadge from './PiiBadge'
import OcrBadge from './OcrBadge'
import ReclassifyButton from './ReclassifyButton'
import Button from './ui/Button'

/**
 * One document of "Документы курса", in both views ("Списком" and "По неделям"). With {@code weeksCount} (US-16, the
 * course teacher or ADMIN) it also gets the week select and the AI's hint "ИИ предлагает: неделя N" with "Принять"
 * and "Другая". A student never gets those (nor the hint: the backend sends none).
 *
 * @param onUpdated     (changes) => the row's new fields, e.g. after "Переклассифицировать" or a week change
 * @param onWeekChanged (doc, week) => called after the week was saved, so the weeks view can update its counts
 */
export default function CourseDocumentRow({ doc, courseId, weeksCount, onUpdated, onWeekChanged }) {
  const format = getFileFormat(doc)
  const FormatIcon = format.icon
  const canManage = canDeleteDocument(doc)
  return (
    <li className="transition-colors hover:bg-page">
      <div className="flex items-center gap-2 pr-3">
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
        {canManage && <ReclassifyButton doc={doc} onUpdated={onUpdated} compact />}
      </div>
      {canManage && weeksCount > 0 && (
        <WeekControls doc={doc} weeksCount={weeksCount} onUpdated={onUpdated} onWeekChanged={onWeekChanged} />
      )}
    </li>
  )
}

function WeekControls({ doc, weeksCount, onUpdated, onWeekChanged }) {
  const { notifyError, showSuccess } = useToast()
  const selectId = useId()
  const selectRef = useRef(null)
  const [busy, setBusy] = useState(false)
  const hint = doc.weekNumber == null && doc.suggestedWeek != null ? doc.suggestedWeek : null

  const save = async (request, done) => {
    setBusy(true)
    try {
      const { data } = await request()
      onUpdated?.({ weekNumber: data.weekNumber, suggestedWeek: data.suggestedWeek })
      onWeekChanged?.(doc, data.weekNumber)
      if (done) showSuccess(done(data.weekNumber))
    } catch (err) {
      notifyError(err)
    } finally {
      setBusy(false)
    }
  }

  const pick = (value) => save(() => api.patch(`/documents/${doc.id}/week`, { week: value === '' ? null : Number(value) }))
  const accept = () => save(() => api.post(`/documents/${doc.id}/week/accept`),
    (week) => `«${doc.originalFileName}» — неделя ${week}`)

  // "Другая": the select is the other choice; focus it (and open it where the browser allows).
  const chooseOther = () => {
    const select = selectRef.current
    if (!select) return
    select.focus()
    try {
      select.showPicker?.()
    } catch {
      // Not allowed here (e.g. no user activation): the focused select is enough.
    }
  }

  return (
    <div className="flex flex-wrap items-center gap-2 pb-3 pl-4 pr-3 sm:pl-[4.25rem]">
      <label htmlFor={selectId} className="text-xs text-fg-muted">Неделя</label>
      <select
        id={selectId}
        ref={selectRef}
        value={doc.weekNumber ?? ''}
        disabled={busy}
        onChange={(event) => pick(event.target.value)}
        aria-label={`Неделя документа ${doc.originalFileName}`}
        className="h-8 rounded-control border border-line-strong bg-surface px-2 text-xs text-fg outline-none transition focus:border-primary focus:ring-4 focus:ring-ring disabled:opacity-60"
      >
        <option value="">Без недели</option>
        {Array.from({ length: weeksCount }, (_, index) => index + 1).map((week) => (
          <option key={week} value={week}>Неделя {week}</option>
        ))}
      </select>
      {hint != null && (
        <span className="inline-flex flex-wrap items-center gap-1.5">
          <span className="inline-flex items-center gap-1 rounded-full bg-accent-soft px-2 py-0.5 text-xs font-medium text-accent-fg">
            <Sparkles className="h-3 w-3" aria-hidden="true" /> ИИ предлагает: неделя {hint}
          </span>
          <Button size="sm" variant="secondary" icon={Check} onClick={accept} loading={busy}>Принять</Button>
          <Button size="sm" variant="ghost" onClick={chooseOther} disabled={busy}>Другая</Button>
        </span>
      )}
    </div>
  )
}
