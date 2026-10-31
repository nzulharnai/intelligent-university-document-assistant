import { useId, useState } from 'react'
import { Lock, Tags } from 'lucide-react'
import api from '../api/axios'
import { useToast } from '../context/ToastContext'
import { CATEGORY_LABELS, CATEGORY_OPTIONS, TEACHER_ONLY_CATEGORY } from '../constants/documents'
import Button from './ui/Button'
import Dialog, { DialogActions } from './ui/Dialog'
import { controlClass, labelClass } from './ui/styles'

/**
 * "Указать тип вручную" (POST /documents/{id}/category): the course teacher sets the type the AI could not find,
 * e.g. for a scan whose text was not recognized. Exam answers become visible to the teacher only.
 */
export default function SetCategoryDialog({ doc, onClose, onSaved }) {
  const titleId = useId()
  const { notifyError, showSuccess } = useToast()
  const [category, setCategory] = useState(CATEGORY_OPTIONS.some((option) => option.value === doc.category) ? doc.category : CATEGORY_OPTIONS[0].value)
  const [saving, setSaving] = useState(false)

  const save = async (event) => {
    event.preventDefault()
    setSaving(true)
    try {
      const { data } = await api.post(`/documents/${doc.id}/category`, { category })
      showSuccess(`Тип документа: ${CATEGORY_LABELS[category] ?? category}`)
      onSaved(data)
      onClose()
    } catch (err) {
      notifyError(err)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog title="Указать тип вручную" icon={Tags} onClose={onClose} busy={saving}>
        <form onSubmit={save} className="mt-5 space-y-4">
          <div>
            <label htmlFor={`${titleId}-category`} className={labelClass}>
              Тип документа
            </label>
            <select
              id={`${titleId}-category`}
              value={category}
              onChange={(event) => setCategory(event.target.value)}
              className={controlClass}
            >
              {CATEGORY_OPTIONS.map((option) => <option key={option.value} value={option.value}>{option.label}</option>)}
            </select>
          </div>
          {category === TEACHER_ONLY_CATEGORY && (
            <p className="flex items-start gap-2 rounded-control bg-warning-soft px-3 py-2 text-small text-warning-fg">
              <Lock className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
              Ответы к экзамену будут видны только преподавателю курса.
            </p>
          )}
          <DialogActions>
            <Button variant="outline" onClick={onClose} disabled={saving}>Отмена</Button>
            <Button type="submit" loading={saving}>Сохранить</Button>
          </DialogActions>
        </form>
    </Dialog>
  )
}
