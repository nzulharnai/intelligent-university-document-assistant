import { Fragment, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { EyeOff, Highlighter, Loader2, SearchX } from 'lucide-react'
import api, { describeError } from '../api/axios'
import ErrorState from './ErrorState'
import Button from './ui/Button'
import { PII_PLACEHOLDER } from '../utils/privacy'
import { locateFragment } from '../utils/fragments'

/**
 * The "Текст" tab: GET /documents/{id}/text - the original for the course teacher and ADMIN, for everyone else the
 * copy with personal data replaced by placeholders, which are highlighted here.
 *
 * US-15: with {@code fragment} ({ from, to, text }, from a Q&A source) the masked copy is loaded for every role, as
 * the source's offsets point into it, and the fragment is marked and scrolled to. A stale link (the document was
 * processed again) falls back to finding the fragment's text; when that fails the text is shown without a mark.
 *
 * @param manages   the viewer manages the course (teacher, ADMIN): they may switch back to the original
 * @param onClearFragment removes the fragment from the address ("Снять выделение", "Показать оригинал")
 */
export default function DocumentTextPanel({ doc, fragment = null, manages = false, onClearFragment }) {
  const [state, setState] = useState({ loading: true, data: null, error: '' })
  const [attempt, setAttempt] = useState(0)
  // The masked copy stays on screen after "Снять выделение"; only "Показать оригинал" leaves it.
  const [maskedView, setMaskedView] = useState(Boolean(fragment))
  const [dismissed, setDismissed] = useState(false)
  const markRef = useRef(null)
  const [previousFragment, setPreviousFragment] = useState(fragment)

  // A new fragment (another source clicked) shows the masked copy and the mark again.
  if (fragment !== previousFragment) {
    setPreviousFragment(fragment)
    if (fragment) {
      setMaskedView(true)
      setDismissed(false)
    }
  }

  const load = useCallback(() => api.get(`/documents/${doc.id}/text`, { params: maskedView ? { masked: true } : {} })
    .then(({ data }) => setState({ loading: false, data, error: '' }))
    .catch((err) => setState({ loading: false, data: null, error: describeError(err).message })), [doc.id, maskedView])

  useEffect(() => {
    load()
  }, [load, attempt])

  const retry = () => {
    setState({ loading: true, data: null, error: '' })
    setAttempt((value) => value + 1)
  }

  const { data } = state
  const showFragment = Boolean(fragment) && !dismissed && Boolean(data?.masked)
  const range = useMemo(
    () => (showFragment ? locateFragment(data?.text, fragment, fragment.text) : null),
    [showFragment, data, fragment],
  )

  useEffect(() => {
    markRef.current?.scrollIntoView({ block: 'center', behavior: 'smooth' })
  }, [range])

  const clearMark = () => {
    setDismissed(true)
    onClearFragment?.()
  }

  const showOriginal = () => {
    setState({ loading: true, data: null, error: '' })
    setDismissed(true)
    setMaskedView(false)
    onClearFragment?.()
  }

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
          {showFragment && (range ? (
            <div role="status" className="flex flex-wrap items-center justify-between gap-2 border-b border-line-subtle bg-primary-soft px-4 py-2.5">
              <p className="flex items-center gap-2 text-sm font-medium text-primary-fg">
                <Highlighter className="h-4 w-4 shrink-0" aria-hidden="true" /> Показан фрагмент, на который сослался ИИ
              </p>
              <Button variant="ghost" size="sm" onClick={clearMark}>Снять выделение</Button>
            </div>
          ) : (
            <div role="status" className="flex flex-wrap items-center justify-between gap-2 border-b border-line-subtle bg-warning-soft px-4 py-2.5">
              <p className="flex items-center gap-2 text-sm text-warning-fg">
                <SearchX className="h-4 w-4 shrink-0" aria-hidden="true" /> Документ изменился, фрагмент не найден
              </p>
              <Button variant="ghost" size="sm" onClick={clearMark}>Скрыть</Button>
            </div>
          ))}
          {manages && data.masked && maskedView && (
            <div className="flex flex-wrap items-center justify-between gap-2 border-b border-line-subtle bg-page-alt px-4 py-2.5">
              <p className="text-small text-fg-secondary">
                Показана копия со скрытыми личными данными: по ней ИИ искал ответ, поэтому выделение совпадает с фрагментом.
              </p>
              <Button variant="secondary" size="sm" onClick={showOriginal}>Показать оригинал</Button>
            </div>
          )}
          {data.masked && data.piiCount > 0 && (
            <p className="flex items-center gap-2 border-b border-line-subtle bg-warning-soft px-4 py-2.5 text-sm text-warning-fg">
              <EyeOff className="h-4 w-4 shrink-0" aria-hidden="true" /> Часть личных данных скрыта.
            </p>
          )}
          <div className="max-h-[70vh] overflow-y-auto px-4 py-5 sm:px-6">
            <div className="mx-auto max-w-reading whitespace-pre-wrap break-words text-body text-fg">
              {range ? (
                <>
                  {highlightPlaceholders(data.text.slice(0, range.from), 'before')}
                  <mark
                    ref={markRef}
                    className="scroll-mt-24 rounded bg-warning-soft text-inherit ring-2 ring-warning/40"
                  >
                    {highlightPlaceholders(data.text.slice(range.from, range.to), 'fragment')}
                  </mark>
                  {highlightPlaceholders(data.text.slice(range.to), 'after')}
                </>
              ) : highlightPlaceholders(data.text, 'text')}
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
function highlightPlaceholders(text, keyPrefix) {
  return text.split(PII_PLACEHOLDER).map((part, index) => (index % 2 === 1 ? (
    <mark key={`${keyPrefix}-${index}`} className="rounded bg-primary-soft px-1 font-medium text-primary-fg">
      {part}
    </mark>
  ) : <Fragment key={`${keyPrefix}-${index}`}>{part}</Fragment>))
}
