import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { AlertTriangle, Archive, Bot, CalendarClock, CheckCircle2, FileText, Loader2, Sparkles, UploadCloud, X, XCircle } from 'lucide-react'
import api, { getErrorMessage } from '../api/axios'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { ROLE_STUDENT } from '../constants/roles'
import { AUTO_ANALYZED_CATEGORIES, CATEGORY_OPTIONS, DOCUMENT_EXTENSIONS, TEACHER_ONLY_CATEGORY, VISIBILITY_OPTIONS } from '../constants/documents'
import { formatSize } from '../utils/fileTypes'
import useDocumentStatus, { initialPhase } from '../hooks/useDocumentStatus'
import CategoryBadge from './CategoryBadge'
import SearchSelect from './SearchSelect'
import { countAssignments } from '../utils/plural'

// Must match DocumentService.MAX_DOCUMENT_BYTES and spring.servlet.multipart on the backend.
const MAX_FILE_SIZE = 20 * 1024 * 1024
const MAX_ARCHIVE_SIZE = 50 * 1024 * 1024
const MAX_REQUEST_SIZE = 90 * 1024 * 1024 // under max-request-size (100 MB), leaving room for the form fields
const MAX_FILES_PER_REQUEST = 50
const AUTO = '' // category value: let the AI classify each file

const DOCUMENT_ACCEPT = DOCUMENT_EXTENSIONS.map((ext) => `.${ext}`).join(',')

const selectClass = 'w-full rounded-control border border-line-strong bg-surface px-3 py-2 text-sm text-fg outline-none transition focus:border-primary focus:ring-4 focus:ring-ring disabled:opacity-60'

const extensionOf = (name) => name.split('.').pop().toLowerCase()
const isZip = (file) => extensionOf(file.name) === 'zip'

// Groups files so that one request stays under the backend's request size and file count limits.
function intoRequests(files) {
  const groups = []
  let current = []
  let size = 0
  for (const file of files) {
    if (current.length && (size + file.size > MAX_REQUEST_SIZE || current.length === MAX_FILES_PER_REQUEST)) {
      groups.push(current)
      current = []
      size = 0
    }
    current.push(file)
    size += file.size
  }
  if (current.length) groups.push(current)
  return groups
}

/**
 * Multi-file drag & drop upload into a course (US-04), with ZIP import (US-06) and AI type classification (US-05).
 * For the course teacher or ADMIN only: callers do not render it for students, and the ZIP option is hidden for
 * them as well. Every document belongs to a course: with {@code course} it is fixed (course page), otherwise one of
 * the user's courses must be picked.
 */
