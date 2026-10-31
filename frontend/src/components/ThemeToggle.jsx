import { Moon, Sun } from 'lucide-react'
import { useTheme } from '../context/ThemeContext'

// Shows the Sun in the light theme and the Moon in the dark one; click switches to the other theme.
export default function ThemeToggle({ className = '' }) {
  const { isDark, toggleTheme } = useTheme()
  const label = isDark ? 'Включить светлую тему' : 'Включить тёмную тему'

  return (
    <button
      type="button"
      onClick={toggleTheme}
      title={label}
      aria-label={label}
      className={`flex h-9 w-9 shrink-0 items-center justify-center rounded-control text-fg-muted transition-colors hover:bg-page-alt hover:text-fg focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring ${className}`}
    >
      {isDark ? <Moon className="h-4.5 w-4.5" /> : <Sun className="h-4.5 w-4.5" />}
    </button>
  )
}
