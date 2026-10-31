import { useId, useState } from 'react'
import { Info, Pencil } from 'lucide-react'
import api from '../api/axios'
import { useToast } from '../context/ToastContext'
import useConfirm from '../hooks/useConfirm'
import Button from './ui/Button'
import Dialog, { DialogActions } from './ui/Dialog'
import { checkboxClass, controlClass as inputClass, hintClass, labelClass } from './ui/styles'

// The backend keeps deadlines as wall-clock time of the university (no zone), like the datetime-local input.
const toInputValue = (deadline) => (deadline ? deadline.slice(0, 16) : '')

/**
 * Edits an assignment's title, requirements, deadline, whether late work is accepted and the highest grade
 * (PATCH /assignments/{id}), e.g. to fix what the AI got wrong. A published assignment's changes reach the students'
 * tasks at once; their progress and submissions stay.
 */
export default function AssignmentEditDialog({ assignment, onClose, onSaved }) {
  const titleId = useId()
  const { notifyError, showSuccess } = useToast()
  const [confirm, confirmDialog] = useConfirm()
  const [form, setForm] = useState({
    title: assignment.title ?? '',
    description: assignment.description ?? '',
    deadline: toInputValue(assignment.deadline),
    allowLate: assignment.allowLate ?? true,
    maxScore: assignment.maxScore == null ? '' : String(assignment.maxScore),
  })
  const [saving, setSaving] = useState(false)

  const published = assignment.status !== 'DRAFT'
  const update = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }))

  const save = async (event) => {
    event.preventDefault()
    const deadlineChanged = form.deadline !== toInputValue(assignment.deadline)
    if (deadlineChanged && form.deadline && new Date(form.deadline) < new Date()) {
      const confirmed = await confirm({
        title: 'Дедлайн уже прошёл',
        message: 'Выбранные дата и время уже в прошлом: студенты увидят задание просроченным. Сохранить всё равно?',
        confirmLabel: 'Сохранить',
        tone: 'primary',
      })
      if (!confirmed) return
    }
    setSaving(true)
    try {
      const { data } = await api.patch(`/assignments/${assignment.id}`, {
        title: form.title.trim(),
        description: form.description.trim() || null,
        deadline: form.deadline ? `${form.deadline}:00` : null,
        allowLate: form.allowLate,
        maxScore: form.maxScore === '' ? null : Number(form.maxScore),
      })
      showSuccess(published ? 'Задание сохранено, студенты видят изменения' : 'Черновик сохранён')
      onSaved?.(data)
      onClose()
    } catch (err) {
      notifyError(err)
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
    <Dialog title={published ? 'Изменить задание' : 'Изменить черновик'} icon={Pencil} onClose={onClose} busy={saving} size="lg">
        <form onSubmit={save} className="mt-5 space-y-4">
          <div>
            <label htmlFor={`${titleId}-title`} className={labelClass}>Название</label>
            <input id={`${titleId}-title`} value={form.title} onChange={update('title')} required maxLength={255} className={inputClass} />
          </div>
          <div>
            <label htmlFor={`${titleId}-req`} className={labelClass}>Требования</label>
            <textarea id={`${titleId}-req`} value={form.description} onChange={update('description')} rows={4} maxLength={2000} className={`${inputClass} resize-y`} />
          </div>
          <div>
            <label htmlFor={`${titleId}-deadline`} className={labelClass}>Дедлайн</label>
            <div className="flex gap-2">
              <input id={`${titleId}-deadline`} type="datetime-local" value={form.deadline} onChange={update('deadline')} className={inputClass} />
              {form.deadline && (
                <Button variant="outline" onClick={() => setForm((current) => ({ ...current, deadline: '' }))} className="h-auto">
                  Без срока
                </Button>
              )}
            </div>
            <p className={hintClass}>Время указывается по часовому поясу вуза.</p>
          </div>
          <div className="grid gap-4 sm:grid-cols-2">
            <div>
              <label htmlFor={`${titleId}-score`} className={labelClass}>Максимальный балл</label>
              <input
                id={`${titleId}-score`}
                type="number"
                inputMode="numeric"
                min={1}
                max={1000}
                step={1}
                value={form.maxScore}
                onChange={update('maxScore')}
                placeholder="Без баллов"
                aria-describedby={`${titleId}-score-hint`}
                className={`${inputClass} tabular-nums`}
              />
              <p id={`${titleId}-score-hint`} className={hintClass}>От 1 до 1000; пусто — задание без баллов.</p>
            </div>
            <label className="flex cursor-pointer items-start gap-2.5 self-start rounded-control border border-line-strong bg-surface px-3 py-2.5 text-sm text-fg sm:mt-7 has-[:focus-visible]:ring-4 has-[:focus-visible]:ring-ring">
              <input
                type="checkbox"
                checked={form.allowLate}
                onChange={(event) => setForm((current) => ({ ...current, allowLate: event.target.checked }))}
                className={`${checkboxClass} mt-0.5`}
              />
              <span>
                Разрешить сдачу после срока
                <span className="block text-small text-fg-muted">Такие работы отмечаются «с опозданием».</span>
              </span>
            </label>
          </div>
          {published && (
            <p className="flex items-start gap-2 rounded-control bg-primary-soft px-3 py-2 text-small text-primary-fg">
              <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              Студенты курса сразу увидят изменения; их статусы и сданные решения сохранятся.
            </p>
          )}
          <DialogActions>
            <Button variant="outline" onClick={onClose} disabled={saving}>Отмена</Button>
            <Button type="submit" loading={saving} disabled={!form.title.trim()}>Сохранить</Button>
          </DialogActions>
        </form>
    </Dialog>
    {confirmDialog}
    </>
  )
}
