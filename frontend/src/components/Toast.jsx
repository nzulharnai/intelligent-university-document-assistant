import { useEffect } from 'react'
import { AlertCircle, CheckCircle2, X } from 'lucide-react'

// Errors stay longer: they often explain what to do next. Both can be closed by hand at any time.
const AUTO_CLOSE_MS = { success: 4000, error: 8000 }

// Pop-up notification in the bottom-right corner; closes itself after a few seconds.
// Pass a new `toast` object ({ id, type, message }) to show the next one.
export default function Toast({ toast, onClose }) {
  useEffect(() => {
    if (!toast) return undefined
    const timer = setTimeout(onClose, AUTO_CLOSE_MS[toast.type] ?? AUTO_CLOSE_MS.success)
    return () => clearTimeout(timer)
  }, [toast, onClose])

  if (!toast) return null
  const isError = toast.type === 'error'
  const Icon = isError ? AlertCircle : CheckCircle2

  return (
    <div className="pointer-events-none fixed inset-x-4 bottom-4 z-[60] flex justify-end sm:inset-x-auto sm:right-6 sm:bottom-6">
      <div
        key={toast.id}
        role={isError ? 'alert' : 'status'}
        className={`animate-step-in pointer-events-auto flex w-full max-w-sm items-start gap-3 rounded-card border border-l-4 border-line bg-surface p-4 shadow-card ${
          isError ? 'border-l-danger' : 'border-l-success'
        }`}
      >
        <Icon className={`mt-0.5 h-5 w-5 shrink-0 ${isError ? 'text-danger' : 'text-success'}`} aria-hidden="true" />
        <p className="min-w-0 flex-1 break-words text-sm text-fg">{toast.message}</p>
        <button
          type="button"
          onClick={onClose}
          className="rounded-md p-1 text-fg-muted transition hover:bg-page-alt hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          aria-label="Закрыть уведомление"
        >
          <X className="h-4 w-4" />
        </button>
      </div>
    </div>
  )
}
