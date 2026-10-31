import { Navigate, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { ShieldX } from 'lucide-react'
import { useAuth } from '../context/AuthContext'
import { normalizeRole } from '../constants/roles'
import FullScreenLoader from './FullScreenLoader'
import Navbar from './Navbar'

// Without allowedRoles any logged-in user passes; otherwise the user's role must be in the list.
// Both sides are normalized, so "STUDENT" and "ROLE_STUDENT" are treated as the same role.
export default function ProtectedRoute({ allowedRoles }) {
  const { isAuthenticated, loading, role } = useAuth()
  const location = useLocation()

  if (loading) return <FullScreenLoader />
  if (!isAuthenticated) return <Navigate to="/login" replace state={{ from: location }} />
  if (allowedRoles && !allowedRoles.some((allowed) => normalizeRole(allowed) === normalizeRole(role))) {
    return <AccessDenied />
  }
  return <Outlet />
}

function AccessDenied() {
  const navigate = useNavigate()

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto flex max-w-md flex-col items-center px-4 py-20 text-center">
        <span className="flex h-16 w-16 items-center justify-center rounded-full bg-danger-soft text-danger-fg">
          <ShieldX className="h-8 w-8" />
        </span>
        <p className="mt-5 text-sm font-semibold uppercase tracking-wide text-danger-fg">Ошибка 403</p>
        <h1 className="mt-1 text-2xl font-bold text-fg">Доступ запрещён</h1>
        <p className="mt-2 text-sm text-fg-muted">
          У вашей роли нет прав для просмотра этой страницы. Если это ошибка, обратитесь в деканат.
        </p>
        <button
          type="button"
          onClick={() => navigate('/dashboard')}
          className="mt-6 rounded-control bg-primary px-4 py-2.5 text-sm font-semibold text-on-primary shadow-soft transition hover:bg-primary-hover"
        >
          Вернуться в личный кабинет
        </button>
      </main>
    </div>
  )
}
