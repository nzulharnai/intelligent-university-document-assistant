import { useCallback, useEffect, useId, useRef, useState } from 'react'
import { Link, NavLink, useLocation, useNavigate } from 'react-router-dom'
import { Bell, Building2, CheckCheck, FileText, FolderLock, GraduationCap, LayoutDashboard, Loader2, LogOut, Menu, Moon, Sun, User, UserRound, X } from 'lucide-react'
import api from '../api/axios'
import { useAuth } from '../context/AuthContext'
import { useTheme } from '../context/ThemeContext'
import NotificationItem from './NotificationItem'
import ProfileDialog from './ProfileDialog'
import { NOTIFICATIONS_CHANGED, markNotificationRead } from '../utils/notifications'
import { DEFAULT_ROLE_BADGE_STYLE, ROLES, ROLE_ADMIN, ROLE_BADGE_STYLES, roleLabel as labelOfRole } from '../constants/roles'

const NAV_ITEMS = [
  { to: '/dashboard', label: 'Панель', icon: LayoutDashboard },
  { to: '/documents', label: 'Документы', icon: FolderLock },
  { to: '/courses', label: 'Курсы', icon: GraduationCap },
  { to: '/admin', label: 'Деканат', icon: Building2, adminOnly: true },
]

const FOCUSABLE = 'a[href], button:not([disabled])'

const UNREAD_POLL_MS = 60_000

/**
 * A disclosure panel (burger menu, user menu). It closes on Escape (focus returns to its button), on a click outside,
 * when focus tabs out of it, and on navigation: it is tied to the location it was opened at. Opening moves focus to the
 * first item of the panel.
 */
function usePopover() {
  const location = useLocation()
  const [openAt, setOpenAt] = useState(null)
  const open = openAt === location.key
  const rootRef = useRef(null)
  const buttonRef = useRef(null)
  const panelRef = useRef(null)

  const close = useCallback((restoreFocus) => {
    setOpenAt(null)
    if (restoreFocus) buttonRef.current?.focus()
  }, [])

  useEffect(() => {
    if (!open) return undefined
    panelRef.current?.querySelector(FOCUSABLE)?.focus()
    const onKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault()
        close(true)
      }
    }
    const onPointerDown = (event) => {
      if (!rootRef.current?.contains(event.target)) close(false)
    }
    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('pointerdown', onPointerDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('pointerdown', onPointerDown)
    }
  }, [open, close])

  return {
    open,
    rootRef,
    buttonRef,
    panelRef,
    close,
    toggle: () => (open ? close(true) : setOpenAt(location.key)),
    // Tabbing out of the menu closes it; a click on its padding (no new focus target) does not.
    onBlur: (event) => {
      if (event.relatedTarget && !rootRef.current?.contains(event.relatedTarget)) close(false)
    },
  }
}

export default function Navbar() {
  const { user, role, logout } = useAuth()
  const navigate = useNavigate()

  const items = NAV_ITEMS.filter((item) => !item.adminOnly || user?.role === ROLE_ADMIN)

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  // Desktop: the active link is underlined in blue along the bar's bottom edge.
  const navLinkClass = ({ isActive }) => (
    `-mb-px flex h-16 items-center gap-2 border-b-2 px-3 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-primary ${
      isActive ? 'border-primary text-primary-fg' : 'border-transparent text-fg-secondary hover:border-line-strong hover:text-fg'
    }`
  )

  // Burger panel: full-width rows; the active one is tinted with a blue bar on the left.
  const menuLinkClass = ({ isActive }) => (
    `flex items-center gap-3 rounded-control border-l-4 px-3 py-2.5 text-sm font-medium transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary ${
      isActive ? 'border-primary bg-primary-soft text-primary-fg' : 'border-transparent text-fg-secondary hover:bg-page-alt hover:text-fg'
    }`
  )

  return (
    <header className="sticky top-0 z-50 border-b border-line bg-surface">
      <div className="mx-auto flex h-16 max-w-6xl items-center justify-between gap-3 px-4 sm:px-6">
        <Link
          to="/dashboard"
          className="flex shrink-0 items-center gap-2.5 rounded-control focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring"
          aria-label="UniDocs Assistant"
        >
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-primary text-on-primary">
            <FileText className="h-4.5 w-4.5" aria-hidden="true" />
          </span>
          <span className="hidden text-lg font-semibold tracking-tight text-fg sm:block">UniDocs</span>
        </Link>

        <div className="flex h-full min-w-0 items-center gap-2">
          {/* lg and up: icons; the labels appear from xl. */}
          <nav aria-label="Основная навигация" className="hidden h-full items-center gap-1 lg:flex xl:gap-2">
            {items.map(({ to, label, icon: Icon }) => (
              <NavLink key={to} to={to} className={navLinkClass} aria-label={label} title={label}>
                <Icon className="h-4 w-4 shrink-0" />
                <span className="hidden xl:inline">{label}</span>
              </NavLink>
            ))}
          </nav>
          {user && <NotificationBell />}
          {user && <UserMenu user={user} role={role} onLogout={handleLogout} />}
          <BurgerMenu items={items} navLinkClass={menuLinkClass} />
        </div>
      </div>
    </header>
  )
}

