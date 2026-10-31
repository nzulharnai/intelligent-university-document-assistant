import { File, FileImage, FileSpreadsheet, FileText } from 'lucide-react'
import { BADGE_TONES } from '../components/ui/styles'

// Must match INLINE_CONTENT_TYPES in DocumentController: only these are served by /documents/{id}/view.
const PREVIEWABLE_TYPES = new Set(['application/pdf', 'image/png', 'image/jpeg', 'image/gif', 'image/webp'])

// Familiar hues per format (PDF red, Word blue, Excel green), from the theme tokens so they work in both themes.
const FORMATS = {
  pdf: { label: 'PDF', icon: FileText, badge: `${BADGE_TONES.danger} ring-current/15`, tile: 'bg-danger-soft text-danger' },
  doc: { label: 'DOCX', icon: FileText, badge: `${BADGE_TONES.primary} ring-current/15`, tile: 'bg-primary-soft text-primary' },
  sheet: { label: 'XLSX', icon: FileSpreadsheet, badge: `${BADGE_TONES.success} ring-current/15`, tile: 'bg-success-soft text-success' },
  image: { label: 'IMG', icon: FileImage, badge: `${BADGE_TONES.accent} ring-current/15`, tile: 'bg-accent-soft text-accent-fg' },
}

const OTHER_STYLE = { icon: File, badge: `${BADGE_TONES.neutral} ring-current/15`, tile: 'bg-neutral-soft text-neutral-fg' }

function baseContentType(contentType) {
  return (contentType ?? '').split(';')[0].trim().toLowerCase()
}

/** Format badge info from the MIME type, falling back to the file extension. */
export function getFileFormat(doc) {
  const type = baseContentType(doc.contentType)
  const ext = doc.originalFileName?.includes('.') ? doc.originalFileName.split('.').pop().toLowerCase() : ''

  if (type === 'application/pdf' || ext === 'pdf') return FORMATS.pdf
  if (type.startsWith('image/')) return FORMATS.image
  if (type.includes('wordprocessingml') || type === 'application/msword' || ext === 'docx' || ext === 'doc') {
    return { ...FORMATS.doc, label: ext === 'doc' ? 'DOC' : 'DOCX' }
  }
  if (type.includes('spreadsheetml') || type === 'application/vnd.ms-excel' || ext === 'xlsx' || ext === 'xls') {
    return { ...FORMATS.sheet, label: ext === 'xls' ? 'XLS' : 'XLSX' }
  }
  return { ...OTHER_STYLE, label: ext ? ext.toUpperCase().slice(0, 5) : 'FILE' }
}

export function isPreviewable(doc) {
  return PREVIEWABLE_TYPES.has(baseContentType(doc.contentType))
}

// The backend decides: the course's teacher or ADMIN. Uploading a document does not make it the uploader's to delete.
export function canDeleteDocument(doc) {
  return Boolean(doc.canDelete)
}

export function isImage(doc) {
  return baseContentType(doc.contentType).startsWith('image/')
}

export function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} Б`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} КБ`
  return `${(bytes / 1024 / 1024).toFixed(1)} МБ`
}
