import { ShieldAlert, ShieldQuestion } from 'lucide-react'
import { isPiiUnknown, managesDocument, piiTypesText } from '../utils/privacy'
import Badge from './ui/Badge'

/**
 * "Личные данные: N" for the course teacher and ADMIN; the kinds (ИИН, телефон, e-mail, номер документа) are in the
 * tooltip and read out by screen readers; "Скан не проверен на личные данные" for a scan whose text is not recognized.
 * Nothing for students, or when the text has no personal data.
 */
export default function PiiBadge({ doc, size = 'small' }) {
  if (!managesDocument(doc)) return null
  const badgeSize = size === 'large' ? 'md' : 'sm'
  if (!doc?.piiCount && isPiiUnknown(doc)) {
    return (
      <Badge
        tone="warning"
        size={badgeSize}
        icon={ShieldQuestion}
        title="Текст скана не распознан, личные данные в нём не проверены. Студенты не откроют оригинал, пока вы не разрешите."
      >
        Скан не проверен на личные данные
      </Badge>
    )
  }
  if (!doc?.piiCount) return null
  const types = piiTypesText(doc.piiTypes)
  const hint = `Скрыто в тексте для студентов, ИИ, поиска и ассистента${types ? `: ${types}` : ''}`
  return (
    <Badge tone="primary" size={badgeSize} icon={ShieldAlert} title={hint}>
      Личные данные: {doc.piiCount}
      {types && <span className="sr-only"> ({types})</span>}
    </Badge>
  )
}
