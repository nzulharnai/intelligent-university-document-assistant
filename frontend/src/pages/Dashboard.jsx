import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, BadgeCheck, BookOpen, CircleAlert, FileUp, Files, FolderLock, GraduationCap, Hourglass, LockKeyhole, Mail, Plus, ShieldCheck, User } from 'lucide-react'
import api, { describeError } from '../api/axios'
import ErrorState from '../components/ErrorState'
import Badge from '../components/ui/Badge'
import EmptyState from '../components/ui/EmptyState'
import Skeleton from '../components/ui/Skeleton'
import { linkClass } from '../components/ui/styles'
import { useAuth } from '../context/AuthContext'
import { ROLES, ROLE_ADMIN, ROLE_STUDENT, roleLabel } from '../constants/roles'
import Navbar from '../components/Navbar'
import AiChat from '../components/AiChat'
import MyTasks from '../components/MyTasks'
import UpcomingDeadlines from '../components/UpcomingDeadlines'

const CHAT_SUGGESTIONS = ['Какие дедлайны на этой неделе?', 'Какие лабораторные нужно сдать?', 'Как выставляется итоговая оценка?']

// What each role's dashboard says about access, after the core rule.
const ACCESS_TEXT = {
  ROLE_STUDENT: { files: 'Документы ваших курсов', access: 'Только ваши курсы', hint: 'Курсы, на которые вы записаны, и их документы' },
  ROLE_TEACHER: { files: 'Документы ваших курсов', access: 'Ваши курсы', hint: 'Вы управляете курсами, которые ведёте, и их документами' },
  ROLE_ADMIN: { files: 'Все документы системы', access: 'Вся система', hint: 'Пользователи, курсы, роли и системный журнал' },
}

