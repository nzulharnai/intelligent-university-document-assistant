import { useCallback, useEffect, useRef, useState } from 'react'
import { AlertTriangle, ListChecks, Loader2, Lock, RefreshCw, Sparkles, Tags } from 'lucide-react'
import api from '../api/axios'
import { useToast } from '../context/ToastContext'
import { formatDateTime } from '../utils/dates'
import TaskItem from './TaskItem'
import { buttonClass } from './ui/styles'

const POLL_INTERVAL_MS = 3000
const POLL_LIMIT = 40 // About two minutes; analysis of a long document can take a while.
const TASKS_PAGE_SIZE = 50

/**
 * "Краткий анализ ИИ" of a document: summary, key topics and the course assignments found in it (each enrolled
 * student gets them as tasks).
 * After starting an analysis it polls GET /analysis until analyzedAt changes, then reloads the tasks.
 */
export default function AnalysisPanel({ doc }) {
  const { notifyError, showError, showSuccess } = useToast()
  const [analysis, setAnalysis] = useState(null)
  const [tasks, setTasks] = useState(null)
  const [loadError, setLoadError] = useState('')
  const [running, setRunning] = useState(false)
  const pollRef = useRef(null)

  const fetchAll = useCallback(() => Promise.all([
    api.get(`/documents/${doc.id}/analysis`),
    api.get(`/documents/${doc.id}/assignments`, { params: { size: TASKS_PAGE_SIZE } }),
  ]).then(([analysisResponse, tasksResponse]) => [analysisResponse.data, tasksResponse.data]), [doc.id])

  // Reloaded also when the document's category, AI status or number of found assignments changes (a reclassify on
  // this page): its assignments may have appeared or changed.
  useEffect(() => {
    let cancelled = false
    fetchAll()
      .then(([analysisData, tasksData]) => {
        if (cancelled) return
        setAnalysis(analysisData)
        setTasks(tasksData)
      })
      .catch((err) => !cancelled && setLoadError(notifyError(err)))
    return () => {
      cancelled = true
    }
  }, [fetchAll, notifyError, doc.category, doc.aiStatus, doc.assignmentsCount])

  // A running analysis poll is stopped only when the panel goes away, not by the reloads above.
  useEffect(() => () => clearTimeout(pollRef.current), [])

  const load = async () => {
    const [analysisData, tasksData] = await fetchAll()
    setAnalysis(analysisData)
    setTasks(tasksData)
  }

  const poll = (previousAnalyzedAt, attempt) => {
    pollRef.current = setTimeout(async () => {
      try {
        const { data } = await api.get(`/documents/${doc.id}/analysis`)
        if (data.analyzedAt && data.analyzedAt !== previousAnalyzedAt) {
          await load()
          setRunning(false)
          showSuccess('Анализ ИИ завершён')
        } else if (attempt + 1 >= POLL_LIMIT) {
          // Still queued or the AI failed (the backend only logs that); either way, stop waiting here.
          setRunning(false)
          showError('Анализ занимает дольше обычного или ИИ не ответил. Обновите страницу позже.')
        } else {
          poll(previousAnalyzedAt, attempt + 1)
        }
      } catch (err) {
        setRunning(false)
        notifyError(err)
      }
    }, POLL_INTERVAL_MS)
  }

  const startAnalysis = async () => {
    setRunning(true)
    try {
      await api.post(`/documents/${doc.id}/analyze`)
      poll(analysis?.analyzedAt ?? null, 0)
    } catch (err) {
      setRunning(false)
      notifyError(err)
    }
  }

  if (loadError) {
    return <PanelMessage icon={AlertTriangle} tone="warning" text={loadError} />
  }
  if (!analysis || !tasks) {
    return (
      <div className="flex items-center justify-center gap-2 py-16 text-sm text-fg-muted">
        <Loader2 className="h-4 w-4 animate-spin" /> Загрузка анализа…
      </div>
    )
  }

  const analyzed = Boolean(analysis.analyzedAt)
  // The backend refuses it (400) and already reports canAnalyze=false; say why instead of just hiding the button.
  const examAnswers = doc.category === 'EXAM_ANSWERS'
  const canRun = analysis.canAnalyze && analysis.aiAvailable && analysis.hasText && !examAnswers

  return (
    <div className="space-y-5">
      <section className="rounded-card border border-primary/25 bg-primary-soft/60 p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h3 className="flex items-center gap-2 text-base font-semibold text-fg">
              <Sparkles className="h-4.5 w-4.5 text-primary" aria-hidden="true" /> Краткий анализ ИИ
            </h3>
            <p className="mt-0.5 text-xs tabular-nums text-fg-muted">
              {analyzed ? `Обновлён ${formatDateTime(analysis.analyzedAt)}` : statusHint(analysis, examAnswers)}
            </p>
          </div>
          {analysis.canAnalyze && !examAnswers && (
            <button
              type="button"
              onClick={startAnalysis}
              disabled={!canRun || running}
              title={!analysis.aiAvailable ? 'ИИ-ассистент не подключён' : !analysis.hasText ? 'Нет распознанного текста' : undefined}
              className={buttonClass({ size: 'sm' })}
            >
              {running ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <RefreshCw className="h-3.5 w-3.5" />}
              {running ? 'Анализ выполняется…' : analyzed ? 'Проанализировать заново' : 'Запустить анализ'}
            </button>
          )}
        </div>

        {examAnswers && (
          <p className="mt-3 flex items-start gap-2 rounded-control bg-surface/70 px-3 py-2 text-xs text-fg-secondary">
            <Lock className="mt-0.5 h-3.5 w-3.5 shrink-0 text-danger" aria-hidden="true" />
            <span>{EXAM_ANSWERS_NOT_ANALYZED} — иначе их названия и сроки попали бы студентам в задачи. Документ виден только преподавателю курса.</span>
          </p>
        )}

        {running && (
          <p className="mt-3 rounded-control bg-surface/70 px-3 py-2 text-xs text-fg-secondary">
            ИИ читает документ — обычно это занимает от нескольких секунд до минуты. Результат появится здесь автоматически.
          </p>
        )}

        {analyzed && (
          <div className="mt-4 space-y-4">
            <div>
              <p className="text-[11px] font-semibold uppercase tracking-wide text-fg-muted">Краткая суть</p>
              <p className="mt-1 text-sm leading-6 text-fg">
                {analysis.summary ?? 'ИИ не сформулировал краткую суть для этого документа.'}
              </p>
            </div>
            {analysis.topics.length > 0 && (
              <div>
                <p className="flex items-center gap-1.5 text-[11px] font-semibold uppercase tracking-wide text-fg-muted">
                  <Tags className="h-3 w-3" /> Ключевые темы
                </p>
                <div className="mt-1.5 flex flex-wrap gap-1.5">
                  {analysis.topics.map((topic) => (
                    <span key={topic} className="rounded-full bg-surface px-2.5 py-1 text-xs font-medium text-primary-fg ring-1 ring-inset ring-ring">
                      {topic}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}
      </section>

      <section>
        <h3 className="flex items-center gap-2 text-sm font-semibold text-fg">
          <ListChecks className="h-4 w-4 text-primary-fg" />
          Задания курса из документа
          <span className="rounded-full bg-page-alt px-2 py-0.5 text-xs font-semibold text-fg-secondary">{tasks.totalElements}</span>
        </h3>
        {tasks.content.length === 0 ? (
          <p className="mt-3 rounded-xl border border-dashed border-line-strong px-4 py-6 text-center text-sm text-fg-muted">
            {analyzed ? 'ИИ не нашёл в документе заданий и дедлайнов.' : 'Задания появятся после анализа документа.'}
          </p>
        ) : (
          <ul className="mt-3 space-y-2">
            {tasks.content.map((task) => <TaskItem key={task.id} task={task} />)}
          </ul>
        )}
        {tasks.content.length > 0 && (
          <p className="mt-2 text-xs text-fg-muted">
            Каждый студент курса получает эти задания в «Мои задачи» и отмечает их выполнение сам.
          </p>
        )}
      </section>
    </div>
  )
}

// Same text as the backend's 400 for POST /analyze (DocumentService.EXAM_ANSWERS_NOT_ANALYZED).
const EXAM_ANSWERS_NOT_ANALYZED = 'Ответы к экзамену не анализируются на задания'

// Why there is no analysis yet, in the user's terms.
function statusHint(analysis, examAnswers) {
  if (examAnswers) return 'Анализ отключён для ответов к экзамену.'
  if (!analysis.hasText) return 'В документе нет распознанного текста — анализ недоступен.'
  if (!analysis.aiAvailable) return 'ИИ-ассистент не подключён на сервере.'
  if (analysis.autoAnalyzed) return 'Силлабусы и задания анализируются автоматически после загрузки — результат скоро появится.'
  return analysis.canAnalyze
    ? 'Для этой категории анализ запускается вручную.'
    : 'Анализ ещё не запускался. Его может запустить преподаватель курса.'
}

function PanelMessage({ icon: Icon, text }) {
  return (
    <p className="flex items-center gap-2 rounded-xl border border-warning/30 bg-warning-soft px-4 py-3 text-sm text-warning-fg">
      <Icon className="h-4 w-4 shrink-0" /> {text}
    </p>
  )
}
