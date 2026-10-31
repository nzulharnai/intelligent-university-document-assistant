import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import api, { TOKEN_KEY } from '../api/axios'
import { normalizeRole } from '../constants/roles'

const AuthContext = createContext(null)

// Every user object in the app carries the role as ROLE_*, whatever form the server sent.
const withNormalizedRole = (user) => user && { ...user, role: normalizeRole(user.role) }

export function AuthProvider({ children }) {
  const [token, setToken] = useState(() => localStorage.getItem(TOKEN_KEY))
  const [user, setUser] = useState(null)
  // Only "loading" if there is a stored token that still has to be checked against the backend.
  const [loading, setLoading] = useState(() => Boolean(localStorage.getItem(TOKEN_KEY)))

  const clearSession = useCallback(() => {
    localStorage.removeItem(TOKEN_KEY)
    setToken(null)
    setUser(null)
  }, [])

  const saveSession = useCallback((newToken, newUser) => {
    localStorage.setItem(TOKEN_KEY, newToken)
    setToken(newToken)
    setUser(withNormalizedRole(newUser))
  }, [])

  // Validate the stored token once on page load.
  useEffect(() => {
    const storedToken = localStorage.getItem(TOKEN_KEY)
    if (!storedToken) return

    let cancelled = false
    api.get('/users/me')
      .then(({ data }) => {
        if (!cancelled) setUser(withNormalizedRole(data))
      })
      .catch(() => {
        if (!cancelled) clearSession()
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [clearSession])

  // The axios interceptor fires this when any request comes back 401.
  useEffect(() => {
    window.addEventListener('auth:logout', clearSession)
    return () => window.removeEventListener('auth:logout', clearSession)
  }, [clearSession])

  const login = useCallback(async (email, password) => {
    const { data } = await api.post('/auth/login', { email, password })
    const { token: newToken, ...profile } = data
    saveSession(newToken, profile)
    return profile
  }, [saveSession])

  // Registration only creates an unverified account and emails a code; the token comes from verifyEmail.
  const register = useCallback(async ({ email, password, fullName, role }) => {
    const { data } = await api.post('/auth/register', { email, password, fullName, role })
    return data.message
  }, [])

  // Returns a callback that starts the session, so the caller can show a success state first
  // (PublicOnlyRoute redirects to /dashboard as soon as a session exists).
  const verifyEmail = useCallback(async (email, otp) => {
    const { data } = await api.post('/auth/verify-email', { email, otp })
    const { token: newToken, ...profile } = data
    return () => saveSession(newToken, profile)
  }, [saveSession])

  const resendVerification = useCallback(async (email) => {
    const { data } = await api.post('/auth/resend-verification-otp', { email })
    return data.message
  }, [])

  // Used after the Google OAuth2 redirect: we only get a token, so fetch the profile.
  const loginWithToken = useCallback(async (newToken) => {
    localStorage.setItem(TOKEN_KEY, newToken)
    try {
      const { data } = await api.get('/users/me')
      saveSession(newToken, data)
      return data
    } catch (error) {
      clearSession()
      throw error
    }
  }, [saveSession, clearSession])

  const forgotPassword = useCallback(async (email) => {
    const { data } = await api.post('/auth/forgot-password', { email })
    return data.message
  }, [])

  const verifyOtp = useCallback(async (email, otp) => {
    const { data } = await api.post('/auth/verify-otp', { email, otp })
    return data.message
  }, [])

  const resetPassword = useCallback(async (email, otp, newPassword) => {
    const { data } = await api.post('/auth/reset-password', { email, otp, newPassword })
    return data.message
  }, [])

  const logout = useCallback(() => {
    clearSession()
  }, [clearSession])

  // "Профиль": name and phone; the response is the updated profile.
  const updateProfile = useCallback(async ({ fullName, phone }) => {
    const { data } = await api.patch('/users/me', { fullName, phone })
    setUser(withNormalizedRole(data))
    return data
  }, [])

  const value = useMemo(() => ({
    user,
    token,
    loading,
    isAuthenticated: Boolean(token && user),
    // Comes from the server (login / verify-email response or /users/me) and matches the JWT "role" claim.
    role: user?.role ?? null,
    login,
    register,
    verifyEmail,
    resendVerification,
    loginWithToken,
    forgotPassword,
    verifyOtp,
    resetPassword,
    logout,
    updateProfile,
  }), [user, token, loading, login, register, verifyEmail, resendVerification, loginWithToken, forgotPassword, verifyOtp, resetPassword, logout, updateProfile])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used inside <AuthProvider>')
  }
  return context
}
