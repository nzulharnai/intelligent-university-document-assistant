import { useState } from 'react'
import { Loader2, RefreshCw } from 'lucide-react'
import api from '../api/axios'
import { useToast } from '../context/ToastContext'
import { isClassifying } from '../utils/documentStatus'

const STYLES = {
  // Matches the action buttons of the vault table.
  small: 'flex items-center gap-1.5 rounded-control border px-2.5 py-1.5 text-xs font-medium',
  // Matches the document page header buttons.
  large: 'flex items-center gap-2 rounded-control border px-3.5 py-2 text-sm font-medium',
}

/**
 * "Переклассифицировать" for the course teacher or ADMIN (render it only when doc.canDelete): POST /reclassify, then
 * reports {@code onUpdated({ aiStatus: 'PENDING', pendingSince })}. The page that shows the row polls PENDING rows
 * (useDocumentStatus) and passes the live row back as {@code doc}, so the button spins until the AI is done.
 * With {@code compact} the text is hidden below sm (icon only, named by aria-label), so narrow rows keep room for the name.
 */
export default function ReclassifyButton({ doc, onUpdated, size = 'small', compact = false }) {
  const { notifyError } = useToast()
  const [sending, setSending] = useState(false)
  const running = sending || isClassifying(doc)

  const reclassify = async () => {
    setSending(true)
    try {
      await api.post(`/documents/${doc.id}/reclassify`)
      onUpdated?.({ aiStatus: 'PENDING', pendingSince: Date.now() })
    } catch (err) {
      notifyError(err)
    } finally {
      setSending(false)
    }
  }

  const icon = size === 'large' ? 'h-4 w-4' : 'h-3.5 w-3.5'
  const label = running ? 'ИИ определяет…' : 'Переклассифицировать'
  return (
    <button
      type="button"
      onClick={reclassify}
      disabled={running}
      aria-label={compact ? label : undefined}
      title="ИИ заново определит тип документа; найденные задания сохранятся"
      className={`${STYLES[size]} ${compact ? 'shrink-0' : ''} border-primary/25 text-primary-fg transition hover:bg-primary-soft disabled:opacity-60`}
    >
      {running ? <Loader2 className={`${icon} animate-spin`} /> : <RefreshCw className={icon} />}
      {compact ? <span className="hidden sm:inline">{label}</span> : label}
    </button>
  )
}
