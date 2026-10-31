import { FileText, LockKeyhole } from 'lucide-react'

/**
 * In place of the preview when the original file holds personal data the course teacher has not opened to students,
 * or is a scan not yet checked for it ({@code unchecked}): the masked text is in the "Текст" tab ({@code onShowText}
 * switches to it).
 */
export default function RestrictedOriginalNotice({ onShowText, unchecked = false }) {
  return (
    <div className="flex flex-col items-center gap-3 rounded-card border border-line bg-page-alt px-4 py-14 text-center">
      <span className="flex h-12 w-12 items-center justify-center rounded-card bg-page-alt text-fg-secondary">
        <LockKeyhole className="h-6 w-6" aria-hidden="true" />
      </span>
      <p className="text-sm font-semibold text-fg">
        {unchecked ? 'Скан ещё не проверен на личные данные' : 'Оригинал доступен только преподавателю'}
      </p>
      <p className="max-w-md text-xs text-fg-secondary">
        {unchecked
          ? 'Оригинал откроется после распознавания текста или когда преподаватель разрешит доступ.'
          : 'В файле есть личные данные. Текст документа доступен — в нём эти данные скрыты.'}
      </p>
      {onShowText && !unchecked && (
        <button
          type="button"
          onClick={onShowText}
          className="inline-flex items-center gap-1.5 rounded-control border border-line-strong bg-surface px-3 py-1.5 text-sm font-medium text-fg transition hover:bg-page-alt"
        >
          <FileText className="h-4 w-4" aria-hidden="true" /> Открыть текст
        </button>
      )}
    </div>
  )
}
