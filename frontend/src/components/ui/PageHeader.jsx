/**
 * Page title block: an optional icon tile, the h1 (28/34 semibold from sm up), a subtitle and actions on the right
 * (they wrap under the title on narrow screens). {@code children} goes under the subtitle (badges, meta line).
 */
export default function PageHeader({ icon: Icon, title, subtitle, actions, children, className = '' }) {
  return (
    <div className={`flex flex-wrap items-start justify-between gap-4 ${className}`}>
      <div className="flex min-w-0 items-start gap-3">
        {Icon && (
          <span className="flex h-11 w-11 shrink-0 items-center justify-center rounded-control bg-primary-soft text-primary">
            <Icon className="h-5 w-5" aria-hidden="true" />
          </span>
        )}
        <div className="min-w-0">
          <h1 className="break-words text-[1.5rem] leading-8 font-semibold text-fg sm:text-title">{title}</h1>
          {subtitle && <p className="mt-1 text-sm text-fg-muted">{subtitle}</p>}
          {children}
        </div>
      </div>
      {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
    </div>
  )
}
