import { useId, useState } from 'react'
import { Award, ChevronRight, Download, FileText, Undo2 } from 'lucide-react'
import api, { getBlobErrorMessage } from '../api/axios'
import { useToast } from '../context/ToastContext'
import useConfirm from '../hooks/useConfirm'
import { formatDateTime } from '../utils/dates'
import { formatSize } from '../utils/fileTypes'
import { downloadSubmission } from '../utils/submissions'
import TaskStateBadge from './TaskStateBadge'
import Button from './ui/Button'
import Dialog, { DialogActions } from './ui/Dialog'
import { controlClass, controlErrorClass, errorTextClass, hintClass, labelClass } from './ui/styles'

const MAX_FEEDBACK = 2000
// The backend's limit without a max score (GradingService.DEFAULT_MAX_SCORE).
const DEFAULT_MAX_SCORE = 1000

const scoreText = (score) => (score == null ? '' : String(Number(score)))

/** Empty when the score is fine, otherwise what is wrong (0..max, at most one decimal). */
function scoreProblem(value, max) {
  if (value.trim() === '') return 'Укажите оценку'
  const normalized = value.trim().replace(',', '.')
  if (!/^\d+(\.\d)?$/.test(normalized)) return 'Число с одним знаком после запятой, например 8.5'
  const score = Number(normalized)
  if (score < 0 || score > max) return `Оценка должна быть от 0 до ${max}`
  return ''
}

/**
 * Grades one student's task (PUT /tasks/{id}/grade): the files of their current hand-in, a score with the
 * assignment's max shown, and feedback. "Следующий студент" saves a changed grade and moves on, for fast grading;
 * "Снять оценку" (after a ConfirmDialog) returns the task to "Сдано" (or "Не начато" without a hand-in).
 * {@code onSaved(student)} gets the task's new row.
 */
