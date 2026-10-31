import api from '../api/axios'

// Fired after notifications were marked read outside the bell (the "Все уведомления" page), so its count follows.
export const NOTIFICATIONS_CHANGED = 'unidocs:notifications-changed'

/**
 * Marks the notification read (POST /notifications/{id}/read, once) and returns its new row; a failure is ignored,
 * since opening the link matters more than the mark.
 */
export async function markNotificationRead(notification) {
  if (notification.readAt) return notification
  try {
    const { data } = await api.post(`/notifications/${notification.id}/read`)
    return data
  } catch {
    return notification
  }
}
