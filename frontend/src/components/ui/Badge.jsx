import { badgeClass } from './styles'

/**
 * Soft-tint badge (tone: primary, accent, success, warning, danger, neutral; size: sm, md). Always give it an
 * {@code icon}, so the color is never the only signal; the icon is decorative unless {@code iconLabel} names it.
 */
export default function Badge({ tone = 'neutral', size = 'sm', icon: Icon, iconLabel, className = '', children, ...props }) {
  return (
    <span className={`${badgeClass(tone, size)} ${className}`} {...props}>
      {Icon && <Icon aria-hidden={iconLabel ? undefined : 'true'} aria-label={iconLabel} role={iconLabel ? 'img' : undefined} />}
      {children}
    </span>
  )
}