export default function DocumentUpload({ course, onUploaded }) {
  const { role } = useAuth()
  const { notifyError, showError, showSuccess } = useToast()
  const canImportZip = role !== ROLE_STUDENT
  const inputRef = useRef(null)
  const zipInputRef = useRef(null)

  const [category, setCategory] = useState(AUTO)
  const [visibility, setVisibility] = useState('COURSE_STUDENTS')
  // On a course page the course is fixed; elsewhere it is picked from a server-side search of the user's courses.
  const [courseOption, setCourseOption] = useState(null)
  const courseId = course ? String(course.id) : courseOption?.value ?? ''
  const fetchCourses = useCallback((term) => api.get('/courses', { params: { size: 20, ...(term ? { q: term } : {}) } })
    .then(({ data }) => data.content.map((item) => ({ value: String(item.id), label: item.code ? `${item.code} · ${item.title}` : item.title }))), [])
  // While sending: { label, progress, phase: 'upload' | 'processing' }.
  const [upload, setUpload] = useState(null)
  const [result, setResult] = useState(null) // { uploaded: [...], skipped: [...], archives: [...] } of the last upload
  const [dragActive, setDragActive] = useState(false)

  // Background AI work on the files of the last upload: their type, then (syllabi, assignments) their deadlines.
  const uploadedDocuments = useMemo(() => result?.uploaded.map(({ document }) => document) ?? [], [result])
  const statuses = useDocumentStatus(uploadedDocuments)
  const viewOf = (document) => statuses[document.id] ?? { ...document, phase: initialPhase(document) }
  const deadlineNotices = uploadedDocuments
    .map((document) => ({ document, view: viewOf(document) }))
    .filter(({ view }) => view.watchedDeadlines && view.phase === 'finished' && view.assignmentsCount > 0)

  // Reloads the course's lists once the AI has typed all files, and again for every file it created deadlines from.
  const reloadedRef = useRef({ documents: null, classified: false, deadlines: new Set() })
  const allClassified = uploadedDocuments.every((document) => viewOf(document).phase !== 'classifying')
  const noticeIds = deadlineNotices.map(({ document }) => document.id).join(',')
  useEffect(() => {
    if (reloadedRef.current.documents !== uploadedDocuments) {
      reloadedRef.current = { documents: uploadedDocuments, classified: false, deadlines: new Set() }
    }
    const reloaded = reloadedRef.current
    let reload = false
    const hadAi = uploadedDocuments.some((document) => initialPhase(document) === 'classifying')
    if (hadAi && allClassified && !reloaded.classified) {
      reloaded.classified = true
      reload = true
    }
    for (const id of noticeIds ? noticeIds.split(',') : []) {
      if (!reloaded.deadlines.has(id)) {
        reloaded.deadlines.add(id)
        reload = true
      }
    }
    if (reload) onUploaded?.(uploadedDocuments)
  }, [uploadedDocuments, allClassified, noticeIds, onUploaded])

  const send = async (url, formData, label) => {
    setUpload({ label, progress: 0, phase: 'upload' })
    const { data } = await api.post(url, formData, {
      // Lets axios send FormData as-is; the browser adds the multipart boundary itself.
      headers: { 'Content-Type': 'multipart/form-data' },
      timeout: 0,
      onUploadProgress: ({ loaded, total }) => {
        if (!total) return
        const progress = Math.round((loaded / total) * 100)
        // Once the bytes are sent, the server extracts text and asks the AI for each file's type.
        setUpload({ label, progress, phase: progress >= 100 ? 'processing' : 'upload' })
      },
    })
    return data
  }

  const formFor = () => {
    const formData = new FormData()
    if (category !== AUTO) formData.append('category', category)
    formData.append('visibility', visibility)
    formData.append('courseId', courseId)
    return formData
  }

  const uploadFiles = async (fileList) => {
    const all = Array.from(fileList ?? [])
    if (all.length === 0 || upload) return
    if (!courseId) {
      showError('Выберите курс: каждый документ принадлежит курсу')
      return
    }

    const skipped = []
    const archives = []
    const documents = []
    for (const file of all) {
      if (isZip(file)) {
        if (!canImportZip) skipped.push({ fileName: file.name, reason: 'Загружать ZIP-архивы может только преподаватель курса' })
        else if (file.size > MAX_ARCHIVE_SIZE) skipped.push({ fileName: file.name, reason: `Архив больше ${formatSize(MAX_ARCHIVE_SIZE)}` })
        else archives.push(file)
      } else if (!DOCUMENT_EXTENSIONS.includes(extensionOf(file.name))) {
        skipped.push({ fileName: file.name, reason: 'Неподдерживаемый формат файла' })
      } else if (file.size > MAX_FILE_SIZE) {
        skipped.push({ fileName: file.name, reason: `Файл больше ${formatSize(MAX_FILE_SIZE)}` })
      } else {
        documents.push(file)
      }
    }

    const uploaded = []
    const archiveSummaries = [] // { name, imported, total } per ZIP
    let current = 'Загрузка' // label of the request in flight, for the error line
    setResult(null)
    try {
      for (const group of intoRequests(documents)) {
        const formData = formFor()
        group.forEach((file) => formData.append('files', file))
        current = group.length === 1 ? `«${group[0].name}»` : `${group.length} файлов`
        const data = await send('/documents/upload-multiple', formData, current)
        uploaded.push(...data.uploaded)
        skipped.push(...data.skipped)
      }
      for (const archive of archives) {
        const formData = formFor()
        formData.append('file', archive)
        current = `архива «${archive.name}»`
        const data = await send('/documents/upload-batch', formData, current)
        // The backend's BatchImport counts the same way: totalFiles = imported + skipped entries.
        archiveSummaries.push({ name: archive.name, imported: data.uploaded.length, total: data.uploaded.length + data.skipped.length })
        uploaded.push(...data.uploaded)
        skipped.push(...data.skipped.map((item) => ({ ...item, fileName: `${archive.name} → ${item.fileName}` })))
      }
    } catch (err) {
      // What was sent before the failure stays uploaded; the failed request is shown with its reason.
      notifyError(err)
      skipped.push({ fileName: current, reason: getErrorMessage(err) })
    } finally {
      setUpload(null)
    }

    setResult({ uploaded, skipped, archives: archiveSummaries })
    if (uploaded.length > 0) {
      const classifying = uploaded.some(({ document }) => initialPhase(document) === 'classifying')
      showSuccess(`Загружено документов: ${uploaded.length}.${classifying ? ' ИИ определяет тип файлов — следите за статусом ниже.' : ''}`)
      onUploaded?.(uploaded.map(({ document }) => document))
    }
  }

  const busy = Boolean(upload)

  return (
    <section className="rounded-card border border-line bg-surface p-4 shadow-card sm:p-5">
      <div className="grid grid-cols-1 gap-3 sm:grid-cols-3 [&>*]:min-w-0">
        <Field label="Тип документа" htmlFor="upload-category">
          <select id="upload-category" value={category} onChange={(event) => setCategory(event.target.value)} className={selectClass} disabled={busy}>
            <option value={AUTO}>Определить автоматически (ИИ)</option>
            {CATEGORY_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </Field>
        <Field label="Кто видит" htmlFor="upload-visibility">
          <select id="upload-visibility" value={visibility} onChange={(event) => setVisibility(event.target.value)} className={selectClass} disabled={busy}>
            {VISIBILITY_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
          </select>
        </Field>
        <Field label="Курс" htmlFor="upload-course">
          {course ? (
            <input id="upload-course" value={course.code ? `${course.code} · ${course.title}` : course.title} disabled className={selectClass} />
          ) : (
            <SearchSelect
              id="upload-course"
              value={courseOption}
              onChange={setCourseOption}
              fetchOptions={fetchCourses}
              placeholder="Выберите курс"
              searchPlaceholder="Название или код курса"
              emptyText="Курсы не найдены"
              invalid={!courseId}
              disabled={busy}
            />
          )}
        </Field>
      </div>
      <p className="mt-2 flex items-center gap-1.5 text-xs text-primary-fg">
        <Sparkles className="h-3.5 w-3.5 shrink-0" />
        {category === AUTO
          ? 'ИИ определит тип каждого файла (силлабус, задание, лекция, ответы к экзамену, положение…) и извлечёт дедлайны из силлабусов и заданий. Файлы, распознанные как ответы к экзамену, увидит только преподаватель.'
          : category === TEACHER_ONLY_CATEGORY
            ? 'Ответы к экзамену видны только преподавателю курса — независимо от выбранной видимости. ИИ не создаёт из них задания.'
          : AUTO_ANALYZED_CATEGORIES.has(category)
            ? 'Силлабусы и задания ИИ анализирует автоматически: краткая суть, темы и дедлайны.'
            : 'Тип задан вручную — ИИ-классификация не используется.'}
      </p>

      <div
        role="button"
        tabIndex={0}
        aria-disabled={busy}
        onClick={() => !busy && inputRef.current?.click()}
        onKeyDown={(event) => {
          if ((event.key === 'Enter' || event.key === ' ') && !busy) {
            event.preventDefault()
            inputRef.current?.click()
          }
        }}
        onDragOver={(event) => {
          event.preventDefault()
          if (!busy) setDragActive(true)
        }}
        onDragLeave={() => setDragActive(false)}
        onDrop={(event) => {
          event.preventDefault()
          setDragActive(false)
          uploadFiles(event.dataTransfer.files)
        }}
        className={`mt-4 flex cursor-pointer flex-col items-center justify-center rounded-card border-2 border-dashed p-7 text-center transition-all duration-200 focus:outline-none focus:ring-4 focus:ring-ring ${
          busy
            ? 'cursor-wait border-primary bg-primary-soft'
            : dragActive
              ? 'cursor-copy border-primary bg-primary-soft'
              : 'border-line-strong bg-page hover:border-primary hover:bg-primary-soft'
        }`}
      >
        <input
          ref={inputRef}
          type="file"
          multiple
          accept={canImportZip ? `${DOCUMENT_ACCEPT},.zip` : DOCUMENT_ACCEPT}
          className="hidden"
          onChange={(event) => {
            uploadFiles(event.target.files)
            event.target.value = ''
          }}
        />
        {upload ? <UploadProgress upload={upload} classifying={category === AUTO} /> : (
          <>
            <div className="flex items-center gap-2 text-primary-fg">
              <FileText className="h-6 w-6" />
              <UploadCloud className={`h-10 w-10 ${dragActive ? 'text-primary-fg' : 'text-primary-fg'}`} />
              {canImportZip && <Archive className="h-6 w-6" />}
            </div>
            <p className="mt-3 text-sm font-medium text-fg">
              Перетащите сюда несколько файлов{canImportZip ? ' или ZIP-архив' : ''}, или{' '}
              <span className="text-primary-fg underline-offset-2 hover:underline">выберите файлы</span>
            </p>
            <p className="mt-1 text-xs text-fg-muted">
              PDF, DOCX, PPTX, TXT, PNG, JPG — до {formatSize(MAX_FILE_SIZE)} на файл{canImportZip ? `; ZIP — до ${formatSize(MAX_ARCHIVE_SIZE)}` : ''}
            </p>
          </>
        )}
      </div>

      {canImportZip && (
        <div className="mt-3 flex justify-end">
          <input
            ref={zipInputRef}
            type="file"
            accept=".zip,application/zip"
            className="hidden"
            onChange={(event) => {
              uploadFiles(event.target.files)
              event.target.value = ''
            }}
          />
          <button
            type="button"
            onClick={() => zipInputRef.current?.click()}
            disabled={busy}
            className="flex items-center gap-2 rounded-control border border-line-strong px-3 py-1.5 text-xs font-medium text-fg transition hover:bg-page-alt disabled:opacity-60"
          >
            <Archive className="h-4 w-4" /> Загрузить ZIP-архив
          </button>
        </div>
      )}

      {result && (
        <UploadResult
          result={result}
          viewOf={viewOf}
          deadlineNotices={deadlineNotices}
          assignmentsLink={(document, anchor = 'assignments') => (course ? `#${anchor}` : `/courses/${document.courseId}#${anchor}`)}
          onClose={() => setResult(null)}
        />
      )}
    </section>
  )
}

function UploadProgress({ upload, classifying }) {
  const processing = upload.phase === 'processing'
  return (
    <div className="w-full max-w-sm">
      {processing && classifying
        ? <Bot className="mx-auto h-8 w-8 animate-pulse text-primary" aria-hidden="true" />
        : <Loader2 className="mx-auto h-8 w-8 animate-spin text-primary" aria-hidden="true" />}
      <p className="mt-3 truncate text-sm font-medium text-fg">
        {processing
          ? (classifying ? `ИИ определяет тип ${upload.label}…` : `Распознавание текста ${upload.label}…`)
          : `Загрузка ${upload.label}…`}
      </p>
      <div className="mt-3 h-2 overflow-hidden rounded-full bg-page-alt">
        <div
          className={`h-full rounded-full transition-all duration-200 ${processing ? 'animate-pulse bg-primary' : 'bg-primary'}`}
          style={{ width: `${upload.progress}%` }}
        />
      </div>
      <p className="mt-1 flex items-center justify-center gap-1.5 text-xs tabular-nums text-fg-muted">
        {processing ? <><Loader2 className="h-3 w-3 animate-spin" /> Обработка на сервере</> : `${upload.progress}%`}
      </p>
    </div>
  )
}

/**
 * Why students do not see an uploaded file (AccessControlService.isHiddenFromStudents): the AI was not configured at
 * upload, it could not type the file, or the scan's text was not recognized. Null when they see it (or it is
 * TEACHER_ONLY anyway) or the background work has not finished.
 */
function hiddenReason(document, view) {
  if (document.visibility === 'TEACHER_ONLY' || view.aiStatus === 'MANUAL') return null
  if (document.aiStatus === 'FAILED') return 'ИИ недоступен — укажите тип, чтобы студенты увидели файл'
  if (view.phase === 'classifying') return null
  if (view.aiStatus === 'FAILED') return 'ИИ не определил тип — укажите тип, чтобы студенты увидели файл'
  if (view.ocrStatus === 'FAILED') return 'Скан не распознан — укажите тип, чтобы студенты увидели файл'
  return null
}

function UploadResult({ result, viewOf, deadlineNotices, assignmentsLink, onClose }) {
  const hidden = result.uploaded
    .map(({ document }) => ({ document, reason: hiddenReason(document, viewOf(document)) }))
    .filter(({ reason }) => reason)
  return (
    <div className="mt-4 rounded-xl border border-line bg-page-alt p-3">
      <div className="flex items-center justify-between gap-2">
        <p className="text-xs font-semibold text-fg">
          Загружено: {result.uploaded.length}{result.skipped.length > 0 && ` · пропущено: ${result.skipped.length}`}
        </p>
        <button type="button" onClick={onClose} className="rounded p-1 text-fg-muted hover:text-fg-secondary" aria-label="Скрыть результат">
          <X className="h-4 w-4" />
        </button>
      </div>
      {result.archives.map((archive) => (
        <p key={archive.name} className="mt-1 flex items-center gap-1.5 text-xs text-fg-secondary">
          <Archive className="h-3.5 w-3.5 shrink-0 text-fg-muted" />
          Импортировано {archive.imported} из {archive.total} файлов — «{archive.name}»
        </p>
      ))}
      {deadlineNotices.length > 0 && (
        <ul className="mt-2 space-y-1" aria-live="polite">
          {deadlineNotices.map(({ document, view }) => (
            <li key={document.id} className="flex flex-wrap items-center gap-1.5 rounded-control bg-success-soft px-2 py-1.5 text-xs text-success-fg">
              <CalendarClock className="h-3.5 w-3.5 shrink-0" />
              {view.draftsCount > 0 ? (
                <>
                  <span className="min-w-0">
                    ИИ нашёл {countAssignments(view.draftsCount)} — проверьте и опубликуйте («{document.originalFileName}»)
                  </span>
                  <AssignmentsLink to={assignmentsLink(document, 'drafts')} label="К черновикам" />
                </>
              ) : (
                <>
                  <span className="min-w-0">ИИ создал дедлайнов: {view.assignmentsCount} — «{document.originalFileName}»</span>
                  <AssignmentsLink to={assignmentsLink(document)} />
                </>
              )}
            </li>
          ))}
        </ul>
      )}
      {hidden.length > 0 && (
        <ul className="mt-2 space-y-1" aria-live="polite">
          {hidden.map(({ document, reason }) => (
            <li key={document.id} className="flex flex-wrap items-center gap-1.5 rounded-control bg-warning-soft px-2 py-1.5 text-xs text-warning-fg">
              <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              <span className="min-w-0">{reason} («{document.originalFileName}»)</span>
              <Link to={`/documents/${document.id}`} className="font-semibold underline-offset-2 hover:underline">Указать тип</Link>
            </li>
          ))}
        </ul>
      )}
      <ul className="mt-2 max-h-56 space-y-1 overflow-y-auto" aria-live="polite">
        {result.uploaded.map(({ document }) => (
          <li key={document.id} className="flex items-center gap-2 text-xs">
            <CheckCircle2 className="h-3.5 w-3.5 shrink-0 text-success" />
            <span className="min-w-0 flex-1 truncate text-fg" title={document.originalFileName}>{document.originalFileName}</span>
            <DocumentStatus view={viewOf(document)} />
          </li>
        ))}
        {result.skipped.map((item, index) => (
          <li key={`skipped-${index}`} className="flex items-start gap-2 text-xs">
            <XCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-danger" />
            <span className="min-w-0 flex-1 truncate text-fg-muted" title={item.fileName}>{item.fileName}</span>
            <span className="shrink-0 text-danger-fg">{item.reason}</span>
          </li>
        ))}
      </ul>
    </div>
  )
}

const FINAL_STATUSES = new Set(['DONE', 'FAILED', 'MANUAL'])

/** "Загружено" -> "ИИ определяет тип…" -> the category (with the AI's confidence), "Тип не определён" if it failed. */
function DocumentStatus({ view }) {
  const muted = 'inline-flex shrink-0 items-center gap-1 text-[11px] font-medium'
  if (view.timedOut && !FINAL_STATUSES.has(view.aiStatus)) {
    // Polling gave up after 3 minutes; the server keeps working and the list shows the result later.
    return <span className={`${muted} text-fg-muted`}>Обработка продолжается в фоне</span>
  }
  if (view.phase === 'classifying') {
    if (view.ocrStatus === 'PENDING' || view.ocrStatus === 'RUNNING') {
      return (
        <span className={`${muted} text-accent-fg`}>
          <Loader2 className="h-3 w-3 animate-spin" /> Распознаём скан…
        </span>
      )
    }
    if (view.aiStatus === 'PROCESSING') {
      return (
        <span className={`${muted} text-primary-fg`}>
          <Loader2 className="h-3 w-3 animate-spin" /> ИИ определяет тип…
        </span>
      )
    }
    return <span className={`${muted} text-fg-muted`}>Загружено</span>
  }
  if (view.aiStatus === 'FAILED') {
    return (
      <span className={`${muted} rounded-md bg-warning-soft px-1.5 py-0.5 text-warning-fg ring-1 ring-inset ring-warning/30`}>
        Тип не определён
      </span>
    )
  }
  return (
    <span className="inline-flex shrink-0 items-center gap-1.5">
      {view.phase === 'deadlines' && (
        <span className={`${muted} text-fg-muted`} title="ИИ ищет задания и дедлайны">
          <Loader2 className="h-3 w-3 animate-spin" /> дедлайны…
        </span>
      )}
      <CategoryBadge category={view.category} confidence={view.aiStatus === 'DONE' ? view.aiConfidence : null} ai={view.aiStatus === 'DONE'} />
    </span>
  )
}

/** In-page anchor on the course page, a route link elsewhere (the vault). */
function AssignmentsLink({ to, label = 'К заданиям курса' }) {
  const className = 'font-semibold underline-offset-2 hover:underline'
  return to.startsWith('#')
    ? <a href={to} className={className}>{label}</a>
    : <Link to={to} className={className}>{label}</Link>
}

function Field({ label, htmlFor, children }) {
  return (
    <div>
      <label htmlFor={htmlFor} className="mb-1 block text-xs font-medium text-fg-secondary">{label}</label>
      {children}
    </div>
  )
}
