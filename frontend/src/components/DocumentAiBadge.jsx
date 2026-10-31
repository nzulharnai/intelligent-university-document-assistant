import { HelpCircle, Loader2 } from 'lucide-react'
import CategoryBadge from './CategoryBadge'
import Badge from './ui/Badge'

const Spinner = (props) => <Loader2 {...props} className="animate-spin" />

/**
 * A document's category as the AI left it: a spinner while it is being classified (only teachers and ADMIN receive
 * such documents), "Тип не определён" when the AI failed, otherwise the category - with the AI's confidence when
 * the AI chose it.
 */
export default function DocumentAiBadge({ doc }) {
  if (doc.ocrStatus === 'PENDING' || doc.ocrStatus === 'RUNNING') {
    return (
      <Badge tone="accent" icon={Spinner} title="Текст скана распознаётся; это может занять до пары минут">
        Распознаём скан…
      </Badge>
    )
  }
  if (doc.aiStatus === 'PENDING' || doc.aiStatus === 'PROCESSING') {
    return (
      <Badge tone="primary" icon={Spinner} title="Студенты увидят документ после классификации">
        ИИ определяет тип…
      </Badge>
    )
  }
  if (doc.aiStatus === 'FAILED') {
    return (
      <Badge tone="warning" icon={HelpCircle} title="ИИ не смог определить тип документа">
        Тип не определён
      </Badge>
    )
  }
  const byAi = doc.aiStatus === 'DONE'
  return <CategoryBadge category={doc.category} confidence={byAi ? doc.aiConfidence : null} ai={byAi} />
}
