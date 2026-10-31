import { Fragment, useCallback, useEffect, useState } from 'react'
import { EyeOff, Loader2 } from 'lucide-react'
import api, { describeError } from '../api/axios'
import ErrorState from './ErrorState'
import { PII_PLACEHOLDER } from '../utils/privacy'

/**
 * The "Текст" tab: GET /documents/{id}/text - the original for the course teacher and ADMIN, for everyone else the
 * copy with personal data replaced by placeholders, which are highlighted here.
 */
export default function DocumentTextPanel({ doc }) {
  const [state, setState] = useState({ loading: true, data: null, error: '' })
  const [attempt, setAttempt] = useState(0)

  const load = useCallback(() => api.get(`/documents/${doc.id}/text`)
    .then(({ data }) => setState({ loading: false, data, error: '' }))
    .catch((err) => setState({ loading: false, data: null, error: describeError(err).message })), [doc.id])

  useEffect(() => {
    load()
  }, [load, attempt])

  const retry = () => {
    setState({ loading: true, data: null, error: '' })
    setAttempt((value) => value + 1)
  }

  const { data } = state
  return (
    <div className="overflow-hidden rounded-card border border-line bg-surface shadow-card">
      {state.loading ? (
        <div role="status" className="flex h-48 items-center justify-center gap-2 text-sm text-fg-muted">
          <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden="true" /> Загрузка текста…
        </div>
      ) : state.error ? (
        <ErrorState title="Не удалось загрузить текст" message={state.error} onRetry={retry} />
      ) : !data.text ? (
        <p className="px-4 py-10 text-center text-sm text-fg-muted">
          Текст документа не распознан: это может быть скан без текстового слоя или защищённый файл.
        </p>
      ) : (
        <>
          {data.masked && data.piiCount > 0 && (
            <p className="flex items-center gap-2 border-b border-line-subtle bg-warning-soft px-4 py-2.5 text-sm text-warning-fg">
              <EyeOff className="h-4 w-4 shrink-0" aria-hidden="true" /> Часть личных данных скрыта.
            </p>
          )}
          <div className="max-h-[70vh] overflow-y-auto px-4 py-5 sm:px-6">
            <div className="mx-auto max-w-reading whitespace-pre-wrap break-words text-body text-fg">
              {highlightPlaceholders(data.text)}
            </div>
          </div>
          {data.truncated && (
            <p className="border-t border-line-subtle px-4 py-2 text-small text-fg-muted">
              Показаны первые 100 000 символов: остальная часть файла не прочитана.
            </p>
          )}
        </>
      )}
    </div>
  )
}

// split() with a capturing group keeps the placeholders at the odd indexes.
function highlightPlaceholders(text) {
  return text.split(PII_PLACEHOLDER).map((part, index) => (index % 2 === 1 ? (
    <mark key={index} className="rounded bg-primary-soft px-1 font-medium text-primary-fg">
      {part}
    </mark>
  ) : <Fragment key={index}>{part}</Fragment>))
}
