import { Loader2 } from 'lucide-react'

export default function FullScreenLoader({ text = 'Загрузка…' }) {
  return (
    <div role="status" className="flex min-h-screen flex-col items-center justify-center gap-3 bg-page text-fg-muted">
      <Loader2 className="h-8 w-8 animate-spin text-primary" aria-hidden="true" />
      <p className="text-sm">{text}</p>
    </div>
  )
}
