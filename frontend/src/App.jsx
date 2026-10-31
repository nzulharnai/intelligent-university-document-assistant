import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import { ToastProvider } from './context/ToastContext'
import { PublicOnlyRoute } from './components/RouteGuards'
import ProtectedRoute from './components/ProtectedRoute'
import { ALL_ROLES, ROLE_ADMIN, ROLE_TEACHER } from './constants/roles'
import AdminPage from './pages/AdminPage'
import AssignmentGradingPage from './pages/AssignmentGradingPage'
import AuthPage from './pages/AuthPage'
import CoursePage from './pages/CoursePage'
import JoinCoursePage from './pages/JoinCoursePage'
import NotificationsPage from './pages/NotificationsPage'
import CoursesPage from './pages/CoursesPage'
import Dashboard from './pages/Dashboard'
import DocumentPage from './pages/DocumentPage'
import DocumentVaultPage from './pages/DocumentVaultPage'
import OAuth2RedirectHandler from './pages/OAuth2RedirectHandler'

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <ToastProvider>
          <Routes>
            <Route element={<PublicOnlyRoute />}>
              <Route path="/login" element={<AuthPage />} />
            </Route>
            <Route path="/oauth2/redirect" element={<OAuth2RedirectHandler />} />
            <Route element={<ProtectedRoute allowedRoles={ALL_ROLES} />}>
              <Route path="/dashboard" element={<Dashboard />} />
              <Route path="/documents" element={<DocumentVaultPage />} />
              <Route path="/documents/:id" element={<DocumentPage />} />
              <Route path="/courses" element={<CoursesPage />} />
              <Route path="/courses/:id" element={<CoursePage />} />
              <Route path="/join/:code" element={<JoinCoursePage />} />
              <Route path="/notifications" element={<NotificationsPage />} />
            </Route>
            <Route element={<ProtectedRoute allowedRoles={[ROLE_TEACHER, ROLE_ADMIN]} />}>
              <Route path="/courses/:courseId/assignments/:id" element={<AssignmentGradingPage />} />
            </Route>
            <Route element={<ProtectedRoute allowedRoles={[ROLE_ADMIN]} />}>
              <Route path="/admin" element={<AdminPage />} />
            </Route>
            <Route path="/" element={<Navigate to="/dashboard" replace />} />
            <Route path="*" element={<Navigate to="/dashboard" replace />} />
          </Routes>
        </ToastProvider>
      </AuthProvider>
    </BrowserRouter>
  )
}