export default function Dashboard() {
  const { user } = useAuth()
  const isStudent = user.role === ROLE_STUDENT
  const isAdmin = user.role === ROLE_ADMIN
  const access = ACCESS_TEXT[user.role] ?? ACCESS_TEXT.ROLE_STUDENT
  const [documentCount, setDocumentCount] = useState(null) // null while loading, undefined if the request failed
  // A status change in "Мои задачи": shown in "Ближайшие дедлайны" at once, then that list is reloaded.
  const [taskStatuses, setTaskStatuses] = useState({})
  const [deadlinesVersion, setDeadlinesVersion] = useState(0)
  const onTaskStatusChanged = (task) => {
    setTaskStatuses((current) => ({ ...current, [task.id]: task.status }))
    setDeadlinesVersion((value) => value + 1)
  }

  useEffect(() => {
    let cancelled = false
    api.get('/documents', { params: { size: 1 } })
      .then(({ data }) => !cancelled && setDocumentCount(data.totalElements))
      .catch(() => !cancelled && setDocumentCount(undefined))
    return () => {
      cancelled = true
    }
  }, [])

  const RoleIcon = ROLES.find((role) => role.value === user.role)?.icon ?? User
  const initials = (user.fullName || user.email)
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('')

  return (
    <div className="min-h-screen bg-page">
      <Navbar />

      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6 sm:py-10">
        <section className="grid grid-cols-1 gap-5 lg:grid-cols-[minmax(0,1fr)_auto] lg:items-end">
            <div>
              <Badge tone="primary" size="md" icon={ShieldCheck}>Защищённая рабочая среда</Badge>
              <h1 className="mt-3 text-[1.5rem] leading-8 font-semibold text-fg sm:text-title">Добро пожаловать в UniDocs Assistant</h1>
              <p className="mt-2 max-w-2xl text-body text-fg-secondary">
                {isStudent
                  ? 'Материалы ваших курсов, дедлайны и ИИ-ассистент, который отвечает только по документам курсов, на которые вы записаны.'
                  : isAdmin
                    ? 'Управление всей системой: пользователи, роли, курсы и системный журнал.'
                    : 'Ваши курсы, их документы, студенты и задания. ИИ находит дедлайны в силлабусах и выдаёт их студентам курса.'}
              </p>
            </div>
            <div className="flex items-center gap-3 rounded-card border border-line bg-surface p-3 pr-4 shadow-soft">
              <span className="flex h-11 w-11 items-center justify-center rounded-full bg-primary text-sm font-bold text-on-primary">
                {initials}
              </span>
              <div className="min-w-0">
                <p className="max-w-44 truncate text-sm font-semibold text-fg">{user.fullName || user.email}</p>
                <p className="mt-0.5 flex items-center gap-1.5 text-xs text-success-fg">
                  <BadgeCheck className="h-3.5 w-3.5" aria-hidden="true" />
                  Профиль активен
                </p>
              </div>
            </div>
        </section>
        {isStudent && user.teacherRequested && (
          <p className="mt-6 flex items-center gap-2 rounded-card border border-warning/30 bg-warning-soft px-4 py-3 text-sm text-warning-fg" role="status">
            <Hourglass className="h-4 w-4 shrink-0" aria-hidden="true" />
            Заявка на роль преподавателя на рассмотрении. После подтверждения деканатом войдите в систему заново.
          </p>
        )}

        <section className="mt-8 grid gap-4 md:grid-cols-3">
          <StatusCard icon={Files} iconClass="bg-primary-soft text-primary" label="Всего файлов" value={documentCount === null ? '…' : documentCount ?? '—'} description={access.files} />
          <StatusCard icon={LockKeyhole} iconClass="bg-success-soft text-success" label="Доступ" value={access.access} description={access.hint} />
          <StatusCard icon={RoleIcon} iconClass="bg-accent-soft text-accent-fg" label="Текущие права доступа" value={roleLabel(user.role)} description="Доступ определяется вашей ролью" />
        </section>

        <h2 className="mt-10 text-small font-semibold uppercase tracking-wide text-fg-muted">Быстрые действия</h2>
        <section className="mt-3 grid gap-4 sm:grid-cols-3">
          <QuickAction to="/courses" icon={GraduationCap} title={isAdmin ? 'Все курсы' : 'Мои курсы'} description="Документы, задания и чат с ИИ" />
          <QuickAction to="/documents" icon={FolderLock} title="Хранилище" description={access.files} />
          {isStudent ? (
            <QuickAction to="/courses" icon={Plus} title="Записаться на курс" description="По ссылке-приглашению от преподавателя" />
          ) : isAdmin ? (
            <QuickAction to="/admin" icon={ShieldCheck} title="Панель деканата" description="Пользователи, роли, курсы, журнал" />
          ) : (
            <QuickAction to="/documents" icon={FileUp} title="Загрузить документ" description="В один из ваших курсов" />
          )}
        </section>

        {isStudent ? (
          <div className="mt-10 grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <div className="space-y-6">
              <UpcomingDeadlines refreshKey={deadlinesVersion} statusOverrides={taskStatuses} />
              <MyTasks onStatusChanged={onTaskStatusChanged} />
            </div>
            <div className="space-y-6">
              <MyCourses title="Мои курсы" empty="Вы пока не записаны ни на один курс." />
              <AiChat
                endpoint="/ai/ask"
                title="Чат с ИИ по моим курсам"
                subtitle="Ответы только по документам курсов, на которые вы записаны"
                placeholder="Например: когда сдавать Practice 04?"
                suggestions={CHAT_SUGGESTIONS}
                showDocuments
              />
            </div>
          </div>
        ) : !isAdmin && (
          <div className="mt-10 grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
            <MyCourses title="Мои курсы" empty="Вы пока не ведёте курсов. Создайте курс на странице «Курсы»." />
            <AiChat
              endpoint="/ai/ask"
              title="Чат с ИИ по моим курсам"
              subtitle="Поиск по документам курсов, которые вы ведёте"
              placeholder="Например: какие темы в силлабусе?"
              suggestions={CHAT_SUGGESTIONS}
              showDocuments
            />
          </div>
        )}

        <section id="profile" className="mt-10 overflow-hidden rounded-card border border-line bg-surface shadow-card">
          <div className="h-20 bg-gradient-to-r from-primary-soft via-accent-soft to-page-alt" aria-hidden="true" />
          <div className="px-5 pb-6 sm:px-6">
            <div className="-mt-10 flex h-20 w-20 items-center justify-center rounded-full border-4 border-surface bg-primary text-2xl font-bold text-on-primary shadow-soft">
              {initials}
            </div>
            <h2 className="mt-3 text-section text-fg">{user.fullName}</h2>

            <dl className="mt-5 grid gap-4 sm:grid-cols-3">
              <InfoItem icon={Mail} label="Email" value={user.email} />
              <InfoItem icon={RoleIcon} label="Роль" value={roleLabel(user.role)} />
              {user.emailVerified !== undefined && (
                <InfoItem
                  icon={user.emailVerified ? BadgeCheck : CircleAlert}
                  label="Email подтверждён"
                  value={user.emailVerified ? 'Да' : 'Нет'}
                />
              )}
            </dl>
          </div>
        </section>
      </main>
    </div>
  )
}

