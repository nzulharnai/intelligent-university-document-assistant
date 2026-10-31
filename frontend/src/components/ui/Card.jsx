import { cardClass } from './styles'

/**
 * White card. With {@code title} it gets a header (optional {@code icon}, {@code description} and {@code actions} on
 * the right). {@code as} picks the element (section by default); {@code padded={false}} leaves the body edge-to-edge,
 * e.g. for a table or a list with its own dividers.
 */
export default function Card({
  as: Tag = 'section', title, titleId, icon: Icon, description, actions, padded = true, className = '', bodyClassName = '',
  children, ...props
}) {
  const hasHeader = title || actions
  return (
    <Tag className={`${cardClass} ${className}`} {...props}>
      {hasHeader && (
        <header className={`flex flex-wrap items-start justify-between gap-3 ${padded ? 'px-5 pt-5 sm:px-6 sm:pt-6' : 'border-b border-line-subtle px-5 py-4 sm:px-6'}`}>
          <div className="min-w-0">
            {title && (
              <h2 id={titleId} className="flex items-center gap-2 text-base font-semibold text-fg">
                {Icon && <Icon className="h-4.5 w-4.5 shrink-0 text-primary" aria-hidden="true" />}
                {title}
              </h2>
            )}
            {description && <p className="mt-0.5 text-small text-fg-muted">{description}</p>}
          </div>
          {actions && <div className="flex flex-wrap items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className={`${padded ? `px-5 pb-5 sm:px-6 sm:pb-6 ${hasHeader ? 'pt-4' : 'pt-5 sm:pt-6'}` : ''} ${bodyClassName}`}>
        {children}
      </div>
    </Tag>
  )
}
