import { BookOpen, Bot, ClipboardList, FileText, GraduationCap, Lock, Presentation, Scale, ScrollText } from 'lucide-react'
import { CATEGORY_LABELS } from '../constants/documents'
import Badge from './ui/Badge'

// A tone and an icon per category, so the type never reads by color alone. Legacy TRANSCRIPT/THESIS stay neutral.
const STYLES = {
  SYLLABUS: { tone: 'primary', icon: BookOpen },
  ASSIGNMENT: { tone: 'warning', icon: ClipboardList },
  LECTURE: { tone: 'accent', icon: Presentation },
  EXAM_ANSWERS: { tone: 'danger', icon: Lock },
  REGULATION: { tone: 'success', icon: Scale },
  TRANSCRIPT: { tone: 'neutral', icon: ScrollText },
  THESIS: { tone: 'neutral', icon: GraduationCap },
  OTHER: { tone: 'neutral', icon: FileText },
}

/**
 * A document category ("Силлабус · 96%"). {@code confidence} (0..1) is shown only when present; {@code ai} marks a
 * category chosen by the AI (robot icon). EXAM_ANSWERS gets a lock: the backend always keeps such documents from
 * students.
 */
export default function CategoryBadge({ category, confidence = null, ai = false, className = '' }) {
  const label = CATEGORY_LABELS[category] ?? category
  const hidden = category === 'EXAM_ANSWERS'
  const percent = typeof confidence === 'number' ? ` · ${Math.round(confidence * 100)}%` : ''
  const style = STYLES[category] ?? STYLES.OTHER
  return (
    <Badge
      tone={style.tone}
      icon={hidden ? Lock : ai ? Bot : style.icon}
      iconLabel={hidden ? 'Скрыто от студентов' : undefined}
      className={className}
      title={hidden ? 'Скрыто от студентов' : ai ? 'Тип определён ИИ' : undefined}
    >
      {label}{percent}
    </Badge>
  )
}
