// US-09: personal data in documents. The backend masks it in the text for everyone but the course teacher and ADMIN,
// and a student gets the original file only when the teacher allowed it.
import { canDeleteDocument } from './fileTypes'

const PII_TYPE_LABELS = {
  IIN: 'ИИН',
  PHONE: 'телефон',
  EMAIL: 'e-mail',
  ID_DOCUMENT: 'номер документа',
}

/** "ИИН, телефон" from the backend's "IIN,PHONE"; '' when unknown (a student is not told the kinds). */
export const piiTypesText = (types) => (types ?? '').split(',').filter(Boolean)
  .map((type) => PII_TYPE_LABELS[type] ?? type).join(', ')

/** The placeholders the backend puts in place of personal data, e.g. "[ИИН скрыт]". */
export const PII_PLACEHOLDER = /(\[(?:ИИН|телефон|e-mail|номер документа) скрыт\])/

/** The viewer manages the document's course (teacher or ADMIN): they see the original and the personal data badge. */
export const managesDocument = (doc) => canDeleteDocument(doc)

/** A scan whose text is not recognized (yet): whether it holds personal data is unknown, so it counts as if it did. */
export const isPiiUnknown = (doc) => Boolean(doc?.piiUnknown)

/** The original may hold personal data: the teacher decides whether students get it ("Разрешить студентам оригинал"). */
export const hasProtectedOriginal = (doc) => (doc?.piiCount ?? 0) > 0 || isPiiUnknown(doc)

/**
 * The original file has personal data, or is a scan not checked for it, and is closed to this viewer: preview and
 * download would answer 403.
 */
export const isOriginalRestricted = (doc) => hasProtectedOriginal(doc) && !doc.originalAllowedForStudents && !managesDocument(doc)
