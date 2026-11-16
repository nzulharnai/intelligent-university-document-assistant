import { Fragment, useEffect, useRef, useState } from 'react'
import { Link, useLocation } from 'react-router-dom'
import { AlertTriangle, ArrowRight, Bot, CalendarClock, ChevronDown, ClipboardList, FileText, GraduationCap, Info, ListChecks, Loader2, Quote, RotateCw, SearchX, SendHorizontal, Sparkles, TextSearch, User } from 'lucide-react'
import api, { describeError } from '../api/axios'
import { useToast } from '../context/ToastContext'
import useAiStatus from '../hooks/useAiStatus'
import { formatDateTime } from '../utils/dates'
import { fragmentLink } from '../utils/fragments'

const MAX_QUESTION_LENGTH = 1000 // DocumentQuestionRequest: @Size(max = 1000)
// The backend waits for the model (read timeout 90 s); the default axios timeout of 15 s would cut it off.
const ASK_TIMEOUT_MS = 120_000
// [2], [2, 3], [Фрагмент 2] and the backend prompt's [file.pdf, фрагмент 2]; the lazy prefix keeps [1, 2] whole.
const CITATION = /\[(?:[^[\]]{0,200}?,\s*)??(?:фрагмент\p{L}*\s*)?(\d+(?:\s*,\s*\d+)*)\]/giu
// [задание 2], [Задания 1, 3] and [file.pdf, фрагмент 1; задание 2]: the backend's [АКТУАЛЬНЫЕ ЗАДАНИЯ КУРСА] numbers.
const ASSIGNMENT_CITATION = /\[(?:[^[\]]{0,200}?[,;]\s*)??задани\p{L}*\s*(\d+(?:\s*,\s*\d+)*)\]/giu
// [задача 1], [Задачи 1, 2]: the student's own tasks ([МОИ ЗАДАЧИ]).
const TASK_CITATION = /\[(?:[^[\]]{0,200}?[,;]\s*)??задач\p{L}*\s*(\d+(?:\s*,\s*\d+)*)\]/giu
// The states the backend names a task by, as "Мои задачи" shows them.
const TASK_STATE_LABELS = {
  OVERDUE: 'Просрочено',
  NOT_STARTED: 'Не начато',
  IN_PROGRESS: 'В работе',
  SUBMITTED: 'Сдано',
  LATE: 'Сдано с опозданием',
  GRADED: 'Оценено',
  COMPLETED: 'Выполнено',
  BEFORE_ENROLLMENT: 'Срок прошёл до записи',
  CLOSED: 'Задание закрыто',
}

// The saved conversation is gone or full: the next question starts a new one.
const CONVERSATION_RESET_CODES = new Set(['CONVERSATION_NOT_FOUND', 'CONVERSATION_FULL'])

/** The turns of a saved conversation (GET /ai/conversations/{id}) as chat messages. */
function messagesOf(turns) {
  return (turns ?? []).flatMap((turn) => [
    { id: `q${turn.id}`, role: 'user', text: turn.question },
    { id: `a${turn.id}`, role: 'assistant', question: turn.question, ...turn.answer },
  ])
}

/**
 * Q&A chat over a document or a course (RAG). Answers cite fragments as [n] and the course's current assignments as
 * [задание N]; clicking a citation opens that fragment or points at that assignment. POST /ai/ask saves every
 * question in a conversation (US-14): the chat sends its conversationId back, so later questions continue it and the
 * model understands follow-ups. Other endpoints (the document chat) keep the history only while mounted.
 *
 * @param endpoint   POST endpoint taking { question } and returning { answer, hasAnswer, sources, assignments,
 *                   citedExplicitly }
 * @param courseId   optional; sent as { question, courseId } (POST /ai/ask: one course instead of all of them)
 * @param showDocuments  show which document and course each source comes from (course and global chats)
 * @param disabledReason when set, the input is disabled and this text is shown instead
 * @param sourceAction   optional (source) => element shown under an opened source, e.g. "Показать в тексте"
 * @param conversation   optional saved conversation to continue: { id, turns } (remount the chat to switch)
 * @param onConversation optional (id) => called when the backend saved the first question in a new conversation
 * @param onAnswered     optional () => called after every answer (e.g. to refresh the history or the limits)
 */
