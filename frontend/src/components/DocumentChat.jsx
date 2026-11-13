import { TextSearch } from 'lucide-react'
import AiChat from './AiChat'

const SUGGESTIONS = ['О чём этот документ?', 'Какие есть дедлайны?', 'Какие требования к лабораторным работам?']

// onShowSource(source): open the fragment in the "Текст" tab of this page (US-15).
export default function DocumentChat({ doc, onShowSource }) {
  return (
    <AiChat
      endpoint={`/documents/${doc.id}/ask`}
      title="Чат с ИИ по документу"
      subtitle={doc.originalFileName}
      placeholder="Например: когда сдавать лабораторную работу 2?"
      suggestions={SUGGESTIONS}
      disabledReason={doc.hasExtractedText ? undefined : 'В документе нет распознанного текста — ИИ не сможет ответить по нему.'}
      sourceAction={onShowSource && ((source) => (
        <button
          type="button"
          onClick={() => onShowSource(source)}
          className="mt-2 inline-flex items-center gap-1 rounded-sm text-xs font-medium text-primary-fg hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
        >
          Показать в тексте <TextSearch className="h-3 w-3" aria-hidden="true" />
        </button>
      ))}
    />
  )
}