/**
 * The bell: the unread count (GET /notifications/unread-count every 60 s, paused while the tab is hidden, at once
 * when it shows again) and a panel with the latest 10. A click marks one read and opens its link.
 */
function NotificationBell() {
  const panelId = useId()
  const navigate = useNavigate()
  const { open, rootRef, buttonRef, panelRef, toggle, close, onBlur } = usePopover()
  const [unread, setUnread] = useState(0)
  const [items, setItems] = useState(null)
  const [failed, setFailed] = useState(false)

  const refreshCount = useCallback(() => {
    api.get('/notifications/unread-count').then(({ data }) => setUnread(data.count)).catch(() => {})
  }, [])

  useEffect(() => {
    let timer = null
    const start = () => {
      refreshCount()
      timer = setInterval(refreshCount, UNREAD_POLL_MS)
    }
    const stop = () => {
      clearInterval(timer)
      timer = null
    }
    const onVisibility = () => {
      if (document.hidden) stop()
      else if (!timer) start()
    }
    if (!document.hidden) start()
    document.addEventListener('visibilitychange', onVisibility)
    window.addEventListener(NOTIFICATIONS_CHANGED, refreshCount)
    return () => {
      stop()
      document.removeEventListener('visibilitychange', onVisibility)
      window.removeEventListener(NOTIFICATIONS_CHANGED, refreshCount)
    }
  }, [refreshCount])

  const openPanel = () => {
    if (!open) {
      setItems(null)
      setFailed(false)
      api.get('/notifications', { params: { size: 10 } })
        .then(({ data }) => setItems(data.content))
        .catch(() => setFailed(true))
    }
    toggle()
  }

  const openItem = async (notification) => {
    const updated = await markNotificationRead(notification)
    if (!notification.readAt) setUnread((value) => Math.max(0, value - 1))
    setItems((current) => current?.map((item) => (item.id === updated.id ? updated : item)))
    close(false)
    if (notification.link) navigate(notification.link)
  }

  const readAll = async () => {
    try {
      await api.post('/notifications/read-all')
      setUnread(0)
      setItems((current) => current?.map((item) => ({ ...item, readAt: item.readAt ?? new Date().toISOString() })))
    } catch {
      // The count is fetched again on the next poll.
    }
  }

  const countLabel = unread > 99 ? '99+' : String(unread)

  return (
    <div ref={rootRef} onBlur={onBlur} className="relative">
      <button
        ref={buttonRef}
        type="button"
        onClick={openPanel}
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={unread > 0 ? `Уведомления: ${unread} непрочитанных` : 'Уведомления'}
        title="Уведомления"
        className="relative flex h-10 w-10 shrink-0 items-center justify-center rounded-full text-fg-secondary transition hover:bg-page-alt hover:text-fg focus:outline-none focus-visible:ring-4 focus-visible:ring-ring"
      >
        <Bell className="h-5 w-5" aria-hidden="true" />
        {unread > 0 && (
          <span aria-hidden="true" className="absolute -right-0.5 -top-0.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold tabular-nums text-on-danger ring-2 ring-surface">
            {countLabel}
          </span>
        )}
      </button>
      {open && (
        <div
          ref={panelRef}
          id={panelId}
          className="absolute right-0 top-full z-50 mt-2 w-[22rem] max-w-[calc(100vw-2rem)] rounded-card border border-line bg-surface shadow-card"
        >
          <div className="flex items-center justify-between gap-2 border-b border-line-subtle px-4 py-3">
            <p className="text-sm font-semibold text-fg">Уведомления</p>
            {unread > 0 && (
              <button type="button" onClick={readAll} className="inline-flex items-center gap-1 rounded-sm text-small font-medium text-primary-fg hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary">
                <CheckCheck className="h-3.5 w-3.5" aria-hidden="true" /> Прочитать все
              </button>
            )}
          </div>
          <div className="max-h-[min(70vh,28rem)] overflow-y-auto p-2">
            {failed ? (
              <p role="alert" className="px-3 py-6 text-center text-small text-fg-muted">Не удалось загрузить уведомления.</p>
            ) : items === null ? (
              <p role="status" className="flex items-center justify-center gap-2 px-3 py-6 text-small text-fg-muted">
                <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden="true" /> Загрузка…
              </p>
            ) : items.length === 0 ? (
              <p className="px-3 py-6 text-center text-small text-fg-muted">Уведомлений пока нет.</p>
            ) : (
              <ul className="space-y-0.5">
                {items.map((item) => (
                  <li key={item.id}><NotificationItem notification={item} onOpen={openItem} compact /></li>
                ))}
              </ul>
            )}
          </div>
          <div className="border-t border-line-subtle p-2">
            <Link to="/notifications" className="block rounded-control px-3 py-2 text-center text-sm font-medium text-primary-fg hover:bg-page-alt focus:outline-none focus-visible:ring-4 focus-visible:ring-ring">
              Все уведомления
            </Link>
          </div>
        </div>
      )}
    </div>
  )
}

