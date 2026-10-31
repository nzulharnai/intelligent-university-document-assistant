import { AlertTriangle, RotateCw } from 'lucide-react'
import Button from './ui/Button'

/**
 * A failed load, always with a way to try again. Data blocks keep three distinct states: loading, this error, and
 * empty - an error never shows as an endless "Загрузка…" or as an empty list.
 *
 * @param message what went wrong, in Russian (describeError / getErrorMessage)
 * @param onRetry re-runs the load; without it no button is shown (e.g. 403/404, where retrying cannot help)
 * @param compact a single line, for small spots inside a header or a toolbar
 */
export default function ErrorState({ message, onRetry, title = 'Не удалось загрузить данные', compact = false, className = '' }) {
  if (compact) {
    return (
      <p role="alert" className={`flex flex-wrap items-center gap-x-1.5 gap-y-1 rounded-control bg-warning-soft px-3 py-2 text-small text-warning-fg ${className}`}>
        <AlertTriangle className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
        <span>{message}</span>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="ml-auto inline-flex shrink-0 items-center gap-1 rounded-md font-semibold underline-offset-2 hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <RotateCw className="h-3.5 w-3.5" aria-hidden="true" /> Повторить
          </button>
        )}
      </p>
    )
  }

  return (
    <div role="alert" className={`flex flex-col items-center px-4 py-8 text-center ${className}`}>
      <span className="flex h-11 w-11 items-center justify-center rounded-full bg-warning-soft text-warning">
        <AlertTriangle className="h-5 w-5" aria-hidden="true" />
      </span>
      <p className="mt-3 text-sm font-semibold text-fg">{title}</p>
      {message && <p className="mt-1 max-w-md text-small text-fg-secondary">{message}</p>}
      {onRetry && (
        <Button variant="secondary" size="sm" icon={RotateCw} onClick={onRetry} className="mt-4">
          Повторить
        </Button>
      )}
    </div>
  )
}
