import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Bell, CheckCheck } from 'lucide-react'
import api, { describeError } from '../api/axios'
import { useToast } from '../context/ToastContext'
import ErrorState from '../components/ErrorState'
import Navbar from '../components/Navbar'
import NotificationItem from '../components/NotificationItem'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import PageHeader from '../components/ui/PageHeader'
import Skeleton from '../components/ui/Skeleton'
import { NOTIFICATIONS_CHANGED, markNotificationRead } from '../utils/notifications'

const PAGE_SIZE = 20

/** "Все уведомления": the user's own notifications, newest first, paged; a click marks one read and opens its link. */
export default function NotificationsPage() {
  const navigate = useNavigate()
  const { notifyError, showSuccess } = useToast()
  const [page, setPage] = useState(null)
  const [error, setError] = useState(null)
  const [loading, setLoading] = useState(true)
  const [attempt, setAttempt] = useState(0)
  const [markingAll, setMarkingAll] = useState(false)

  const fetchPage = useCallback((number) => api.get('/notifications', { params: { page: number, size: PAGE_SIZE } })
    .then(({ data }) => data), [])

  useEffect(() => {
    let cancelled = false
    fetchPage(0)
      .then((data) => {
        if (cancelled) return
        setPage(data)
        setError(null)
      })
      .catch((err) => !cancelled && setError(describeError(err)))
      .finally(() => !cancelled && setLoading(false))
    return () => {
      cancelled = true
    }
  }, [fetchPage, attempt])

  const retry = () => {
    setLoading(true)
    setError(null)
    setAttempt((value) => value + 1)
  }

  const loadMore = async () => {
    setLoading(true)
    try {
      const next = await fetchPage(page.page + 1)
      setPage({ ...next, content: [...page.content, ...next.content] })
    } catch (err) {
      notifyError(err)
    } finally {
      setLoading(false)
    }
  }

  const open = async (notification) => {
    const updated = await markNotificationRead(notification)
    window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED))
    setPage((current) => ({ ...current, content: current.content.map((item) => (item.id === updated.id ? updated : item)) }))
    if (notification.link) navigate(notification.link)
  }

  const readAll = async () => {
    setMarkingAll(true)
    try {
      const { data } = await api.post('/notifications/read-all')
      window.dispatchEvent(new Event(NOTIFICATIONS_CHANGED))
      setPage((current) => ({
        ...current,
        content: current.content.map((item) => ({ ...item, readAt: item.readAt ?? new Date().toISOString() })),
      }))
      showSuccess(data.updated > 0 ? 'Все уведомления прочитаны' : 'Непрочитанных уведомлений нет')
    } catch (err) {
      notifyError(err)
    } finally {
      setMarkingAll(false)
    }
  }

  const hasUnread = page?.content.some((item) => !item.readAt)

  return (
    <div className="min-h-screen bg-page">
      <Navbar />
      <main className="mx-auto max-w-3xl px-4 py-6 sm:px-6 sm:py-8">
        <PageHeader
          icon={Bell}
          title="Уведомления"
          subtitle="Новые задания, изменения сроков, оценки и решения деканата"
          actions={hasUnread && (
            <Button variant="outline" size="sm" icon={CheckCheck} loading={markingAll} onClick={readAll}>
              Прочитать все
            </Button>
          )}
        />
        <section className="mt-6 rounded-card border border-line bg-surface p-2 shadow-card sm:p-3">
          {error && !page ? (
            <ErrorState className="m-2" title="Не удалось загрузить уведомления" message={error.message} onRetry={retry} />
          ) : !page ? (
            <div role="status" className="space-y-2 p-2">
              <span className="sr-only">Загрузка уведомлений…</span>
              {[0, 1, 2, 3].map((key) => <Skeleton key={key} className="h-16 w-full" rounded="rounded-control" />)}
            </div>
          ) : page.content.length === 0 ? (
            <EmptyState
              compact
              icon={Bell}
              title="Уведомлений пока нет"
              description="Здесь появятся новые задания, изменения сроков и оценки."
              className="m-2"
            />
          ) : (
            <>
              <ul className={`space-y-0.5 transition-opacity ${loading ? 'opacity-60' : ''}`}>
                {page.content.map((item) => (
                  <li key={item.id}><NotificationItem notification={item} onOpen={open} /></li>
                ))}
              </ul>
              {!page.last && (
                <Button variant="outline" onClick={loadMore} loading={loading} className="mt-2 w-full">
                  Показать ещё
                </Button>
              )}
            </>
          )}
        </section>
      </main>
    </div>
  )
}
