import { useId, useRef } from 'react'
import { AlarmClock, AlertTriangle, Info } from 'lucide-react'
import useDialogFocus from '../hooks/useDialogFocus'
import Button from './ui/Button'
import { dialogPanelClass, overlayClass } from './ui/styles'

const TONES = {
  danger: { icon: 'bg-danger-soft text-danger', Icon: AlertTriangle, button: 'danger' },
  primary: { icon: 'bg-primary-soft text-primary', Icon: Info, button: 'primary' },
  warning: { icon: 'bg-warning-soft text-warning', Icon: AlarmClock, button: 'primary' },
}

// Modal confirmation, focus kept inside (red "danger" tone by default, for deletes). Escape or a click on the backdrop cancels (unless busy).
export default function ConfirmDialog({ title, children, confirmLabel = 'Удалить', tone = 'danger', loading, onConfirm, onCancel }) {
  const colors = TONES[tone] ?? TONES.danger
  const titleId = useId()
  const dialogRef = useRef(null)
  useDialogFocus(dialogRef, { onClose: onCancel, busy: loading })

  return (
    <div
      className={`${overlayClass} z-[70]`}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !loading) onCancel()
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`${dialogPanelClass} max-w-md`}
      >
        <div className="flex items-start gap-4">
          <span className={`flex h-11 w-11 shrink-0 items-center justify-center rounded-full ${colors.icon}`}>
            <colors.Icon className="h-5 w-5" aria-hidden="true" />
          </span>
          <div className="min-w-0">
            <h2 id={titleId} className="text-lg font-semibold text-fg">{title}</h2>
            <div className="mt-2 break-words text-sm text-fg-secondary">{children}</div>
          </div>
        </div>
        <div className="mt-6 flex flex-col-reverse gap-2 sm:flex-row sm:justify-end sm:gap-3">
          <Button variant="outline" onClick={onCancel} disabled={loading} autoFocus>
            Отмена
          </Button>
          <Button variant={colors.button} onClick={onConfirm} loading={loading}>
            {confirmLabel}
          </Button>
        </div>
      </div>
    </div>
  )
}
