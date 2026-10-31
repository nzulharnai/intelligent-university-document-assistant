import { Fragment, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { AlertTriangle, Bot, ChevronDown, FileText, Loader2, Quote, RotateCw, SendHorizontal, Sparkles, User } from 'lucide-react'
import api, { describeError } from '../api/axios'
import { useToast } from '../context/ToastContext'
import useAiStatus from '../hooks/useAiStatus'

const MAX_QUESTION_LENGTH = 1000 // DocumentQuestionRequest: @Size(max = 1000)
// The backend waits for the model (read timeout 90 s); the default axios timeout of 15 s would cut it off.
const ASK_TIMEOUT_MS = 120_000
// [2], [2, 3], [Фрагмент 2] and the backend prompt's [file.pdf, фрагмент 2]; the lazy prefix keeps [1, 2] whole.
const CITATION = /\[(?:[^[\]]{0,200}?,\s*)??(?:фрагмент\p{L}*\s*)?(\d+(?:\s*,\s*\d+)*)\]/giu

/**
 * Q&A chat over a document or a course (RAG). Answers cite fragments as [n]; clicking a citation or a source
 * card opens that fragment. History lives only while the page is open.
 *
 * @param endpoint   POST endpoint taking { question } and returning { answer, hasAnswer, sources }
 * @param showDocuments  show which document each source comes from (course chat)
 * @param disabledReason when set, the input is disabled and this text is shown instead
 */
export default function AiChat({ endpoint, title, subtitle, placeholder, suggestions = [], showDocuments = false, disabledReason }) {
  const aiAvailable = useAiStatus()
  const { notifyError } = useToast()
  const [messages, setMessages] = useState([])
  const [question, setQuestion] = useState('')
  const [loading, setLoading] = useState(false)
  const scrollRef = useRef(null)

  const blockedReason = disabledReason
    ?? (aiAvailable === false ? 'ИИ-ассистент не подключён на сервере. Обратитесь в деканат.' : null)

  useEffect(() => {
    const element = scrollRef.current
    if (element) element.scrollTo({ top: element.scrollHeight, behavior: 'smooth' })
  }, [messages, loading])

  // Sends a question whose user bubble is already in the conversation; failures leave an error bubble with a retry.
  const send = async (trimmed, id) => {
    setLoading(true)
    try {
      const { data } = await api.post(endpoint, { question: trimmed }, { timeout: ASK_TIMEOUT_MS })
      setMessages((current) => [...current, { id: id + 1, role: 'assistant', question: trimmed, ...data }])
      // A question put back into the input after a failure is answered now; clear it unless the user edited it.
      setQuestion((current) => (current.trim() === trimmed ? '' : current))
    } catch (err) {
      const { status, message } = describeError(err)
      if (status === 503 || status === 403) notifyError(err)
      setMessages((current) => [...current, { id: id + 1, role: 'error', text: message, status, question: trimmed }])
      // Keep the question: put it back if the input is still empty (the user may have started typing another one).
      setQuestion((current) => (current.trim() ? current : trimmed))
    } finally {
      setLoading(false)
    }
  }

  const ask = (text) => {
    const trimmed = text.trim()
    if (!trimmed || loading || blockedReason) return
    const id = Date.now()
    setMessages((current) => [...current, { id, role: 'user', text: trimmed }])
    setQuestion('')
    send(trimmed, id)
  }

  // "Повторить" on an error bubble: the same question again, in place of the error, without a second user bubble.
  const retry = (message) => {
    if (loading || blockedReason) return
    setMessages((current) => current.filter((item) => item.id !== message.id))
    send(message.question, message.id - 1)
  }

  const handleKeyDown = (event) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      ask(question)
    }
  }

  return (
    <section className="flex h-[38rem] max-h-[80vh] flex-col overflow-hidden rounded-card border border-line bg-surface shadow-card">
      <header className="flex items-center gap-3 border-b border-line-subtle px-5 py-4 sm:px-6">
        <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-primary text-on-primary">
          <Sparkles className="h-5 w-5" aria-hidden="true" />
        </span>
        <div className="min-w-0">
          <h2 className="text-base font-semibold text-fg">{title}</h2>
          <p className="truncate text-xs text-fg-muted">{subtitle}</p>
        </div>
      </header>

      <div ref={scrollRef} className="flex-1 space-y-4 overflow-y-auto px-4 py-5 sm:px-5" aria-live="polite">
        {messages.length === 0 && !loading && (
          <EmptyState blockedReason={blockedReason} suggestions={suggestions} onPick={ask} />
        )}
        {messages.map((message) => (
          message.role === 'user'
            ? <UserBubble key={message.id} text={message.text} />
            : message.role === 'error'
              ? <ErrorBubble key={message.id} text={message.text} onRetry={loading || blockedReason ? null : () => retry(message)} />
              : <AnswerBubble key={message.id} message={message} showDocuments={showDocuments} />
        ))}
        {loading && <ThinkingBubble />}
      </div>

      <form
        className="border-t border-line-subtle bg-page p-3 sm:p-4"
        onSubmit={(event) => {
          event.preventDefault()
          ask(question)
        }}
      >
        {blockedReason && messages.length > 0 && (
          <p className="mb-2 flex items-center gap-1.5 text-xs text-warning-fg">
            <AlertTriangle className="h-3.5 w-3.5" /> {blockedReason}
          </p>
        )}
        <div className="flex items-end gap-2">
          <textarea
            value={question}
            onChange={(event) => setQuestion(event.target.value.slice(0, MAX_QUESTION_LENGTH))}
            onKeyDown={handleKeyDown}
            rows={2}
            disabled={Boolean(blockedReason)}
            placeholder={blockedReason ? 'Чат недоступен' : placeholder}
            aria-label="Вопрос ИИ-ассистенту"
            className="min-h-[2.75rem] flex-1 resize-none rounded-xl border border-line-strong bg-surface px-3 py-2.5 text-sm text-fg outline-none transition placeholder:text-fg-muted focus:border-primary focus:ring-4 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-60"
          />
          <button
            type="submit"
            disabled={loading || !question.trim() || Boolean(blockedReason)}
            className="flex h-11 w-11 shrink-0 items-center justify-center rounded-xl bg-primary text-on-primary shadow-soft transition hover:bg-primary-hover focus:outline-none focus:ring-4 focus:ring-ring disabled:cursor-not-allowed disabled:opacity-50"
            aria-label="Отправить вопрос"
          >
            {loading ? <Loader2 className="h-4.5 w-4.5 animate-spin" /> : <SendHorizontal className="h-4.5 w-4.5" />}
          </button>
        </div>
        <p className="mt-1.5 flex justify-between text-[11px] text-fg-muted">
          <span>Enter — отправить, Shift+Enter — новая строка</span>
          <span>{question.length}/{MAX_QUESTION_LENGTH}</span>
        </p>
      </form>
    </section>
  )
}

