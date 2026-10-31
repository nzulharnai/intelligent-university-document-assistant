// Shared class strings of the design system. They use the semantic tokens from index.css, so each one works in both
// themes without dark: variants. Components in this folder are built from them; markup that cannot use a component
// (a <select> inside a label, a table) takes the string directly.

/** Visible keyboard focus for buttons, links and other clickable elements. */
export const focusRing = 'focus-visible:outline-none focus-visible:ring-4 focus-visible:ring-ring'

const BUTTON_VARIANTS = {
  primary: 'bg-primary text-on-primary shadow-soft hover:bg-primary-hover active:bg-primary-pressed',
  secondary: 'border border-primary bg-surface text-primary-fg hover:bg-primary-soft',
  ghost: 'text-fg-secondary hover:bg-page-alt hover:text-fg',
  danger: 'bg-danger text-on-danger shadow-soft hover:bg-danger/90',
  // Destructive action that should not shout (row actions, "Выйти").
  'danger-ghost': 'text-danger-fg hover:bg-danger-soft',
  // Neutral outlined button ("Отмена").
  outline: 'border border-line-strong bg-surface text-fg hover:bg-page-alt',
}

const BUTTON_SIZES = {
  sm: 'h-8 gap-1.5 px-3 text-small',
  md: 'h-10 gap-2 px-4 text-sm',
}
const ICON_ONLY_SIZES = { sm: 'h-8 w-8', md: 'h-10 w-10' }

/** Shared button classes, for links (<Link>) and labels that must look like a button. */
export function buttonClass({ variant = 'primary', size = 'md', iconOnly = false, className = '' } = {}) {
  return `inline-flex shrink-0 items-center justify-center rounded-control font-semibold transition-colors disabled:cursor-not-allowed disabled:opacity-60 ${focusRing} ${
    BUTTON_VARIANTS[variant] ?? BUTTON_VARIANTS.primary
  } ${iconOnly ? ICON_ONLY_SIZES[size] : BUTTON_SIZES[size]} ${className}`
}

/** Text inputs, selects and textareas: white, 1px edge with 3:1 contrast, blue focus ring. */
export const controlClass = 'w-full rounded-control border border-line-strong bg-surface px-3 py-2 text-sm text-fg outline-none transition placeholder:text-fg-muted focus:border-primary focus:ring-4 focus:ring-ring disabled:cursor-not-allowed disabled:bg-page-alt disabled:opacity-70'

/** Added to controlClass when the field is invalid (pair it with aria-invalid and an error text). */
export const controlErrorClass = '!border-danger focus:!ring-danger/25'

export const labelClass = 'mb-1.5 block text-sm font-medium text-fg'
export const hintClass = 'mt-1 text-small text-fg-muted'
export const errorTextClass = 'mt-1 text-small text-danger-fg'

/** Native checkbox and radio in the brand color. */
export const checkboxClass = 'h-4 w-4 shrink-0 cursor-pointer rounded border-line-strong accent-primary'

/** White card with a light edge and a soft blue-tinted shadow. */
export const cardClass = 'rounded-card border border-line bg-surface shadow-card'

/** Tables: wrap in tableWrapClass; the header row sticks to the top of that box when it scrolls. */
export const tableWrapClass = 'overflow-auto'
export const tableClass = 'w-full text-left text-sm'
export const tableHeadClass = 'text-xs font-semibold uppercase tracking-wide text-fg-muted [&_th]:sticky [&_th]:top-0 [&_th]:z-10 [&_th]:bg-page-alt [&_th]:px-4 [&_th]:py-3 [&_th]:font-semibold'
export const tableRowClass = 'border-t border-line-subtle transition-colors hover:bg-page [&>td]:h-12 [&>td]:px-4 [&>td]:py-2.5'

/** Soft-tint badge colors: tinted background, darker text of the same hue (all AA). */
export const BADGE_TONES = {
  primary: 'bg-primary-soft text-primary-fg',
  accent: 'bg-accent-soft text-accent-fg',
  success: 'bg-success-soft text-success-fg',
  warning: 'bg-warning-soft text-warning-fg',
  danger: 'bg-danger-soft text-danger-fg',
  neutral: 'bg-neutral-soft text-neutral-fg',
}

export const BADGE_SIZES = {
  sm: 'gap-1 px-2 py-0.5 text-[11px] leading-4 [&>svg]:h-3 [&>svg]:w-3',
  md: 'gap-1.5 px-2.5 py-0.5 text-xs leading-5 [&>svg]:h-3.5 [&>svg]:w-3.5',
}

/** A badge without the Badge component (e.g. on a <select>): base + tone + size. */
export const badgeClass = (tone = 'neutral', size = 'sm') =>
  `inline-flex shrink-0 items-center rounded-full font-semibold tabular-nums ring-1 ring-inset ring-current/15 ${BADGE_TONES[tone] ?? BADGE_TONES.neutral} ${BADGE_SIZES[size] ?? BADGE_SIZES.sm}`

/** Dialog backdrop and panel (see Dialog). */
export const overlayClass = 'fixed inset-0 flex items-center justify-center bg-overlay p-4 backdrop-blur-sm'
export const dialogPanelClass = 'animate-step-in max-h-[calc(100dvh-2rem)] w-full overflow-y-auto rounded-card border border-line bg-surface p-6 shadow-card focus:outline-none'

/** Text link in the brand color (darker shade: AA on any background). */
export const linkClass = 'font-medium text-primary-fg underline-offset-2 hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary rounded-sm'
