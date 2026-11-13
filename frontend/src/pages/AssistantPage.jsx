import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { GraduationCap, Sparkles } from 'lucide-react'
import api, { describeError } from '../api/axios'
import AiChat from '../components/AiChat'
import ErrorState from '../components/ErrorState'
import Navbar from '../components/Navbar'
import SearchSelect from '../components/SearchSelect'
import EmptyState from '../components/ui/EmptyState'
import PageHeader from '../components/ui/PageHeader'
import Skeleton from '../components/ui/Skeleton'
import { buttonClass, labelClass } from '../components/ui/styles'
import { useAuth } from '../context/AuthContext'
import { ROLE_STUDENT } from '../constants/roles'

const ALL_COURSES = { value: '', label: 'Все мои курсы' }
const SUGGESTIONS = ['Какие дедлайны на этой неделе?', 'Когда сдавать лабораторную 2?', 'Как выставляется итоговая оценка?']

/**
 * "ИИ-ассистент": questions in plain language over all of the user's courses (POST /ai/ask) or one of them
 * (courseId in the body). The backend searches only the courses the user may access.
 */
export default function AssistantPage() {
  const { user } = useAuth()
  const isStudent = user.role === ROLE_STUDENT
  const [courseCount, setCourseCount] = useState(null) // null while loading
  const [error, setError] = useState(null)
  const [attempt, setAttempt] = useState(0)
  const [scope, setScope] = useState(ALL_COURSES)

  useEffect(() => {
    let cancelled = false
    api.get('/courses', { params: { size: 1 } })
      .then(({ data }) => {
        if (cancelled) return
        setCourseCount(data.totalElements)
        setError(null)
      })
      .catch((err) => !cancelled && setError(describeError(err)))
    return () => {
      cancelled = true
    }
  }, [attempt])

  // "Все мои курсы" heads the first page; a search lists matching courses only.
  const fetchScopes = useCallback((term) => api.get('/courses', { params: { size: 20, ...(term ? { q: term } : {}) } })
    .then(({ data }) => {
      const courses = data.content.map((item) => ({
        value: String(item.id),
        label: item.code ? `${item.code} · ${item.title}` : item.title,
      }))
      return term ? courses : [ALL_COURSES, ...courses]
    }), [])

  const courseId = scope.value ? Number(scope.value) : undefined

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto max-w-4xl px-4 py-8 sm:px-6 sm:py-10">
        <PageHeader
          icon={Sparkles}
          title="ИИ-ассистент"
          subtitle={isStudent
            ? 'Вопросы обычным языком по документам и заданиям курсов, на которые вы записаны'
            : 'Вопросы обычным языком по документам и заданиям ваших курсов'}
        />

        <div className="mt-6">
          {error ? (
            <ErrorState
              title="Не удалось загрузить курсы"
              message={error.message}
              onRetry={() => {
                setError(null)
                setCourseCount(null)
                setAttempt((value) => value + 1)
              }}
            />
          ) : courseCount === null ? (
            <div role="status" className="space-y-3">
              <span className="sr-only">Загрузка…</span>
              <Skeleton className="h-11 w-full max-w-sm" rounded="rounded-control" />
              <Skeleton className="h-96 w-full" rounded="rounded-card" />
            </div>
          ) : courseCount === 0 ? (
            <EmptyState
              icon={GraduationCap}
              title={isStudent ? 'Вы пока не записаны ни на один курс' : 'У вас пока нет курсов'}
              description="ИИ-ассистент отвечает только по документам ваших курсов."
              action={<Link to="/courses" className={buttonClass({ variant: 'primary' })}>Курсы</Link>}
            />
          ) : (
            <div className="space-y-4">
              <div className="max-w-sm">
                <label htmlFor="assistant-scope" className={labelClass}>Где искать ответ</label>
                <SearchSelect
                  id="assistant-scope"
                  value={scope}
                  onChange={(option) => setScope(option ?? ALL_COURSES)}
                  fetchOptions={fetchScopes}
                  placeholder="Все мои курсы"
                  searchPlaceholder="Название или код курса"
                  emptyText="Курсы не найдены"
                  ariaLabel="Где искать ответ"
                />
              </div>
              <AiChat
                endpoint="/ai/ask"
                courseId={courseId}
                title={courseId ? `Вопрос по курсу «${scope.label}»` : 'Вопрос по всем моим курсам'}
                subtitle={courseId
                  ? 'Поиск по доступным вам документам и заданиям этого курса'
                  : 'Поиск по доступным вам документам и заданиям всех ваших курсов'}
                placeholder="Например: когда сдавать лабораторную 2?"
                suggestions={SUGGESTIONS}
                showDocuments
              />
            </div>
          )}
        </div>
      </main>
    </div>
  )
}
