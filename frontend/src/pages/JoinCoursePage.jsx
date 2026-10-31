import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { GraduationCap, Loader2, ShieldAlert } from 'lucide-react'
import api, { getErrorMessage } from '../api/axios'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { ROLE_STUDENT } from '../constants/roles'
import Navbar from '../components/Navbar'

/**
 * /join/:code - the invitation link a teacher shares. A logged-in student is enrolled (POST /courses/join) and taken
 * to the course; a teacher or ADMIN gets an explanation. Logged-out users are sent to /login by ProtectedRoute and
 * come back here afterwards.
 */
export default function JoinCoursePage() {
  const { code } = useParams()
  const { role } = useAuth()
  const navigate = useNavigate()
  const { showSuccess } = useToast()
  const [error, setError] = useState(null) // { status, message }
  const started = useRef(false) // StrictMode runs effects twice in dev; join once.
  const isStudent = role === ROLE_STUDENT

  useEffect(() => {
    if (!isStudent || started.current) return
    started.current = true
    api.post('/courses/join', { code })
      .then(({ data }) => {
        showSuccess('Вы записаны на курс')
        navigate(`/courses/${data.courseId}`, { replace: true })
      })
      .catch((err) => setError({ status: err.response?.status, message: getErrorMessage(err) }))
  }, [code, isStudent, navigate, showSuccess])

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto mt-16 flex max-w-md flex-col items-center px-4 text-center">
        {!isStudent ? (
          <Message
            icon={GraduationCap}
            title="Ссылка для студентов"
            text="По ссылке-приглашению на курс записываются только студенты. Свои курсы вы найдёте в разделе «Мои курсы»."
          />
        ) : error ? (
          <Message
            icon={ShieldAlert}
            title={error.status === 404 ? 'Курс не найден' : error.status === 429 ? 'Слишком много попыток' : 'Не удалось записаться'}
            text={error.status === 404
              ? 'Ссылка неверна или больше не действует: преподаватель мог создать новую. Попросите у него актуальную ссылку.'
              : error.message}
          />
        ) : (
          <p className="flex items-center gap-2 text-sm text-fg-muted">
            <Loader2 className="h-4 w-4 animate-spin" /> Записываем на курс…
          </p>
        )}
      </main>
    </div>
  )
}

function Message({ icon: Icon, title, text }) {
  return (
    <>
      <Icon className="h-10 w-10 text-fg-muted" />
      <h1 className="mt-3 text-lg font-semibold text-fg">{title}</h1>
      <p className="mt-1 text-sm text-fg-muted">{text}</p>
      <Link to="/courses" className="mt-4 rounded-control bg-primary px-3.5 py-2 text-sm font-semibold text-on-primary transition hover:bg-primary-hover">
        Мои курсы
      </Link>
    </>
  )
}
