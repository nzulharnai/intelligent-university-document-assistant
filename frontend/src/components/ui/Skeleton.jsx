/**
 * Loading placeholder with a sky-tinted shimmer (static with prefers-reduced-motion). Size it with className
 * (h-4 w-32...). Hidden from screen readers: the surrounding block announces the loading state.
 */
export default function Skeleton({ className = '', rounded = 'rounded-md' }) {
  return <span aria-hidden="true" className={`skeleton animate-shimmer block ${rounded} ${className}`} />
}

/** A few text-like lines of decreasing width. */
export function SkeletonLines({ lines = 3, className = '' }) {
  const widths = ['w-full', 'w-11/12', 'w-4/5', 'w-2/3', 'w-1/2']
  return (
    <div className={`space-y-2 ${className}`} aria-hidden="true">
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton key={index} className={`h-3.5 ${widths[index % widths.length]}`} />
      ))}
    </div>
  )
}