export default function GradingDialog({ student, maxScore, onClose, onSaved, onNext }) {
  const formId = useId()
  const { notifyError, showError, showSuccess } = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const max = maxScore ?? DEFAULT_MAX_SCORE
  const initial = { score: scoreText(student.score), feedback: student.feedback ?? '' }
  const [form, setForm] = useState(initial)
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const problem = scoreProblem(form.score, max)
  const dirty = form.score !== initial.score || form.feedback !== initial.feedback
  const graded = student.status === 'GRADED'

  const save = async () => {
    setTouched(true)
    if (problem) return null
    setBusy(true)
    try {
      const { data } = await api.put(`/tasks/${student.taskId}/grade`, {
        score: Number(form.score.trim().replace(',', '.')),
        feedback: form.feedback.trim() || null,
      })
      showSuccess(graded ? 'Оценка изменена' : 'Оценка сохранена')
      onSaved(data)
      return data
    } catch (err) {
      notifyError(err)
      return null
    } finally {
      setBusy(false)
    }
  }

  const submit = async (event) => {
    event.preventDefault()
    if (await save()) onClose()
  }

  const next = async () => {
    if (dirty && !(await save())) return
    onNext()
  }

  const ungrade = async () => {
    if (!(await confirm({
      title: 'Снять оценку?',
      message: `Оценка и отзыв студенту «${student.studentFullName || student.studentEmail}» будут удалены, `
        + `работа вернётся в состояние «${student.submittedAt ? 'Сдано' : 'Не начато'}». Сданные файлы останутся.`,
      confirmLabel: 'Снять оценку',
    }))) return
    setBusy(true)
    try {
      const { data } = await api.delete(`/tasks/${student.taskId}/grade`)
      showSuccess('Оценка снята')
      onSaved(data)
      onClose()
    } catch (err) {
      notifyError(err)
    } finally {
      setBusy(false)
    }
  }

  const download = async (file) => {
    try {
      await downloadSubmission(file)
    } catch (err) {
      showError(await getBlobErrorMessage(err))
    }
  }

  return (
    <>
      <Dialog
        title={student.studentFullName || student.studentEmail}
        subtitle={student.studentFullName ? student.studentEmail : undefined}
        icon={Award}
        onClose={onClose}
        busy={busy}
        size="lg"
      >
        <div className="mt-4 flex flex-wrap items-center gap-2">
          <TaskStateBadge status={student.status} late={student.late} size="md" />
          {student.submittedAt && (
            <span className="text-small tabular-nums text-fg-muted">Сдано {formatDateTime(student.submittedAt)}</span>
          )}
        </div>

        <section className="mt-4" aria-labelledby={`${formId}-files`}>
          <h3 id={`${formId}-files`} className="text-sm font-semibold text-fg">Файлы текущей версии</h3>
          {student.files.length === 0 ? (
            <p className="mt-1.5 rounded-control bg-page-alt px-3 py-2 text-small text-fg-muted">
              Студент ничего не сдал. Можно поставить оценку и без работы, например 0.
            </p>
          ) : (
            <ul className="mt-1.5 divide-y divide-line-subtle rounded-control border border-line">
              {student.files.map((file) => (
                <li key={file.id} className="flex items-center gap-2 px-3 py-2 text-sm">
                  <FileText className="h-4 w-4 shrink-0 text-fg-muted" aria-hidden="true" />
                  <span className="min-w-0 flex-1 truncate text-fg" title={file.originalFileName}>{file.originalFileName}</span>
                  <span className="shrink-0 text-small tabular-nums text-fg-muted">{formatSize(file.fileSize)}</span>
                  <Button variant="ghost" size="sm" iconOnly icon={Download} onClick={() => download(file)} aria-label={`Скачать ${file.originalFileName}`} />
                </li>
              ))}
            </ul>
          )}
        </section>

        <form onSubmit={submit} className="mt-5 space-y-4" noValidate>
          <div>
            <label htmlFor={`${formId}-score`} className={labelClass}>Оценка</label>
            <div className="flex items-center gap-2">
              <input
                id={`${formId}-score`}
                inputMode="decimal"
                autoComplete="off"
                value={form.score}
                onChange={(event) => setForm((current) => ({ ...current, score: event.target.value }))}
                onBlur={() => setTouched(true)}
                aria-invalid={touched && Boolean(problem)}
                aria-describedby={`${formId}-score-note`}
                className={`${controlClass} w-28 tabular-nums ${touched && problem ? controlErrorClass : ''}`}
                autoFocus
              />
              <span className="text-sm tabular-nums text-fg-secondary">из {max}</span>
            </div>
            <p id={`${formId}-score-note`} className={touched && problem ? errorTextClass : hintClass}>
              {touched && problem ? problem : maxScore == null ? 'У задания нет максимального балла: от 0 до 1000.' : 'Можно с одним знаком после запятой.'}
            </p>
          </div>
          <div>
            <label htmlFor={`${formId}-feedback`} className={labelClass}>Отзыв</label>
            <textarea
              id={`${formId}-feedback`}
              value={form.feedback}
              onChange={(event) => setForm((current) => ({ ...current, feedback: event.target.value }))}
              rows={4}
              maxLength={MAX_FEEDBACK}
              placeholder="Что получилось, что исправить"
              aria-describedby={`${formId}-feedback-count`}
              className={`${controlClass} resize-y`}
            />
            <p id={`${formId}-feedback-count`} className={`${hintClass} text-right tabular-nums`}>{form.feedback.length} / {MAX_FEEDBACK}</p>
          </div>
          <DialogActions>
            {graded && (
              <Button variant="danger-ghost" icon={Undo2} onClick={ungrade} disabled={busy} className="sm:mr-auto">
                Снять оценку
              </Button>
            )}
            <Button variant="outline" onClick={onClose} disabled={busy}>Отмена</Button>
            {onNext && (
              <Button variant="secondary" onClick={next} disabled={busy} title={dirty ? 'Сохранить и открыть следующего студента' : 'Открыть следующего студента'}>
                Следующий студент <ChevronRight className="h-4 w-4" aria-hidden="true" />
              </Button>
            )}
            <Button type="submit" loading={busy}>Сохранить</Button>
          </DialogActions>
        </form>
      </Dialog>
      {confirmDialog}
    </>
  )
}
