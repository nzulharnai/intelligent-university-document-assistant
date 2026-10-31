import { useCallback, useEffect, useState } from 'react'
import { BadgeCheck, BookOpen, CircleAlert, ClipboardList, FileStack, GraduationCap, HardDrive, Loader2, ScrollText, Save, Search, ShieldCheck, Trash2, Users } from 'lucide-react'
import { Link } from 'react-router-dom'
import api, { getErrorMessage } from '../api/axios'
import { useToast } from '../context/ToastContext'
import { useAuth } from '../context/AuthContext'
import { DEFAULT_ROLE_BADGE_STYLE, ROLES, ROLE_BADGE_STYLES, ROLE_TEACHER, roleLabel } from '../constants/roles'
import Navbar from '../components/Navbar'
import ConfirmDialog from '../components/ConfirmDialog'
import { Alert } from '../components/FormControls'
import AdminTeacherRequests from '../components/AdminTeacherRequests'
import ErrorState from '../components/ErrorState'
import SearchSelect from '../components/SearchSelect'
import { formatDateTime } from '../utils/dates'
import PageHeader from '../components/ui/PageHeader'
import Skeleton from '../components/ui/Skeleton'
import { controlClass, tableHeadClass, tableRowClass } from '../components/ui/styles'


function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} Б`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} КБ`
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} МБ`
  return `${(bytes / 1024 / 1024 / 1024).toFixed(2)} ГБ`
}

const USERS_PAGE_SIZE = 20
const COURSES_PAGE_SIZE = 20

export default function AdminPage() {
  const { user: currentUser } = useAuth()
  const { notifyError, showSuccess } = useToast()

  const [stats, setStats] = useState(null)
  const [statsError, setStatsError] = useState('')
  const [selectedRoles, setSelectedRoles] = useState({}) // userId -> role picked in the dropdown
  const [savingId, setSavingId] = useState(null)
  const [deleteTarget, setDeleteTarget] = useState(null) // user awaiting delete confirmation
  const [deleting, setDeleting] = useState(false)
  const [requestsVersion, setRequestsVersion] = useState(0) // Bumped after role changes, see AdminTeacherRequests.

  // Users: server pages, searched by email or name (2+ characters, 300 ms after the last keystroke).
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('') // '' or the debounced search term
  const [usersPage, setUsersPage] = useState(null) // { ...PageResponse, forQuery }
  const [usersError, setUsersError] = useState('')
  const [usersAttempt, setUsersAttempt] = useState(0)
  const [loadingMoreUsers, setLoadingMoreUsers] = useState(false)


  const loadStats = useCallback(() => api.get('/admin/stats')
    .then(({ data }) => {
      setStats(data)
      setStatsError('')
    })
    .catch((err) => setStatsError(getErrorMessage(err))), [])

  useEffect(() => {
    loadStats()
  }, [loadStats])

  useEffect(() => {
    const timer = setTimeout(() => setQuery(search.trim().length >= 2 ? search.trim() : ''), 300)
    return () => clearTimeout(timer)
  }, [search])

  const fetchUsers = useCallback((number, term) => api.get('/admin/users', {
    params: { page: number, size: USERS_PAGE_SIZE, ...(term ? { q: term } : {}) },
  }).then(({ data }) => data), [])

  useEffect(() => {
    let cancelled = false
    fetchUsers(0, query)
      .then((data) => {
        if (cancelled) return
        setUsersPage({ ...data, forQuery: query })
        setUsersError('')
      })
      .catch((err) => !cancelled && setUsersError(getErrorMessage(err)))
    return () => {
      cancelled = true
    }
  }, [fetchUsers, query, usersAttempt])

  const users = usersPage?.content ?? []
  // Loading: nothing yet, or the shown page belongs to an older search term.
  const usersLoading = !usersError && (!usersPage || usersPage.forQuery !== query)
  const setUsers = (update) => setUsersPage((current) => current && ({ ...current, content: update(current.content) }))

  const loadMoreUsers = async () => {
    setLoadingMoreUsers(true)
    try {
      const next = await fetchUsers(usersPage.page + 1, query)
      setUsersPage((current) => ({ ...next, forQuery: query, content: [...current.content, ...next.content] }))
    } catch (err) {
      notifyError(err)
    } finally {
      setLoadingMoreUsers(false)
    }
  }

  const handleSave = async (target) => {
    const role = selectedRoles[target.id]
    if (!role || role === target.role) return
    setSavingId(target.id)
    try {
      const { data } = await api.patch(`/admin/users/${target.id}/role`, { role })
      setUsers((prev) => prev.map((item) => (item.id === data.id ? data : item)))
      setSelectedRoles((prev) => {
        const next = { ...prev }
        delete next[target.id]
        return next
      })
      showSuccess(`Роль пользователя ${data.email} успешно изменена`)
      // A promotion settles a pending teacher request; the requests list must not offer it any more.
      setRequestsVersion((value) => value + 1)
    } catch (err) {
      notifyError(err)
    } finally {
      setSavingId(null)
    }
  }

  const cancelDelete = useCallback(() => setDeleteTarget(null), [])

  const handleDelete = async () => {
    const target = deleteTarget
    setDeleting(true)
    try {
      await api.delete(`/admin/users/${target.id}`)
      setUsersPage((current) => current && ({
        ...current,
        totalElements: Math.max(0, current.totalElements - 1),
        content: current.content.filter((item) => item.id !== target.id),
      }))
      setDeleteTarget(null)
      showSuccess(`Пользователь ${target.email} удалён`)
      // Documents and disk usage changed along with the user; a pending request of theirs is gone too.
      loadStats()
      setRequestsVersion((value) => value + 1)
    } catch (err) {
      setDeleteTarget(null)
      notifyError(err)
    } finally {
      setDeleting(false)
    }
  }

  const cards = [
    { icon: Users, label: 'Всего пользователей', value: stats?.totalUsers },
    { icon: FileStack, label: 'Документов в хранилище', value: stats?.totalDocuments },
    {
      icon: HardDrive,
      label: 'Использование диска',
      value: stats ? formatBytes(stats.totalStorageBytes) : undefined,
      hint: 'Суммарный размер файлов',
    },
    { icon: BookOpen, label: 'Курсов', value: stats?.totalCourses },
    { icon: GraduationCap, label: 'Записей на курсы', value: stats?.totalEnrollments },
    { icon: ClipboardList, label: 'Заданий', value: stats?.totalAssignments },
  ]
  const statsLoading = stats === null && !statsError

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6 sm:py-10">
        <PageHeader icon={ShieldCheck} title="Панель деканата" subtitle="Пользователи, роли, курсы и системный журнал" />

        {statsError && (
          <ErrorState compact message={`Не удалось загрузить статистику: ${statsError}`} onRetry={loadStats} className="mt-6" />
        )}

        <section className="mt-8 grid gap-4 sm:grid-cols-2 lg:grid-cols-3" aria-label="Статистика" aria-busy={statsLoading || undefined}>
          {cards.map(({ icon: Icon, label, value, hint }) => (
            <div key={label} className="rounded-card border border-line bg-surface p-5 shadow-soft">
              <div className="flex items-start justify-between gap-3">
                <div className="min-w-0">
                  <p className="text-xs font-semibold uppercase tracking-wide text-fg-muted">{label}</p>
                  <p className="mt-2 text-[1.75rem] leading-9 font-semibold tabular-nums text-fg">
                    {statsLoading ? <Skeleton className="h-9 w-20" /> : (value ?? '—')}
                  </p>
                  {hint && <p className="mt-1 text-small text-fg-muted">{hint}</p>}
                </div>
                <span className="flex h-10 w-10 shrink-0 items-center justify-center rounded-control bg-primary-soft text-primary">
                  <Icon className="h-5 w-5" aria-hidden="true" />
                </span>
              </div>
            </div>
          ))}
        </section>

        <AdminTeacherRequests
          refreshKey={requestsVersion}
          onDecided={(updated) => setUsers((current) => current.map((item) => (item.id === updated.id ? updated : item)))}
        />

        <section className="mt-8 overflow-hidden rounded-card border border-line bg-surface shadow-card">
          <div className="flex flex-col gap-3 border-b border-line-subtle px-5 py-4 sm:flex-row sm:items-start sm:justify-between sm:px-6">
            <div className="min-w-0">
              <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
                Пользователи
                {usersPage && <span className="rounded-full bg-page-alt px-2 py-0.5 text-xs font-semibold tabular-nums text-fg-secondary">{usersPage.totalElements}</span>}
              </h2>
              <p className="mt-1 max-w-reading text-small text-fg-muted">
                После смены роли пользователю нужно войти заново — старый токен перестаёт действовать. Бывший студент
                отчисляется со всех курсов, курсы бывшего преподавателя остаются без преподавателя.
              </p>
            </div>
            <label className="relative block w-full shrink-0 sm:w-64">
              <span className="sr-only">Поиск по email или имени</span>
              <Search className="pointer-events-none absolute left-2.5 top-1/2 h-3.5 w-3.5 -translate-y-1/2 text-fg-muted" aria-hidden="true" />
              <input
                type="search"
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder="Email или имя (от 2 символов)"
                className={`${controlClass} !pl-8`}
              />
            </label>
          </div>

          {usersError ? (
            <ErrorState
              title="Не удалось загрузить пользователей"
              message={usersError}
              onRetry={() => {
                setUsersError('')
                setUsersAttempt((value) => value + 1)
              }}
            />
          ) : usersLoading ? (
            <div role="status" className="flex items-center justify-center gap-2 py-16 text-sm text-fg-muted">
              <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden="true" /> {query ? 'Поиск…' : 'Загрузка пользователей…'}
            </div>
          ) : users.length === 0 ? (
            <p className="py-16 text-center text-sm text-fg-muted">
              {query ? `Никого не найдено по запросу «${query}»` : 'Пользователей нет'}
            </p>
          ) : (
            <>
              <div className="max-h-[70vh] overflow-auto">
                <table className="w-full min-w-[860px] text-left text-sm [&_td]:!px-3 [&_th]:!px-3">
                  <thead className={tableHeadClass}>
                    <tr>
                      <th className="px-4 py-3 font-medium">ID</th>
                      <th className="px-4 py-3 font-medium">Имя</th>
                      <th className="px-4 py-3 font-medium">Email</th>
                      <th className="px-4 py-3 font-medium">Статус Email</th>
                      <th className="px-4 py-3 font-medium">Текущая роль</th>
                      <th className="px-4 py-3 font-medium">Новая роль</th>
                      <th className="px-4 py-3 font-medium" />
                    </tr>
                  </thead>
                  <tbody>
                    {users.map((item) => {
                      const isSelf = item.email === currentUser?.email
                      const selected = selectedRoles[item.id] ?? item.role
                      const changed = selected !== item.role
                      const saving = savingId === item.id
                      return (
                        <tr key={item.id} className={tableRowClass}>
                          <td className="px-4 py-3 tabular-nums text-fg-muted">{item.id}</td>
                          <td className="px-4 py-3">
                            <span className="block max-w-[140px] truncate font-medium text-fg" title={item.fullName}>
                              {item.fullName || '—'}
                            </span>
                            {isSelf && <span className="text-xs text-primary-fg">Это вы</span>}
                          </td>
                          <td className="px-4 py-3">
                            <span className="block max-w-[150px] truncate text-fg" title={item.email}>{item.email}</span>
                          </td>
                          <td className="whitespace-nowrap px-4 py-3">
                            {item.emailVerified ? (
                              <span className="inline-flex items-center gap-1 text-xs font-medium text-success-fg">
                                <BadgeCheck className="h-4 w-4" /> Подтверждён
                              </span>
                            ) : (
                              <span className="inline-flex items-center gap-1 text-xs font-medium text-warning-fg">
                                <CircleAlert className="h-4 w-4" /> Не подтверждён
                              </span>
                            )}
                          </td>
                          <td className="whitespace-nowrap px-4 py-3">
                            <RoleBadge role={item.role} />
                          </td>
                          <td className="px-4 py-3">
                            <select
                              value={selected}
                              onChange={(event) => setSelectedRoles((prev) => ({ ...prev, [item.id]: event.target.value }))}
                              disabled={isSelf || saving}
                              title={isSelf ? 'Нельзя изменить собственную роль' : undefined}
                              aria-label={`Новая роль для ${item.email}`}
                              className={`${controlClass} min-w-[130px] !py-1.5`}
                            >
                              {ROLES.map(({ value, label }) => (
                                <option key={value} value={value}>{label}</option>
                              ))}
                            </select>
                          </td>
                          <td className="whitespace-nowrap px-4 py-3">
                            <div className="flex flex-col items-stretch justify-end gap-1.5 xl:flex-row xl:items-center xl:gap-2">
                              <button
                                type="button"
                                onClick={() => handleSave(item)}
                                disabled={!changed || saving}
                                className="inline-flex h-8 items-center gap-1.5 rounded-control bg-primary px-3 text-small font-semibold text-on-primary shadow-soft transition-colors hover:bg-primary-hover focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring disabled:cursor-not-allowed disabled:bg-page-alt disabled:text-fg-muted disabled:shadow-none"
                              >
                                {saving ? <Loader2 className="h-3.5 w-3.5 animate-spin" /> : <Save className="h-3.5 w-3.5" />}
                                Сохранить
                              </button>
                              <button
                                type="button"
                                onClick={() => setDeleteTarget(item)}
                                disabled={isSelf || saving}
                                title={isSelf ? 'Нельзя удалить собственный аккаунт' : 'Удалить пользователя'}
                                className="inline-flex h-8 items-center gap-1.5 rounded-control border border-danger/40 px-3 text-small font-semibold text-danger-fg transition-colors hover:bg-danger-soft focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring disabled:cursor-not-allowed disabled:border-line disabled:text-fg-muted disabled:hover:bg-transparent"
                              >
                                <Trash2 className="h-3.5 w-3.5" />
                                Удалить
                              </button>
                            </div>
                          </td>
                        </tr>
                      )
                    })}
                  </tbody>
                </table>
              </div>
              {!usersPage.last && (
                <ShowMoreButton loading={loadingMoreUsers} onClick={loadMoreUsers} />
              )}
            </>
          )}
        </section>

        <AdminCourses />
        <AdminLogs />
      </main>

      {deleteTarget && (
        <ConfirmDialog
          title="Удалить пользователя?"
          confirmLabel="Удалить навсегда"
          loading={deleting}
          onConfirm={handleDelete}
          onCancel={cancelDelete}
        >
          Вы уверены, что хотите удалить пользователя <b className="text-fg">{deleteTarget.email}</b>?
          Все его документы будут безвозвратно удалены.
        </ConfirmDialog>
      )}

    </div>
  )
}

function ShowMoreButton({ loading, onClick }) {
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={loading}
      className="flex w-full items-center justify-center gap-2 border-t border-line-subtle py-2.5 text-sm font-medium text-primary-fg hover:bg-page-alt disabled:opacity-60"
    >
      {loading && <Loader2 className="h-4 w-4 animate-spin" />} Показать ещё
    </button>
  )
}

const tableHead = tableHeadClass

// Role name with its icon, so the role never reads by color alone.
function RoleBadge({ role }) {
  const Icon = ROLES.find((item) => item.value === role)?.icon
  return (
    <span className={`inline-flex items-center gap-1 rounded-full px-2.5 py-0.5 text-xs font-semibold ring-1 ring-inset ${ROLE_BADGE_STYLES[role] ?? DEFAULT_ROLE_BADGE_STYLE}`}>
      {Icon && <Icon className="h-3.5 w-3.5" aria-hidden="true" />}
      {roleLabel(role)}
    </span>
  )
}

/** All courses with their teacher, a page at a time; ADMIN can hand a course over to another teacher. */
function AdminCourses() {
  const { notifyError, showSuccess } = useToast()
  const [page, setPage] = useState(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [loadingMore, setLoadingMore] = useState(false)
  const [savingId, setSavingId] = useState(null)

  const fetchPage = useCallback((number) => api.get('/courses', { params: { page: number, size: COURSES_PAGE_SIZE, sort: 'title' } })
    .then(({ data }) => data), [])

  useEffect(() => {
    let cancelled = false
    fetchPage(0)
      .then((data) => {
        if (cancelled) return
        setPage(data)
        setError('')
      })
      .catch((err) => !cancelled && setError(getErrorMessage(err)))
    return () => {
      cancelled = true
    }
  }, [fetchPage, attempt])

  const loadMore = async () => {
    setLoadingMore(true)
    try {
      const next = await fetchPage(page.page + 1)
      setPage((current) => ({ ...next, content: [...current.content, ...next.content] }))
    } catch (err) {
      notifyError(err)
    } finally {
      setLoadingMore(false)
    }
  }

  const assign = async (course, teacherId) => {
    if (!teacherId) return
    setSavingId(course.id)
    try {
      const { data } = await api.patch(`/courses/${course.id}/teacher`, { teacherId: Number(teacherId) })
      setPage((current) => ({ ...current, content: current.content.map((item) => (item.id === data.id ? data : item)) }))
      showSuccess(`Курс «${data.title}» передан преподавателю ${data.teacherEmail}`)
    } catch (err) {
      notifyError(err)
    } finally {
      setSavingId(null)
    }
  }

  return (
    <section className="mt-8 overflow-hidden rounded-card border border-line bg-surface shadow-card">
      <div className="border-b border-line px-5 py-4">
        <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
          Курсы
          {page && <span className="rounded-full bg-page-alt px-2 py-0.5 text-xs font-semibold text-fg-secondary">{page.totalElements}</span>}
        </h2>
        <p className="text-xs text-fg-muted">Преподаватель управляет только своими курсами и их документами.</p>
      </div>
      {error ? (
        <ErrorState
          title="Не удалось загрузить курсы"
          message={error}
          onRetry={() => {
            setError('')
            setPage(null)
            setAttempt((value) => value + 1)
          }}
        />
      ) : page === null ? (
        <div role="status" className="flex items-center justify-center gap-2 py-10 text-sm text-fg-muted"><Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden="true" /> Загрузка курсов…</div>
      ) : page.content.length === 0 ? (
        <p className="py-10 text-center text-sm text-fg-muted">Курсов пока нет</p>
      ) : (
        <>
          {/* No overflow-x wrapper: the teacher picker's dropdown must not be clipped. */}
          <table className="w-full text-left text-sm">
            <thead className={tableHead}>
              <tr>
                <th className="px-4 py-3 font-medium">Курс</th>
                <th className="hidden px-4 py-3 font-medium sm:table-cell">Преподаватель</th>
                <th className="px-4 py-3 font-medium">Назначить</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-line-subtle">
              {page.content.map((course) => (
                <tr key={course.id} className={tableRowClass}>
                  <td className="px-4 py-3">
                    <Link to={`/courses/${course.id}`} className="font-medium text-fg hover:text-primary-fg hover:underline">
                      {course.code ? `${course.code} · ` : ''}{course.title}
                    </Link>
                    <span className="mt-0.5 block text-xs text-fg-secondary sm:hidden">
                      {course.teacherEmail ?? 'преподаватель не назначен'}
                    </span>
                  </td>
                  <td className="hidden px-4 py-3 text-fg sm:table-cell">
                    {course.teacherEmail ?? <span className="text-warning-fg">не назначен</span>}
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-2">
                      <TeacherPicker course={course} disabled={savingId === course.id} onPick={(teacherId) => assign(course, teacherId)} />
                      {savingId === course.id && <Loader2 className="h-4 w-4 shrink-0 animate-spin text-fg-muted" />}
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {!page.last && <ShowMoreButton loading={loadingMore} onClick={loadMore} />}
        </>
      )}
    </section>
  )
}

/** Searches teachers on the server (any number of them); the course's current teacher is not offered. */
function TeacherPicker({ course, disabled, onPick }) {
  const fetchTeachers = useCallback((term) => api.get('/admin/users', {
    params: { role: ROLE_TEACHER, size: 20, ...(term ? { q: term } : {}) },
  }).then(({ data }) => data.content
    .filter((teacher) => teacher.id !== course.teacherId)
    .map((teacher) => ({ value: String(teacher.id), label: teacher.fullName ? `${teacher.fullName} (${teacher.email})` : teacher.email }))),
  [course.teacherId])

  return (
    <SearchSelect
      value={null}
      onChange={(option) => onPick(option.value)}
      fetchOptions={fetchTeachers}
      placeholder="Выбрать преподавателя…"
      searchPlaceholder="Имя или email преподавателя"
      emptyText="Преподаватели не найдены"
      ariaLabel={`Преподаватель курса ${course.title}`}
      disabled={disabled}
      className="w-full min-w-[12rem]"
    />
  )
}

const ACTION_LABELS = {
  ACCESS_DENIED: 'Отказ в доступе',
  DOCUMENT_UPLOADED: 'Загрузка документа',
  DOCUMENT_DELETED: 'Удаление документа',
  COURSE_CREATED: 'Создание курса',
  COURSE_TEACHER_CHANGED: 'Смена преподавателя',
  PROFILE_UPDATED: 'Изменение профиля',
  STUDENT_ENROLLED: 'Запись на курс',
  STUDENT_UNENROLLED: 'Отчисление с курса',
  ASSIGNMENT_CREATED: 'Создание задания',
  ASSIGNMENT_DELETED: 'Удаление задания',
  ASSIGNMENT_CLOSED: 'Закрытие задания',
  ASSIGNMENT_PUBLISHED: 'Публикация заданий',
  ASSIGNMENT_REOPENED: 'Повторное открытие задания',
  ASSIGNMENT_DEADLINE_CHANGED: 'Перенос дедлайна',
  PII_ORIGINAL_VIEWED: 'Просмотр оригинала с личными данными',
  PII_ORIGINAL_ALLOWED: 'Студентам разрешён оригинал',
  PII_ORIGINAL_DENIED: 'Студентам закрыт оригинал',
  DOCUMENT_CATEGORY_SET: 'Тип документа указан вручную',
  OCR_RETRY_REQUESTED: 'Повторное распознавание скана',
  ROLE_CHANGED: 'Смена роли',
  USER_DELETED: 'Удаление пользователя',
  DOCUMENT_CLASSIFIED: 'Классификация документа',
  BATCH_IMPORTED: 'Пакетный импорт',
  TASK_SUBMITTED: 'Сдача решения',
  TASK_GRADED: 'Оценка работы',
  TASK_UNGRADED: 'Снятие оценки',
  REMINDERS_RUN: 'Запуск напоминаний о сроках',
  COURSE_INVITE_REGENERATED: 'Новая ссылка на курс',
  COURSE_JOIN_FAILED: 'Неудачная запись по ссылке',
  TEACHER_REQUEST_APPROVED: 'Заявка преподавателя одобрена',
  TEACHER_REQUEST_REJECTED: 'Заявка преподавателя отклонена',
  LOGIN_LOCKED: 'Блокировка входа',
}

/** System log: access denials and changes to users, courses, enrollments, documents and assignments. */
function AdminLogs() {
  const [page, setPage] = useState(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)

  // Loading is switched on by the buttons (and initially), not here: no synchronous setState in the effect.
  const fetchPage = useCallback((number) => api.get('/admin/logs', { params: { page: number, size: 50 } })
    .then(({ data }) => setPage((current) => (number === 0 ? data : { ...data, content: [...current.content, ...data.content] })))
    .catch((err) => setError(getErrorMessage(err)))
    .finally(() => setLoading(false)), [])

  useEffect(() => {
    fetchPage(0)
  }, [fetchPage])

  const load = (number) => {
    setLoading(true)
    fetchPage(number)
  }

  return (
    <section className="mt-8 overflow-hidden rounded-card border border-line bg-surface shadow-card">
      <div className="flex items-center justify-between gap-3 border-b border-line px-5 py-4">
        <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
          <ScrollText className="h-4 w-4 text-primary-fg" /> Системный журнал
        </h2>
        <button type="button" onClick={() => load(0)} disabled={loading} className="text-xs font-medium text-primary-fg hover:underline disabled:opacity-60">Обновить</button>
      </div>
      {error ? (
        <div className="p-4"><Alert>{error}</Alert></div>
      ) : !page ? (
        <div role="status" className="flex items-center justify-center gap-2 py-10 text-sm text-fg-muted"><Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden="true" /> Загрузка журнала…</div>
      ) : page.content.length === 0 ? (
        <p className="py-10 text-center text-sm text-fg-muted">Событий пока нет</p>
      ) : (
        <>
          <div className="max-h-[70vh] overflow-auto">
            <table className="w-full min-w-[760px] text-left text-sm">
              <thead className={tableHead}>
                <tr>
                  <th className="px-4 py-3 font-medium">Время</th>
                  <th className="px-4 py-3 font-medium">Пользователь</th>
                  <th className="px-4 py-3 font-medium">Событие</th>
                  <th className="px-4 py-3 font-medium">Подробности</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-line-subtle">
                {page.content.map((entry) => (
                  <tr key={entry.id} className={tableRowClass}>
                    <td className="whitespace-nowrap px-4 py-2.5 tabular-nums text-fg-muted">{formatDateTime(entry.createdAt)}</td>
                    <td className="max-w-[200px] truncate px-4 py-2.5 text-fg" title={entry.actorEmail}>{entry.actorEmail ?? '—'}</td>
                    <td className="whitespace-nowrap px-4 py-2.5">
                      <span className={`rounded-full px-2 py-0.5 text-xs font-semibold ${entry.action === 'ACCESS_DENIED'
                        ? 'bg-danger-soft text-danger-fg'
                        : 'bg-page-alt text-fg'}`}
                      >
                        {ACTION_LABELS[entry.action] ?? entry.action}
                      </span>
                    </td>
                    <td className="px-4 py-2.5 text-fg-secondary">{entry.details}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!page.last && (
            <button type="button" onClick={() => load(page.page + 1)} disabled={loading} className="flex w-full items-center justify-center gap-2 border-t border-line-subtle py-2.5 text-sm font-medium text-primary-fg hover:bg-page-alt disabled:opacity-60">
              {loading && <Loader2 className="h-4 w-4 animate-spin" />} Показать ещё
            </button>
          )}
        </>
      )}
    </section>
  )
}
