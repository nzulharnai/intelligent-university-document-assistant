import { AlertTriangle, ScanText } from 'lucide-react'
import { managesDocument } from '../utils/privacy'
import Badge from './ui/Badge'

/**
 * US-07, text recognition of a scan. The course teacher and ADMIN see "Нужна проверка" when it failed; everyone
 * sees "Распознано со скана" (the text may contain OCR errors). While it runs, DocumentAiBadge shows the progress.
 */
export default function OcrBadge({ doc, size = 'small' }) {
  const badgeSize = size === 'large' ? 'md' : 'sm'
  const manager = managesDocument(doc)

  if (manager && doc?.ocrStatus === 'FAILED') {
    return (
      <Badge
        tone="warning"
        size={badgeSize}
        icon={AlertTriangle}
        title="Не удалось распознать текст скана. Проверьте файл или загрузите версию с текстовым слоем."
      >
        Нужна проверка
      </Badge>
    )
  }
  if (doc?.textFromOcr) {
    return (
      <Badge tone="accent" size={badgeSize} icon={ScanText} title="Текст получен распознаванием скана и может содержать ошибки">
        Распознано со скана
      </Badge>
    )
  }
  return null
}