function MyCourses({ title, empty }) {
  const [courses, setCourses] = useState(null)
  const [error, setError] = useState('')
  const [attempt, setAttempt] = useState(0)

  useEffect(() => {
    let cancelled = false
    api.get('/courses', { params: { size: 6 } })
      .then(({ data }) => {
        if (cancelled) return
        setCourses(data)
        setError('')
      })
      .catch((err) => !cancelled && setError(describeError(err).message))
    return () => {
      cancelled = true
    }
  }, [attempt])

  return (
    <section className="rounded-card border border-line bg-surface p-5 shadow-card sm:p-6">
      <div className="flex items-center justify-between gap-3">
        <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
          <BookOpen className="h-5 w-5 text-primary" aria-hidden="true" /> {title}
        </h2>
        <Link to="/courses" className={`${linkClass} text-small`}>Все курсы</Link>
      </div>
      {error ? (
        <ErrorState
          title="Не удалось загрузить курсы"
          message={error}
          onRetry={() => {
            setError('')
            setCourses(null)
            setAttempt((value) => value + 1)
          }}
        />
      ) : courses === null ? (
        <div role="status" className="mt-4 space-y-2">
          <span className="sr-only">Загрузка…</span>
          {[0, 1, 2].map((index) => <Skeleton key={index} className="h-11 w-full" rounded="rounded-control" />)}
        </div>
      ) : courses.content.length === 0 ? (
        <EmptyState compact icon={GraduationCap} title={empty} className="mt-4" />
      ) : (
        <ul className="mt-4 space-y-2">
          {courses.content.map((course) => (
            <li key={course.id}>
              <Link to={`/courses/${course.id}`} className="group flex items-center gap-3 rounded-control border border-line px-3 py-2.5 transition-colors hover:border-primary/40 hover:bg-page focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring">
                {course.code && <span className="rounded-md bg-primary-soft px-1.5 py-0.5 text-xs font-bold tabular-nums text-primary-fg">{course.code}</span>}
                <span className="min-w-0 flex-1 truncate text-sm font-medium text-fg">{course.title}</span>
                <ArrowRight className="h-4 w-4 shrink-0 text-fg-muted transition-transform group-hover:translate-x-1 group-hover:text-primary" aria-hidden="true" />
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function InfoItem({ icon: Icon, label, value }) {
  return (
    <div className="flex items-start gap-3 rounded-control bg-page p-4">
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-control bg-surface text-primary shadow-soft">
        <Icon className="h-4.5 w-4.5" aria-hidden="true" />
      </span>
      <div className="min-w-0">
        <dt className="text-xs font-medium uppercase tracking-wide text-fg-muted">{label}</dt>
        <dd className="mt-0.5 truncate text-sm font-medium text-fg">{value}</dd>
      </div>
    </div>
  )
}

function StatusCard({ icon: Icon, iconClass, label, value, description }) {
  return (
    <div className="rounded-card border border-line bg-surface p-5 shadow-soft">
      <span className={`flex h-10 w-10 items-center justify-center rounded-control ${iconClass}`}>
        <Icon className="h-5 w-5" aria-hidden="true" />
      </span>
      <p className="mt-4 text-xs font-semibold uppercase tracking-wide text-fg-muted">{label}</p>
      <p className="mt-1 text-lg font-semibold tabular-nums text-fg">{value}</p>
      <p className="mt-1 text-small text-fg-muted">{description}</p>
    </div>
  )
}

function QuickAction({ to, icon: Icon, title, description }) {
  return (
    <Link
      to={to}
      className="group flex items-start gap-3 rounded-card border border-line bg-surface p-4 shadow-soft transition duration-200 hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-card focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring motion-reduce:hover:translate-y-0"
    >
      <span className="flex h-9 w-9 shrink-0 items-center justify-center rounded-control bg-primary-soft text-primary transition-colors group-hover:bg-primary group-hover:text-on-primary">
        <Icon className="h-4.5 w-4.5" aria-hidden="true" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block text-sm font-semibold text-fg">{title}</span>
        <span className="mt-0.5 block text-small text-fg-muted">{description}</span>
      </span>
      <ArrowRight className="mt-2 h-4 w-4 shrink-0 text-fg-muted transition-transform duration-200 group-hover:translate-x-1 group-hover:text-primary" aria-hidden="true" />
    </Link>
  )
}