export default function AiChat({
  endpoint, courseId, title, subtitle, placeholder, suggestions = [], showDocuments = false, disabledReason,
  sourceAction, conversation, onConversation, onAnswered,
}) {
  const aiAvailable = useAiStatus()
  const { notifyError } = useToast()
  const [messages, setMessages] = useState(() => messagesOf(conversation?.turns))
  const [conversationId, setConversationId] = useState(conversation?.id ?? null)
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
      const body = { question: trimmed }
      if (courseId != null) body.courseId = courseId
      if (conversationId != null) body.conversationId = conversationId
      const { data } = await api.post(endpoint, body, { timeout: ASK_TIMEOUT_MS })
      setMessages((current) => [...current, { id: id + 1, role: 'assistant', question: trimmed, ...data }])
      if (data.conversationId != null && data.conversationId !== conversationId) {
        setConversationId(data.conversationId)
        onConversation?.(data.conversationId)
      }
      onAnswered?.()
      // A question put back into the input after a failure is answered now; clear it unless the user edited it.
      setQuestion((current) => (current.trim() === trimmed ? '' : current))
    } catch (err) {
      const { status, message } = describeError(err)
      if (status === 503 || status === 403) notifyError(err)
      if (CONVERSATION_RESET_CODES.has(err?.response?.data?.code)) setConversationId(null)
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
              : <AnswerBubble key={message.id} message={message} showDocuments={showDocuments} sourceAction={sourceAction} />
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

function AnswerBubble({ message, showDocuments, sourceAction }) {
  const [openFragment, setOpenFragment] = useState(null)
  const [activeAssignment, setActiveAssignment] = useState(null)
  const [activeTask, setActiveTask] = useState(null)
  const assignmentsRef = useRef(null)
  const tasksRef = useRef(null)
  const sources = message.sources ?? []
  const assignments = message.assignments ?? []
  const tasks = message.tasks ?? []
  const termPattern = questionTermPattern(message.question)
  // An older answer (or a server before Sprint 5) has no flag: treat its sources as cited.
  const allContext = message.citedExplicitly === false && sources.length > 0

  const toggle = (fragment) => setOpenFragment((current) => (current === fragment ? null : fragment))
  const pointAt = (number) => {
    setActiveAssignment(number)
    const card = assignmentsRef.current?.querySelector(`[data-assignment="${number}"]`)
    card?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    card?.focus({ preventScroll: true })
  }
  const pointAtTask = (number) => {
    setActiveTask(number)
    const card = tasksRef.current?.querySelector(`[data-task="${number}"]`)
    card?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    card?.focus({ preventScroll: true })
  }

  return (
    <div className="flex gap-2">
      <AssistantAvatar />
      <div className="min-w-0 max-w-[85%] flex-1">
        {!message.hasAnswer && (
          // US-15 AC-4: an answer the documents do not hold is marked as such, not dressed up as an answer.
          <p className="mb-1 inline-flex items-center gap-1.5 rounded-full bg-warning-soft px-2 py-0.5 text-[11px] font-semibold text-warning-fg">
            <SearchX className="h-3 w-3" aria-hidden="true" /> Не найдено в документах
          </p>
        )}
        <div className={`rounded-card rounded-tl-sm border px-4 py-2.5 text-sm leading-6 ${
          message.hasAnswer
            ? 'border-line bg-page-alt text-fg'
            : 'border-line bg-surface italic text-fg-muted'
        }`}
        >
          <p className="whitespace-pre-wrap break-words">
            <AnswerText
              text={message.answer}
              sources={sources}
              assignments={assignments}
              tasks={tasks}
              onCite={toggle}
              onCiteAssignment={pointAt}
              onCiteTask={pointAtTask}
            />
          </p>
        </div>
        {sources.length > 0 && (
          <div className="mt-2 space-y-1.5">
            <p className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-fg-muted">
              <Quote className="h-3 w-3" /> Источники ({sources.length})
            </p>
            {allContext && (
              <p className="flex items-start gap-1.5 text-xs text-fg-muted">
                <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" aria-hidden="true" />
                ИИ не указал конкретные фрагменты. Показаны все, по которым он отвечал.
              </p>
            )}
            {sources.map((source) => (
              <SourceCard
                key={source.fragment}
                source={source}
                open={openFragment === source.fragment}
                onToggle={() => toggle(source.fragment)}
                showDocument={showDocuments}
                termPattern={termPattern}
                action={sourceAction}
              />
            ))}
          </div>
        )}
        {assignments.length > 0 && (
          <div ref={assignmentsRef} className="mt-2 space-y-1.5">
            <p className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-fg-muted">
              <ClipboardList className="h-3 w-3" aria-hidden="true" /> Задания курса ({assignments.length})
            </p>
            {assignments.map((assignment) => (
              <AssignmentCard key={assignment.number} assignment={assignment} active={activeAssignment === assignment.number} />
            ))}
          </div>
        )}
        {tasks.length > 0 && (
          <div ref={tasksRef} className="mt-2 space-y-1.5">
            <p className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-fg-muted">
              <ListChecks className="h-3 w-3" aria-hidden="true" /> Мои задачи ({tasks.length})
            </p>
            {tasks.map((task) => (
              <TaskCard key={task.number} task={task} active={activeTask === task.number} />
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

// One of the student's own tasks the answer cites as [задача N]; the link opens "Мои задачи" on the dashboard.
function TaskCard({ task, active }) {
  const overdue = task.state === 'OVERDUE'
  return (
    <div
      data-task={task.number}
      tabIndex={-1}
      className={`flex items-start gap-2.5 rounded-xl border px-3 py-2 outline-none transition focus-visible:ring-2 focus-visible:ring-primary ${
        active ? 'border-primary bg-primary-soft' : overdue ? 'border-danger/40 bg-surface' : 'border-line bg-surface'
      }`}
    >
      <span className="mt-0.5 inline-flex h-5 shrink-0 items-center justify-center rounded-md bg-success-soft px-1.5 text-[11px] font-bold text-success-fg">
        Т{task.number}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block break-words text-xs font-semibold text-fg">{task.title}</span>
        <span className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-fg-muted">
          <span className={`font-medium ${overdue ? 'text-danger-fg' : 'text-fg-secondary'}`}>
            {TASK_STATE_LABELS[task.state] ?? task.state}
          </span>
          {task.courseTitle && (
            <span className="inline-flex min-w-0 items-center gap-1">
              <GraduationCap className="h-3 w-3 shrink-0" aria-hidden="true" />
              <span className="truncate">{task.courseTitle}</span>
            </span>
          )}
          <span className="inline-flex items-center gap-1">
            <CalendarClock className="h-3 w-3 shrink-0" aria-hidden="true" />
            {task.deadline ? `до ${formatDateTime(task.deadline)}` : 'Срок не указан'}
          </span>
        </span>
        <Link
          to="/dashboard#my-tasks"
          className="mt-1 inline-flex items-center gap-1 rounded-sm text-xs font-medium text-primary-fg hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          К моим задачам <ArrowRight className="h-3 w-3" aria-hidden="true" />
        </Link>
      </span>
    </div>
  )
}

// One assignment the answer cites as [задание N], with its current deadline from "Задания курса".
function AssignmentCard({ assignment, active }) {
  return (
    <div
      data-assignment={assignment.number}
      tabIndex={-1}
      className={`flex items-start gap-2.5 rounded-xl border px-3 py-2 outline-none transition focus-visible:ring-2 focus-visible:ring-primary ${
        active ? 'border-primary bg-primary-soft' : 'border-line bg-surface'
      }`}
    >
      <span className="mt-0.5 inline-flex h-5 shrink-0 items-center justify-center rounded-md bg-accent-soft px-1.5 text-[11px] font-bold text-accent-fg">
        З{assignment.number}
      </span>
      <span className="min-w-0 flex-1">
        <span className="block break-words text-xs font-semibold text-fg">{assignment.title}</span>
        <span className="mt-0.5 flex flex-wrap items-center gap-x-3 gap-y-0.5 text-[11px] text-fg-muted">
          {assignment.courseTitle && (
            <span className="inline-flex min-w-0 items-center gap-1">
              <GraduationCap className="h-3 w-3 shrink-0" aria-hidden="true" />
              <span className="truncate">{assignment.courseTitle}</span>
            </span>
          )}
          <span className="inline-flex items-center gap-1">
            <CalendarClock className="h-3 w-3 shrink-0" aria-hidden="true" />
            {assignment.deadline ? `до ${formatDateTime(assignment.deadline)}` : 'Срок не указан'}
          </span>
        </span>
        {assignment.courseId != null && (
          <Link
            to={`/courses/${assignment.courseId}#assignments`}
            className="mt-1 inline-flex items-center gap-1 rounded-sm text-xs font-medium text-primary-fg hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            К заданиям курса <ArrowRight className="h-3 w-3" aria-hidden="true" />
          </Link>
        )}
      </span>
    </div>
  )
}

// Fragment and assignment citations in the order they appear; a match inside an earlier one is skipped.
function citations(text) {
  const found = [
    ...[...text.matchAll(CITATION)].map((match) => ({ match, kind: 'fragment' })),
    ...[...text.matchAll(ASSIGNMENT_CITATION)].map((match) => ({ match, kind: 'assignment' })),
    ...[...text.matchAll(TASK_CITATION)].map((match) => ({ match, kind: 'task' })),
  ].sort((a, b) => a.match.index - b.match.index)
  const result = []
  let end = 0
  for (const item of found) {
    if (item.match.index < end) continue
    result.push(item)
    end = item.match.index + item.match[0].length
  }
  return result
}

const CITE_BUTTON = 'mx-0.5 inline-flex h-5 min-w-5 items-center justify-center rounded-md px-1 align-text-top text-[11px] font-bold not-italic transition focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary'

// Renders "[1]" / "[2, 3]" as buttons that open the cited source, "[задание 2]" as buttons that point at the assignment.
function AnswerText({ text, sources, assignments, tasks, onCite, onCiteAssignment, onCiteTask }) {
  const knownFragments = new Set(sources.map((source) => source.fragment))
  const knownAssignments = new Set(assignments.map((assignment) => assignment.number))
  const knownTasks = new Set(tasks.map((task) => task.number))
  const KINDS = {
    fragment: { known: knownFragments, onClick: onCite, label: (n) => `Фрагмент ${n}`, text: (n) => n, plain: (n) => `[${n}]`, tone: 'bg-primary-soft text-primary-fg hover:bg-primary/20' },
    assignment: { known: knownAssignments, onClick: onCiteAssignment, label: (n) => `Задание ${n}`, text: (n) => `З${n}`, plain: (n) => `[задание ${n}]`, tone: 'bg-accent-soft text-accent-fg hover:bg-accent/20' },
    task: { known: knownTasks, onClick: onCiteTask, label: (n) => `Моя задача ${n}`, text: (n) => `Т${n}`, plain: (n) => `[задача ${n}]`, tone: 'bg-success-soft text-success-fg hover:bg-success/20' },
  }
  const parts = []
  let last = 0
  for (const { match, kind } of citations(text)) {
    parts.push(text.slice(last, match.index))
    const numbers = match[1].split(',').map((value) => Number(value.trim()))
    const style = KINDS[kind]
    parts.push(
      <span key={match.index} className="whitespace-nowrap">
        {numbers.map((number) => (style.known.has(number) ? (
          <button
            key={number}
            type="button"
            onClick={() => style.onClick(number)}
            className={`${CITE_BUTTON} ${style.tone}`}
            title={`Показать: ${style.label(number).toLowerCase()}`}
            aria-label={style.label(number)}
          >
            {style.text(number)}
          </button>
        ) : <Fragment key={number}>{style.plain(number)}</Fragment>))}
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

function SourceCard({ source, open, onToggle, showDocument, termPattern, action }) {
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
            <span className="mb-0.5 flex flex-wrap items-center gap-x-2 gap-y-0.5 text-xs">
              <span className="inline-flex min-w-0 items-center gap-1 font-semibold text-fg">
                <FileText className="h-3 w-3 shrink-0" aria-hidden="true" />
                <span className="truncate">{source.documentName}</span>
              </span>
              {source.courseTitle && (
                <span className="inline-flex min-w-0 items-center gap-1 text-fg-muted">
                  <GraduationCap className="h-3 w-3 shrink-0" aria-hidden="true" />
                  <span className="truncate">{source.courseTitle}</span>
                </span>
              )}
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
            <div className="mt-2 flex flex-wrap gap-x-4 gap-y-1">
              <Link
                to={fragmentLink(source)}
                state={{ from: location.pathname, fragmentText: source.text }}
                className="inline-flex items-center gap-1 rounded-sm text-xs font-medium text-primary-fg hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                Показать в документе <TextSearch className="h-3 w-3" aria-hidden="true" />
              </Link>
              <Link
                to={`/documents/${source.documentId}`}
                state={{ from: location.pathname }}
                className="inline-flex items-center gap-1 rounded-sm text-xs font-medium text-fg-secondary hover:text-primary-fg hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
              >
                Открыть документ <FileText className="h-3 w-3" aria-hidden="true" />
              </Link>
            </div>
          )}
          {action?.(source)}
        </div>
      )}
    </div>
  )
}
