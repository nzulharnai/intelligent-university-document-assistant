// US-15: a Q&A source opened in its document's "Текст" tab. Fragments are cut from the masked copy of the text, so
// the offsets point into it (GET /documents/{id}/text?masked=true).

/** The "Текст" tab of the source's document with the fragment selected; its text goes along in the router state. */
export function fragmentLink(source) {
  return `/documents/${source.documentId}?tab=text&from=${source.startOffset}&to=${source.endOffset}`
}

const NON_NEGATIVE_INTEGER = /^\d{1,9}$/

/** { from, to } from the address, or null when they are missing or not whole numbers with 0 <= from < to. */
export function parseFragmentRange(searchParams) {
  const from = searchParams.get('from')
  const to = searchParams.get('to')
  if (!NON_NEGATIVE_INTEGER.test(from ?? '') || !NON_NEGATIVE_INTEGER.test(to ?? '')) return null
  const range = { from: Number(from), to: Number(to) }
  return range.from < range.to ? range : null
}

/**
 * Where the fragment is in {@code text} now: the given range when it still holds the fragment's text (or when the
 * text is unknown, e.g. a pasted link, and the range fits); otherwise the first place the fragment's text occurs.
 * Null when it is not there any more (the document was processed again).
 */
export function locateFragment(text, range, fragmentText) {
  if (!text || !range) return null
  if (!fragmentText) return range.to <= text.length ? range : null
  if (text.slice(range.from, range.to) === fragmentText) return range
  const index = text.indexOf(fragmentText)
  return index >= 0 ? { from: index, to: index + fragmentText.length } : null
}
