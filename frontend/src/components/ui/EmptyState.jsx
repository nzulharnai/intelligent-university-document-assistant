/**
 * "Nothing here yet": a line icon, a title, an optional hint and at most one action. Distinct from loading
 * (Skeleton / spinner) and from a failed load (ErrorState).
 */
export default function EmptyState({ icon: Icon, title, description, action, compact = false, className = '' }) {
  return (
    <div
      className={`flex flex-col items-center rounded-card border border-dashed border-line text-center ${
        compact ? 'px-4 py-6' : 'px-6 py-10'
      } ${className}`}
    >
      {Icon && (
        <span className="flex h-11 w-11 items-center justify-center rounded-full bg-primary-soft text-primary">
          <Icon className="h-5 w-5" strokeWidth={1.75} aria-hidden="true" />
        </span>
      )}
      <p className={`${Icon ? 'mt-3' : ''} text-sm font-semibold text-fg`}>{title}</p>
      {description && <p className="mt-1 max-w-md text-small text-fg-muted">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}
