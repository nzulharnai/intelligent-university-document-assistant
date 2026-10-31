import { Loader2 } from 'lucide-react'
import { buttonClass } from './styles'

/**
 * Button in one of the variants (primary, secondary, ghost, danger, danger-ghost, outline) and sizes (sm, md).
 * {@code loading} shows a spinner in place of the icon and disables the button; {@code iconOnly} renders just the
 * icon - pass an aria-label then. {@code type} defaults to "button".
 */
export default function Button({
  variant = 'primary', size = 'md', icon: Icon, iconOnly = false, loading = false, disabled, className = '',
  type = 'button', children, ...props
}) {
  const iconClass = size === 'sm' ? 'h-3.5 w-3.5' : 'h-4 w-4'
  return (
    <button
      type={type}
      disabled={disabled || loading}
      aria-busy={loading || undefined}
      className={buttonClass({ variant, size, iconOnly, className })}
      {...props}
    >
      {loading ? <Loader2 className={`${iconClass} animate-spin`} aria-hidden="true" /> : Icon && <Icon className={iconClass} aria-hidden="true" />}
      {!iconOnly && children}
    </button>
  )
}
