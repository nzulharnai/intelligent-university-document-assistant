import { Building2, GraduationCap, Presentation } from 'lucide-react'
import { BADGE_TONES } from '../components/ui/styles'

// Same names as the backend Role enum and the "role" claim in the JWT.
export const ROLE_STUDENT = 'ROLE_STUDENT'
export const ROLE_TEACHER = 'ROLE_TEACHER'
export const ROLE_ADMIN = 'ROLE_ADMIN'

export const ROLES = [
  { value: ROLE_STUDENT, label: 'Студент', icon: GraduationCap },
  { value: ROLE_TEACHER, label: 'Преподаватель', icon: Presentation },
  { value: ROLE_ADMIN, label: 'Деканат', icon: Building2 },
]

export const ALL_ROLES = ROLES.map((role) => role.value)

// Names an old backend build or a stored session may still carry.
const LEGACY_ROLES = { ROLE_FACULTY: ROLE_TEACHER }

// Brings any role string to the ROLE_* form: "STUDENT" -> "ROLE_STUDENT", "ROLE_FACULTY" -> "ROLE_TEACHER".
export function normalizeRole(role) {
  if (!role) return null
  const upper = String(role).trim().toUpperCase()
  const prefixed = upper.startsWith('ROLE_') ? upper : `ROLE_${upper}`
  return LEGACY_ROLES[prefixed] ?? prefixed
}

export const ROLE_LABELS = Object.fromEntries(ROLES.map((role) => [role.value, role.label]))

// Role badge colours (Navbar, admin users table); the role icon always goes with them.
export const ROLE_BADGE_STYLES = {
  ROLE_STUDENT: `${BADGE_TONES.primary} ring-current/15`,
  ROLE_TEACHER: `${BADGE_TONES.warning} ring-current/15`,
  ROLE_ADMIN: `${BADGE_TONES.neutral} ring-current/25`,
}
export const DEFAULT_ROLE_BADGE_STYLE = `${BADGE_TONES.neutral} ring-current/15`

/** The role's Russian name; never the ROLE_* code (an unknown role reads "Неизвестная роль"). */
export const roleLabel = (role) => ROLE_LABELS[role] ?? 'Неизвестная роль'
