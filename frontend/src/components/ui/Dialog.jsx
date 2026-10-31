import { useId, useRef } from 'react'
import { X } from 'lucide-react'
import useDialogFocus from '../../hooks/useDialogFocus'
import Button from './Button'
import { dialogPanelClass, overlayClass } from './styles'

const WIDTHS = { md: 'max-w-md', lg: 'max-w-lg' }

/**
 * Modal shell: backdrop, panel, header with {@code title} (optional {@code icon} and {@code subtitle}) and a close
 * button. Focus handling is useDialogFocus: focus stays inside, Escape and a click on the backdrop call
 * {@code onClose} unless {@code busy}. {@code closeDisabled} (defaults to busy) disables the close button.
 */
export default function Dialog({
  title, icon: Icon, subtitle, onClose, busy = false, closeDisabled = busy, size = 'md', zIndex = 'z-[60]', children,
}) {
  const titleId = useId()
  const dialogRef = useRef(null)
  useDialogFocus(dialogRef, { onClose, busy })

  return (
    <div
      className={`${overlayClass} ${zIndex}`}
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !busy) onClose()
      }}
    >
      <div
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        className={`${dialogPanelClass} ${WIDTHS[size] ?? WIDTHS.md}`}
      >
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <h2 id={titleId} className="flex items-center gap-2 text-lg font-semibold text-fg">
              {Icon && <Icon className="h-4.5 w-4.5 shrink-0 text-primary" aria-hidden="true" />}
              {title}
            </h2>
            {subtitle && <p className="mt-0.5 text-sm text-fg-muted">{subtitle}</p>}
          </div>
          <Button variant="ghost" size="sm" iconOnly icon={X} onClick={onClose} disabled={closeDisabled} aria-label="Закрыть" className="-mr-1.5 -mt-1" />
        </div>
        {children}
      </div>
    </div>
  )
}

/** The Отмена / primary pair at the bottom of a dialog form. */
export function DialogActions({ children }) {
  return <div className="flex flex-col-reverse gap-2 pt-2 sm:flex-row sm:justify-end sm:gap-3">{children}</div>
}
