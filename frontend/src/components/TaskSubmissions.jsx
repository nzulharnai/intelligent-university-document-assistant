import { useId, useRef, useState } from 'react'
import { ChevronDown, Download, History, Loader2, Paperclip, UploadCloud } from 'lucide-react'
import api, { getBlobErrorMessage } from '../api/axios'
import { useToast } from '../context/ToastContext'
import useConfirm from '../hooks/useConfirm'
import { DOCUMENT_EXTENSIONS } from '../constants/documents'
import { formatSize } from '../utils/fileTypes'
import { formatDateTime } from '../utils/dates'
import { downloadSubmission } from '../utils/submissions'
import { isPastDeadline } from '../utils/tasks'

// Must match SubmissionService.MAX_FILES_PER_SUBMISSION and DocumentService.MAX_DOCUMENT_BYTES.
const MAX_FILES = 10
const MAX_FILE_SIZE = 20 * 1024 * 1024
const ACCEPT = DOCUMENT_EXTENSIONS.map((ext) => `.${ext}`).join(',')

const extensionOf = (name) => name.split('.').pop().toLowerCase()

// Local date-times of the university, as the backend sends them; both sides of a comparison come from the server.
const isAfter = (a, b) => Boolean(a && b) && new Date(a).getTime() > new Date(b).getTime()

/**
 * Hand-ins grouped per student task and version, current version first: [{ taskId, studentFullName, studentEmail,
 * versions: [{ version, submittedAt, current, files }] }]. The rows come sorted by student, then version (desc).
 */
function groupByVersion(items) {
  const tasks = []
  for (const item of items) {
    let task = tasks.find((entry) => entry.taskId === item.taskId)
    if (!task) {
      task = { taskId: item.taskId, studentFullName: item.studentFullName, studentEmail: item.studentEmail, versions: [] }
      tasks.push(task)
    }
    let version = task.versions.find((entry) => entry.version === item.version)
    if (!version) {
      version = { version: item.version, submittedAt: item.submittedAt, current: item.current, files: [] }
      task.versions.push(version)
    }
    version.files.push(item)
  }
  return tasks
}

const filesLabel = (count) => (count === 1 ? '1 файл' : count < 5 ? `${count} файла` : `${count} файлов`)

/**
 * Submissions of a task (Moodle-style). With {@code task} it is the student's own task: files are dropped or picked
 * here and handed in with POST /tasks/{id}/submissions - the only upload a student can make. Each hand-in is a new
 * version; the current one is listed first, earlier ones stay collapsed as history. After the deadline the student
 * confirms a late hand-in, or sees why it is not possible (late work not accepted, assignment closed, already
 * graded); the server decides in the end. {@code onSubmitted(changes)} gets the task's new status fields. With
 * {@code assignmentId} it is the course teacher's read-only view of every student's submissions.
 */
