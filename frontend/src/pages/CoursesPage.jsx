import { useCallback, useEffect, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { ArrowRight, BookOpen, GraduationCap, Plus, Search, UserRound } from 'lucide-react'
import api from '../api/axios'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { ROLE_ADMIN, ROLE_STUDENT } from '../constants/roles'
import Navbar from '../components/Navbar'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import PageHeader from '../components/ui/PageHeader'
import Skeleton from '../components/ui/Skeleton'
import { cardClass, controlClass as inputClass } from '../components/ui/styles'

const PAGE_SIZE = 12

/** "My courses": enrolled ones for a student, taught ones for a teacher, all for ADMIN (GET /courses). */
export default function CoursesPage() {
  const { role } = useAuth()
  const { notifyError } = useToast()
  const isStudent = role === ROLE_STUDENT
  const [page, setPage] = useState(null)
  const [loading, setLoading] = useState(true)

  const fetchPage = useCallback((number) => api.get('/courses', { params: { page: number, size: PAGE_SIZE } })
    .then(({ data }) => data), [])

  useEffect(() => {
    let cancelled = false
    fetchPage(0)
      .then((data) => !cancelled && setPage(data))
      .catch((err) => !cancelled && notifyError(err))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [fetchPage, notifyError])

  const loadMore = async () => {
    setLoading(true)
    try {
      const next = await fetchPage(page.page + 1)
      setPage({ ...next, content: [...page.content, ...next.content] })
    } catch (err) {
      notifyError(err)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto max-w-6xl px-4 py-8 sm:px-6 sm:py-10">
        <PageHeader
          icon={GraduationCap}
          title="Курсы"
          subtitle={isStudent ? 'Курсы, на которые вы записаны' : role === ROLE_ADMIN ? 'Все курсы системы' : 'Курсы, которые вы ведёте'}
        />

        <div className="mt-8 grid grid-cols-1 gap-6 lg:grid-cols-[minmax(0,1fr)_20rem]">
          <section aria-label="Список курсов">
            {!page && loading ? (
              <div role="status" className="grid gap-4 sm:grid-cols-2">
                <span className="sr-only">Загрузка курсов…</span>
                {[0, 1, 2, 3].map((index) => <Skeleton key={index} className="h-36 w-full" rounded="rounded-card" />)}
              </div>
            ) : page?.content.length === 0 ? (
              <EmptyState
                icon={BookOpen}
                title="Курсов пока нет"
                description={isStudent ? 'Попросите преподавателя прислать ссылку-приглашение и откройте её или вставьте в форму справа.' : 'Создайте первый курс в форме справа.'}
                className="bg-surface"
              />
            ) : page && (
              <>
                <ul className="grid gap-4 sm:grid-cols-2">
                  {page.content.map((course) => <CourseCard key={course.id} course={course} showNumber={role === ROLE_ADMIN} />)}
                </ul>
                {!page.last && (
                  <Button variant="outline" onClick={loadMore} loading={loading} className="mt-4 w-full">
                    Показать ещё
                  </Button>
                )}
              </>
            )}
          </section>

          <aside className="space-y-4">
            {isStudent ? <JoinByLinkForm /> : <CreateCourseForm />}
          </aside>
        </div>
      </main>
    </div>
  )
}

function CourseCard({ course, showNumber }) {
  return (
    <li>
      <Link
        to={`/courses/${course.id}`}
        className="group relative flex h-full flex-col overflow-hidden rounded-card border border-line bg-surface p-5 pt-6 shadow-soft transition duration-200 hover:-translate-y-0.5 hover:border-primary/40 hover:shadow-card focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring motion-reduce:hover:translate-y-0"
      >
        <span className="absolute inset-x-0 top-0 h-1 bg-primary" aria-hidden="true" />
        <span className="flex items-center gap-2">
          {course.code && (
            <span className="rounded-md bg-primary-soft px-1.5 py-0.5 text-xs font-bold tabular-nums text-primary-fg">{course.code}</span>
          )}
          {showNumber && <span className="text-xs tabular-nums text-fg-muted">№ {course.id}</span>}
        </span>
        <span className="mt-2.5 text-base font-semibold leading-6 text-fg">{course.title}</span>
        {course.description && <span className="mt-1 line-clamp-2 text-small text-fg-muted">{course.description}</span>}
        <span className="mt-auto flex items-center justify-between gap-2 border-t border-line-subtle pt-3 text-small text-fg-secondary">
          <span className="flex min-w-0 items-center gap-1.5">
            <UserRound className="h-3.5 w-3.5 shrink-0 text-fg-muted" aria-hidden="true" />
            <span className="truncate">{course.teacherFullName || course.teacherEmail || 'Преподаватель не назначен'}</span>
          </span>
          <ArrowRight className="h-4 w-4 shrink-0 text-fg-muted transition-transform group-hover:translate-x-1 group-hover:text-primary" aria-hidden="true" />
        </span>
      </Link>
    </li>
  )
}

function CreateCourseForm() {
  const navigate = useNavigate()
  const { notifyError, showSuccess } = useToast()
  const [form, setForm] = useState({ title: '', code: '', description: '' })
  const [saving, setSaving] = useState(false)

  const submit = async (event) => {
    event.preventDefault()
    setSaving(true)
    try {
      const { data } = await api.post('/courses', {
        title: form.title.trim(),
        code: form.code.trim() || null,
        description: form.description.trim() || null,
      })
      showSuccess(`Курс «${data.title}» создан`)
      navigate(`/courses/${data.id}`)
    } catch (err) {
      notifyError(err)
      setSaving(false)
    }
  }

  const update = (field) => (event) => setForm((current) => ({ ...current, [field]: event.target.value }))

  return (
    <form onSubmit={submit} className={`${cardClass} p-5`}>
      <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
        <Plus className="h-4.5 w-4.5 text-primary" aria-hidden="true" /> Новый курс
      </h2>
      <div className="mt-4 space-y-3">
        <input value={form.title} onChange={update('title')} required maxLength={255} placeholder="Название, например «Алгоритмы»" className={inputClass} aria-label="Название курса" />
        <input value={form.code} onChange={update('code')} maxLength={50} placeholder="Код, например CS101" className={inputClass} aria-label="Код курса" />
        <textarea value={form.description} onChange={update('description')} rows={3} maxLength={10000} placeholder="Описание (необязательно)" className={`${inputClass} resize-none`} aria-label="Описание курса" />
        <Button type="submit" loading={saving} disabled={!form.title.trim()} className="w-full">
          Создать курс
        </Button>
      </div>
    </form>
  )
}

// The code of an invitation link: the part after /join/ of a pasted link, or the code itself.
function inviteCodeFrom(input) {
  const value = input.trim()
  const match = value.match(/\/join\/([^/?#\s]+)/)
  return match ? match[1] : value.replace(/[/?#\s].*$/, '')
}

// Students join only through the teacher's invitation link (/join/:code); course numbers are not enough.
function JoinByLinkForm() {
  const navigate = useNavigate()
  const [link, setLink] = useState('')
  const code = inviteCodeFrom(link)

  return (
    <form
      onSubmit={(event) => {
        event.preventDefault()
        if (code) navigate(`/join/${encodeURIComponent(code)}`)
      }}
      className={`${cardClass} p-5`}
    >
      <h2 className="flex items-center gap-2 text-base font-semibold text-fg">
        <Search className="h-4.5 w-4.5 text-primary" aria-hidden="true" /> Записаться на курс
      </h2>
      <p className="mt-1 text-small text-fg-muted">
        Откройте ссылку-приглашение от преподавателя или вставьте её сюда.
      </p>
      <div className="mt-3 flex gap-2">
        <input
          value={link}
          onChange={(event) => setLink(event.target.value)}
          placeholder="Ссылка-приглашение"
          className={inputClass}
          aria-label="Ссылка-приглашение"
        />
        <Button type="submit" disabled={!code}>
          Записаться
        </Button>
      </div>
    </form>
  )
}