/** Below lg: the navigation in a panel under the header. */
function BurgerMenu({ items, navLinkClass }) {
  const panelId = useId()
  const { open, rootRef, buttonRef, panelRef, close, toggle, onBlur } = usePopover()

  return (
    <div ref={rootRef} onBlur={onBlur} className="lg:hidden">
      <button
        ref={buttonRef}
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={open ? 'Закрыть меню' : 'Открыть меню'}
        title={open ? 'Закрыть меню' : 'Меню'}
        className="flex h-10 w-10 items-center justify-center rounded-control text-fg-secondary transition-colors hover:bg-page-alt hover:text-fg focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring"
      >
        {open ? <X className="h-5 w-5" /> : <Menu className="h-5 w-5" />}
      </button>
      {open && (
        <nav
          ref={panelRef}
          id={panelId}
          aria-label="Основная навигация"
          className="absolute inset-x-0 top-full border-b border-line bg-surface shadow-card"
        >
          <ul className="mx-auto max-w-6xl space-y-1 px-4 py-3 sm:px-6">
            {items.map(({ to, label, icon: Icon }) => (
              <li key={to}>
                <NavLink to={to} className={navLinkClass} onClick={() => close(false)}>
                  <Icon className="h-4 w-4 shrink-0" />
                  {label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      )}
    </div>
  )
}

/** The avatar (initials + role icon); its menu holds the name, email, role, "Профиль", the theme switch and "Выйти". */
function UserMenu({ user, role, onLogout }) {
  const panelId = useId()
  const { isDark, toggleTheme } = useTheme()
  const { open, rootRef, buttonRef, panelRef, toggle, onBlur } = usePopover()
  const [profileOpen, setProfileOpen] = useState(false)

  const roleInfo = ROLES.find((item) => item.value === role)
  const RoleIcon = roleInfo?.icon ?? User
  const roleLabel = labelOfRole(role)
  const badgeStyle = ROLE_BADGE_STYLES[role] ?? DEFAULT_ROLE_BADGE_STYLE
  const initials = (user.fullName || user.email || 'U')
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase())
    .join('')

  const itemClass = 'flex w-full items-center gap-2.5 rounded-control px-3 py-2 text-left text-sm font-medium transition-colors focus:outline-none focus-visible:ring-4 focus-visible:ring-ring'

  return (
    <div ref={rootRef} onBlur={onBlur} className="relative">
      <button
        ref={buttonRef}
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-controls={panelId}
        aria-label={`Профиль: ${user.fullName || user.email}${roleLabel ? `, ${roleLabel}` : ''}`}
        title={user.fullName || user.email}
        className="relative flex h-10 w-10 shrink-0 items-center justify-center rounded-full bg-primary-soft text-xs font-bold text-primary-fg ring-2 ring-surface transition hover:ring-line-strong focus:outline-none focus-visible:ring-4 focus-visible:ring-ring"
      >
        {initials}
        <span className={`absolute -bottom-0.5 -right-0.5 flex h-4.5 w-4.5 items-center justify-center rounded-full ${badgeStyle} !ring-2 !ring-surface`}>
          <RoleIcon className="h-2.5 w-2.5" aria-hidden="true" />
        </span>
      </button>
      {open && (
        <div
          ref={panelRef}
          id={panelId}
          className="absolute right-0 top-full z-50 mt-2 w-72 max-w-[calc(100vw-2rem)] rounded-card border border-line bg-surface p-2 shadow-card"
        >
          <div className="border-b border-line-subtle px-3 pb-3 pt-2">
            <p className="truncate text-sm font-semibold text-fg" title={user.fullName || undefined}>
              {user.fullName || user.email}
            </p>
            {user.fullName && (
              <p className="truncate text-small text-fg-secondary" title={user.email}>{user.email}</p>
            )}
            <span className={`mt-2 inline-flex items-center gap-1 rounded-full px-2 py-0.5 text-xs font-semibold ring-1 ring-inset ${badgeStyle}`}>
              <RoleIcon className="h-3 w-3" aria-hidden="true" />
              {roleLabel}
            </span>
          </div>
          <div className="space-y-0.5 pt-2">
            <button
              type="button"
              onClick={() => {
                toggle()
                setProfileOpen(true)
              }}
              className={`${itemClass} text-fg hover:bg-page-alt`}
            >
              <UserRound className="h-4 w-4 text-fg-muted" aria-hidden="true" />
              Профиль
            </button>
            <button
              type="button"
              onClick={toggleTheme}
              className={`${itemClass} text-fg hover:bg-page-alt`}
            >
              {isDark ? <Sun className="h-4 w-4 text-fg-muted" aria-hidden="true" /> : <Moon className="h-4 w-4 text-fg-muted" aria-hidden="true" />}
              {isDark ? 'Светлая тема' : 'Тёмная тема'}
            </button>
            <button
              type="button"
              onClick={onLogout}
              className={`${itemClass} text-danger-fg hover:bg-danger-soft`}
            >
              <LogOut className="h-4 w-4" aria-hidden="true" />
              Выйти
            </button>
          </div>
        </div>
      )}
      {profileOpen && <ProfileDialog onClose={() => setProfileOpen(false)} />}
    </div>
  )
}