export default function TaskSubmissions({ task, assignmentId, onSubmitted }) {
  const { notifyError, showError, showSuccess } = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const inputRef = useRef(null)
  const reasonId = useId()
  const [open, setOpen] = useState(false)
  const [items, setItems] = useState(null)
  const [sending, setSending] = useState(false)
  const [dragActive, setDragActive] = useState(false)
  const own = Boolean(task)
  // The teacher's view learns the number only when it is opened.
  const total = own ? task.submissionCount ?? 0 : items?.length ?? null
  const listUrl = own ? `/tasks/${task.id}/submissions` : `/assignments/${assignmentId}/submissions`

  // A hint from the browser's clock: the warning and the disabled button. The server checks again.
  const pastDeadline = own && isPastDeadline(task.deadline)
  const blockedReason = !own ? null
    : task.assignmentStatus === 'CLOSED' ? 'Задание закрыто — новые решения не принимаются'
      : task.status === 'GRADED' ? 'Работа оценена — сдать заново нельзя'
        : pastDeadline && task.allowLate === false
          ? `Срок сдачи истёк ${formatDateTime(task.deadline)} — работы после срока не принимаются`
          : null

  const load = async () => {
    try {
      const { data } = await api.get(listUrl)
      setItems(data)
    } catch (err) {
      notifyError(err)
    }
  }

  const toggle = () => {
    if (!open && items === null) load()
    setOpen(!open)
  }

  const submit = async (fileList) => {
    const files = Array.from(fileList ?? [])
    if (files.length === 0 || sending || blockedReason) return
    const problem = files.length > MAX_FILES ? `Можно приложить не больше ${MAX_FILES} файлов`
      : files.map((file) => (!DOCUMENT_EXTENSIONS.includes(extensionOf(file.name)) ? `«${file.name}»: неподдерживаемый формат`
        : file.size > MAX_FILE_SIZE ? `«${file.name}» больше ${formatSize(MAX_FILE_SIZE)}` : null)).find(Boolean)
    if (problem) {
      showError(problem)
      return
    }
    if (pastDeadline && !(await confirm({
      title: 'Срок прошёл',
      message: 'Срок прошёл — работа будет отмечена как сданная с опозданием.',
      confirmLabel: 'Сдать с опозданием',
      tone: 'warning',
    }))) return
    const formData = new FormData()
    files.forEach((file) => formData.append('files', file))
    setSending(true)
    try {
      const { data } = await api.post(listUrl, formData, { headers: { 'Content-Type': 'multipart/form-data' }, timeout: 0 })
      const submittedAt = data[0]?.submittedAt ?? null
      const late = isAfter(submittedAt, task.deadline)
      showSuccess(late ? 'Работа сдана с опозданием' : 'Работа сдана')
      onSubmitted?.({ status: 'SUBMITTED', submittedAt, late, submissionCount: (task.submissionCount ?? 0) + data.length })
      setOpen(true)
      await load()
    } catch (err) {
      notifyError(err)
    } finally {
      setSending(false)
    }
  }

  const download = async (item) => {
    try {
      await downloadSubmission(item)
    } catch (err) {
      showError(await getBlobErrorMessage(err))
    }
  }

  const fileRow = (item) => (
    <li key={item.id} className="flex items-center gap-2 text-[11px]">
      <span className="min-w-0 flex-1 truncate text-fg-secondary" title={item.originalFileName}>{item.originalFileName}</span>
      <span className="shrink-0 tabular-nums text-fg-muted">{formatSize(item.fileSize)}</span>
      <button type="button" onClick={() => download(item)} className="shrink-0 rounded p-1 text-fg-muted hover:text-primary-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary" aria-label={`Скачать ${item.originalFileName}`}>
        <Download className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </li>
  )

  const versionTitle = (version) => `Версия ${version.version} · ${formatDateTime(version.submittedAt)} · ${filesLabel(version.files.length)}`

  const history = (entry) => {
    const [current, ...earlier] = entry.versions
    return (
      <div key={entry.taskId} className="space-y-1">
        {!own && (
          <p className="truncate text-[11px] font-semibold text-fg" title={entry.studentEmail}>
            {entry.studentFullName || entry.studentEmail}
          </p>
        )}
        <p className="text-[11px] font-medium tabular-nums text-fg-secondary">
          {versionTitle(current)}{current.current && earlier.length > 0 && ' · текущая'}
        </p>
        <ul className="space-y-1">{current.files.map(fileRow)}</ul>
        {earlier.map((version) => (
          <details key={version.version} className="group rounded-md border border-line-subtle px-2 py-1">
            <summary className="flex cursor-pointer list-none items-center gap-1.5 rounded-sm text-[11px] text-fg-muted hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary [&::-webkit-details-marker]:hidden">
              <History className="h-3 w-3 shrink-0" aria-hidden="true" />
              <span className="min-w-0 flex-1 truncate tabular-nums">Прежняя {versionTitle(version).toLowerCase()}</span>
              <ChevronDown className="h-3 w-3 shrink-0 transition group-open:rotate-180" aria-hidden="true" />
            </summary>
            <ul className="mt-1 space-y-1">{version.files.map(fileRow)}</ul>
          </details>
        ))}
      </div>
    )
  }

  return (
    <div className="mt-2">
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={toggle}
          className="inline-flex items-center gap-1 rounded-sm text-[11px] font-medium text-fg-secondary hover:text-primary-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          aria-expanded={open}
        >
          <Paperclip className="h-3 w-3" aria-hidden="true" />
          {own ? 'Моё решение' : 'Решения студентов'}{total !== null && `: ${total}`}
          <ChevronDown className={`h-3 w-3 transition ${open ? 'rotate-180' : ''}`} aria-hidden="true" />
        </button>
        {own && (
          <>
            <input
              ref={inputRef}
              type="file"
              multiple
              accept={ACCEPT}
              className="hidden"
              onChange={(event) => {
                submit(event.target.files)
                event.target.value = ''
              }}
            />
            <button
              type="button"
              disabled={sending || Boolean(blockedReason)}
              aria-describedby={blockedReason ? reasonId : undefined}
              onClick={() => inputRef.current?.click()}
              onDragOver={(event) => {
                event.preventDefault()
                if (!sending && !blockedReason) setDragActive(true)
              }}
              onDragLeave={() => setDragActive(false)}
              onDrop={(event) => {
                event.preventDefault()
                setDragActive(false)
                submit(event.dataTransfer.files)
              }}
              className={`inline-flex items-center gap-1 rounded-md border border-dashed px-2 py-0.5 text-[11px] font-medium transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:cursor-not-allowed disabled:border-line-strong disabled:text-fg-muted ${
                dragActive
                  ? 'border-primary bg-primary-soft text-primary-fg'
                  : 'border-primary text-primary-fg hover:bg-primary-soft disabled:hover:bg-transparent'
              }`}
              title={blockedReason ?? 'Перетащите файлы сюда или нажмите, чтобы выбрать'}
            >
              {sending ? <Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" /> : <UploadCloud className="h-3 w-3" aria-hidden="true" />}
              {sending ? 'Отправка…' : total > 0 ? 'Сдать заново' : 'Сдать'}
            </button>
            {blockedReason && <span id={reasonId} className="text-[11px] text-fg-muted">{blockedReason}</span>}
          </>
        )}
      </div>

      {open && (
        items === null ? (
          <p role="status" className="mt-1.5 flex items-center gap-1.5 text-[11px] text-fg-muted"><Loader2 className="h-3 w-3 animate-spin" aria-hidden="true" /> Загрузка…</p>
        ) : items.length === 0 ? (
          <p className="mt-1.5 text-[11px] text-fg-muted">{own ? 'Вы ещё ничего не сдали.' : 'Пока никто не сдал решение.'}</p>
        ) : (
          <div className="mt-1.5 space-y-3">{groupByVersion(items).map(history)}</div>
        )
      )}
      {confirmDialog}
    </div>
  )
}