function EmptyState({ blockedReason, suggestions, onPick }) {
  if (blockedReason) {
    return (
      <div className="mx-auto mt-8 flex max-w-sm flex-col items-center text-center">
        <span className="flex h-12 w-12 items-center justify-center rounded-card bg-warning-soft text-warning">
          <AlertTriangle className="h-6 w-6" />
        </span>
        <p className="mt-3 text-sm font-medium text-fg">{blockedReason}</p>
      </div>
    )
  }
  return (
    <div className="mx-auto mt-6 flex max-w-md flex-col items-center text-center">
      <span className="flex h-12 w-12 items-center justify-center rounded-card bg-primary-soft text-primary-fg">
        <Bot className="h-6 w-6" />
      </span>
      <p className="mt-3 text-sm font-medium text-fg">Задайте вопрос по содержанию</p>
      <p className="mt-1 text-xs text-fg-muted">
        ИИ отвечает только по тексту документов и указывает фрагменты-источники.
      </p>
      {suggestions.length > 0 && (
        <div className="mt-4 flex flex-wrap justify-center gap-2">
          {suggestions.map((suggestion) => (
            <button
              key={suggestion}
              type="button"
              onClick={() => onPick(suggestion)}
              className="rounded-full border border-primary/30 bg-surface px-3 py-1.5 text-xs font-medium text-primary-fg transition hover:bg-primary-soft focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring"
            >
              {suggestion}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}

function UserBubble({ text }) {
  return (
    <div className="flex justify-end gap-2">
      <p className="max-w-[85%] whitespace-pre-wrap break-words rounded-card rounded-tr-sm bg-primary px-4 py-2.5 text-sm text-on-primary shadow-card">
        {text}
      </p>
      <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary-soft text-primary-fg">
        <User className="h-4 w-4" />
      </span>
    </div>
  )
}

function AssistantAvatar() {
  return (
    <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-primary text-on-primary">
      <Bot className="h-4 w-4" aria-hidden="true" />
    </span>
  )
}

function ThinkingBubble() {
  return (
    <div className="flex gap-2" role="status">
      <AssistantAvatar />
      <div className="w-full max-w-[85%] rounded-card rounded-tl-sm border border-line bg-page-alt px-4 py-3">
        <p className="flex items-center gap-2 text-xs font-medium text-primary-fg">
          <Loader2 className="h-3.5 w-3.5 animate-spin" /> ИИ ищет ответ в документах…
        </p>
        <div className="mt-3 space-y-2" aria-hidden="true">
          <div className="skeleton animate-shimmer h-2.5 w-11/12 rounded" />
          <div className="skeleton animate-shimmer h-2.5 w-4/5 rounded" />
          <div className="skeleton animate-shimmer h-2.5 w-2/3 rounded" />
        </div>
      </div>
    </div>
  )
}

function ErrorBubble({ text, onRetry }) {
  return (
    <div className="flex gap-2">
      <AssistantAvatar />
      <div role="alert" className="max-w-[85%] rounded-card rounded-tl-sm border border-warning/30 bg-warning-soft px-4 py-2.5 text-sm text-warning-fg">
        <p className="flex items-start gap-2">
          <AlertTriangle className="mt-0.5 h-4 w-4 shrink-0" /> {text}
        </p>
        {onRetry && (
          <button
            type="button"
            onClick={onRetry}
            className="mt-2 inline-flex items-center gap-1 text-xs font-semibold text-primary-fg hover:underline focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <RotateCw className="h-3.5 w-3.5" /> Повторить
          </button>
        )}
      </div>
    </div>
  )
}

function AnswerBubble({ message, showDocuments }) {
  const [openFragment, setOpenFragment] = useState(null)
  const sources = message.sources ?? []
  const termPattern = questionTermPattern(message.question)

  const toggle = (fragment) => setOpenFragment((current) => (current === fragment ? null : fragment))

  return (
    <div className="flex gap-2">
      <AssistantAvatar />
      <div className="min-w-0 max-w-[85%] flex-1">
        <div className={`rounded-card rounded-tl-sm border px-4 py-2.5 text-sm leading-6 ${
          message.hasAnswer
            ? 'border-line bg-page-alt text-fg'
            : 'border-line bg-surface italic text-fg-muted'
        }`}
        >
          <p className="whitespace-pre-wrap break-words">
            <AnswerText text={message.answer} sources={sources} onCite={toggle} />
          </p>
        </div>
        {sources.length > 0 && (
          <div className="mt-2 space-y-1.5">
            <p className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-fg-muted">
              <Quote className="h-3 w-3" /> Источники ({sources.length})
            </p>
            {sources.map((source) => (
              <SourceCard
                key={source.fragment}
                source={source}
                open={openFragment === source.fragment}
                onToggle={() => toggle(source.fragment)}
                showDocument={showDocuments}
                termPattern={termPattern}
              />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

// Renders "[1]" / "[2, 3]" in the answer as buttons that open the cited source.
function AnswerText({ text, sources, onCite }) {
  const known = new Set(sources.map((source) => source.fragment))
  const parts = []
  let last = 0
  for (const match of text.matchAll(CITATION)) {
    parts.push(text.slice(last, match.index))
    const numbers = match[1].split(',').map((value) => Number(value.trim()))
    parts.push(
      <span key={match.index} className="whitespace-nowrap">
        {numbers.map((number) => (known.has(number) ? (
          <button
            key={number}
            type="button"
            onClick={() => onCite(number)}
            className="mx-0.5 inline-flex h-5 min-w-5 items-center justify-center rounded-md bg-primary-soft px-1 align-text-top text-[11px] font-bold not-italic text-primary-fg transition hover:bg-primary/20 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            title={`Показать фрагмент ${number}`}
          >
            {number}
          </button>
        ) : <Fragment key={number}>[{number}]</Fragment>))}
      </span>,
    )
    last = match.index + match[0].length
  }
  parts.push(text.slice(last))
  return parts
}

// Question words that are not just filler, matched by their first five letters so "лабораторную" also finds
// "Лабораторная" (a crude stand-in for the backend's Russian stemming).
const QUESTION_STOP_WORDS = new Set(['когда', 'какой', 'какая', 'какие', 'каких', 'какое', 'через', 'после', 'нужно', 'можно', 'этот', 'этом', 'этого', 'документ', 'документе', 'where', 'what', 'when', 'which'])

function questionTermPattern(question) {
  const stems = [...new Set((question ?? '').toLowerCase().match(/[\p{L}\p{N}]{4,}/gu) ?? [])]
    .filter((word) => !QUESTION_STOP_WORDS.has(word))
    .map((word) => word.slice(0, 5).replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
  return stems.length ? new RegExp(`(?:${stems.join('|')})[\\p{L}\\p{N}]*`, 'giu') : null
}

// Splits text into plain strings and <mark>ed matches of the question's words.
function highlight(text, pattern) {
  if (!pattern) return text
  const parts = []
  let last = 0
  for (const match of text.matchAll(pattern)) {
    parts.push(text.slice(last, match.index))
    parts.push(<mark key={match.index} className="rounded bg-warning-soft px-0.5 text-inherit ring-1 ring-warning/30">{match[0]}</mark>)
    last = match.index + match[0].length
  }
  parts.push(text.slice(last))
  return parts
}

// A ~160-character preview around the first matching word, so the collapsed card shows why it was cited.
function focusedPreview(text, pattern) {
  const first = pattern ? text.search(pattern) : -1
  const start = first > 60 ? text.lastIndexOf(' ', first - 50) + 1 : 0
  const slice = text.slice(start, start + 160)
  return `${start > 0 ? '…' : ''}${slice}${start + 160 < text.length ? '…' : ''}`
}

function SourceCard({ source, open, onToggle, showDocument, termPattern }) {
  const quoteRef = useRef(null)
  const location = useLocation()
  const preview = focusedPreview(source.text, termPattern)

  // Scroll the opened fragment to the first highlighted word; the relevant sentence is often mid-fragment.
  useEffect(() => {
    const quote = quoteRef.current
    const mark = quote?.querySelector('mark')
    if (open && mark) quote.scrollTop = Math.max(0, mark.offsetTop - 24)
  }, [open])

  return (
    <div className={`overflow-hidden rounded-xl border transition ${
      open
        ? 'border-primary bg-primary-soft'
        : 'border-line bg-surface'
    }`}
    >
      <button
        type="button"
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full items-start gap-2.5 px-3 py-2 text-left"
      >
        <span className="mt-0.5 flex h-5 min-w-5 items-center justify-center rounded-md bg-primary px-1 text-[11px] font-bold text-on-primary">
          {source.fragment}
        </span>
        <span className="min-w-0 flex-1">
          {showDocument && (
            <span className="mb-0.5 flex items-center gap-1 text-xs font-semibold text-fg">
              <FileText className="h-3 w-3 shrink-0" />
              <span className="truncate">{source.documentName}</span>
              <span className="shrink-0 font-normal text-fg-muted">· ID {source.documentId}</span>
            </span>
          )}
          {!open && <span className="line-clamp-2 text-xs text-fg-muted">{highlight(preview, termPattern)}</span>}
          <span className="mt-0.5 block text-[11px] text-fg-muted">
            Фрагмент №{source.chunkIndex + 1} · символы {source.startOffset.toLocaleString('ru-RU')}–{source.endOffset.toLocaleString('ru-RU')}
          </span>
        </span>
        <ChevronDown className={`mt-0.5 h-4 w-4 shrink-0 text-fg-muted transition-transform ${open ? 'rotate-180' : ''}`} />
      </button>
      {open && (
        <div className="border-t border-primary/25 px-3 py-2.5">
          <blockquote ref={quoteRef} className="relative max-h-56 overflow-y-auto whitespace-pre-wrap border-l-2 border-primary pl-3 text-xs leading-5 text-fg">
            {highlight(source.text, termPattern)}
          </blockquote>
          {showDocument && (
            <Link
              to={`/documents/${source.documentId}`}
              state={{ from: location.pathname }}
              className="mt-2 inline-flex items-center gap-1 text-xs font-medium text-primary-fg hover:underline"
            >
              Открыть документ <FileText className="h-3 w-3" />
            </Link>
          )}
        </div>
      )}
    </div>
  )
}
