import { useEffect, useId, useState } from 'react'
import { Loader2, UserRound } from 'lucide-react'
import api, { describeError } from '../api/axios'
import ErrorState from './ErrorState'
import { useAuth } from '../context/AuthContext'
import { useToast } from '../context/ToastContext'
import { ROLE_TEACHER } from '../constants/roles'
import Button from './ui/Button'
import Dialog, { DialogActions } from './ui/Dialog'
import { checkboxClass, controlClass as inputClass, hintClass, labelClass } from './ui/styles'

/**
 * "Профиль" (PATCH /users/me): the user's own name and phone. A teacher's phone stays readable for students in the
 * documents of their courses, like their e-mail; the server stores it as digits only.
 */
export default function ProfileDialog({ onClose }) {
  const titleId = useId()
  const { user, role, updateProfile } = useAuth()
  const { notifyError, showSuccess } = useToast()
  const [fullName, setFullName] = useState(user?.fullName ?? '')
  const [emailNotifications, setEmailNotifications] = useState(true)
  const [savingPreference, setSavingPreference] = useState(false)
  const [phone, setPhone] = useState(user?.phone ?? '')
  // The login response carries no phone: the form is filled from /users/me, so saving never drops a stored number.
  const [loaded, setLoaded] = useState(false)
  const [loadError, setLoadError] = useState('')
  const [attempt, setAttempt] = useState(0)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let cancelled = false
    api.get('/users/me')
      .then(({ data }) => {
        if (cancelled) return
        setFullName(data.fullName ?? '')
        setPhone(data.phone ?? '')
        setEmailNotifications(data.emailNotifications ?? true)
        setLoaded(true)
      })
      .catch((err) => !cancelled && setLoadError(describeError(err).message))
    return () => {
      cancelled = true
    }
  }, [attempt])

  const retryLoad = () => {
    setLoadError('')
    setAttempt((value) => value + 1)
  }

  // Saved at once (PATCH /users/me/preferences), apart from the name and phone form.
  const toggleEmails = async (value) => {
    setSavingPreference(true)
    setEmailNotifications(value)
    try {
      const { data } = await api.patch('/users/me/preferences', { emailNotifications: value })
      setEmailNotifications(data.emailNotifications)
      showSuccess(value ? 'Уведомления будут приходить и на почту' : 'Письма с уведомлениями отключены')
    } catch (err) {
      setEmailNotifications(!value)
      notifyError(err)
    } finally {
      setSavingPreference(false)
    }
  }

  const save = async (event) => {
    event.preventDefault()
    if (!fullName.trim()) {
      setError('Введите ФИО')
      return
    }
    setSaving(true)
    setError('')
    try {
      await updateProfile({ fullName: fullName.trim(), phone: phone.trim() })
      showSuccess('Профиль сохранён')
      onClose()
    } catch (err) {
      setError(describeError(err).message)
    } finally {
      setSaving(false)
    }
  }

  return (
    <Dialog title="Профиль" icon={UserRound} onClose={onClose} busy={saving}>
        {loadError ? (
          <div className="mt-4">
            <ErrorState title="Не удалось загрузить профиль" message={loadError} onRetry={retryLoad} />
          </div>
        ) : !loaded ? (
          <div role="status" className="mt-4 flex items-center justify-center gap-2 py-6 text-sm text-fg-muted">
            <Loader2 className="h-4 w-4 animate-spin text-primary" aria-hidden="true" /> Загрузка…
          </div>
        ) : (
        <form onSubmit={save} noValidate className="mt-5 space-y-4">
          <div>
            <label htmlFor={`${titleId}-name`} className={labelClass}>ФИО</label>
            <input
              id={`${titleId}-name`}
              value={fullName}
              onChange={(event) => setFullName(event.target.value)}
              maxLength={150}
              autoComplete="name"
              className={inputClass}
            />
          </div>
          <div>
            <label htmlFor={`${titleId}-phone`} className={labelClass}>
              Телефон <span className="font-normal text-fg-muted">(необязательно)</span>
            </label>
            <input
              id={`${titleId}-phone`}
              type="tel"
              value={phone}
              onChange={(event) => setPhone(event.target.value)}
              maxLength={30}
              autoComplete="tel"
              placeholder="+7 701 123 45 67"
              aria-describedby={`${titleId}-phone-hint`}
              className={inputClass}
            />
            <p id={`${titleId}-phone-hint`} className={hintClass}>
              {role === ROLE_TEACHER
                ? 'Ваш телефон и e-mail остаются видны студентам в документах ваших курсов; остальные номера скрываются.'
                : 'Пустое поле удаляет номер.'}
            </p>
          </div>
          {user?.email && <p className="text-small text-fg-muted">E-mail: {user.email} (не меняется)</p>}
          <label className="flex cursor-pointer items-start gap-2.5 rounded-control border border-line-strong bg-surface px-3 py-2.5 text-sm text-fg has-[:focus-visible]:ring-4 has-[:focus-visible]:ring-ring">
            <input
              type="checkbox"
              checked={emailNotifications}
              disabled={savingPreference}
              onChange={(event) => toggleEmails(event.target.checked)}
              className={`${checkboxClass} mt-0.5`}
            />
            <span>
              Присылать уведомления на почту
              <span className="block text-small text-fg-muted">Новые задания, изменения сроков и оценки. В колокольчике на сайте они видны всегда.</span>
            </span>
          </label>
          {error && (
            <p role="alert" className="rounded-control bg-danger-soft px-3 py-2 text-small text-danger-fg">{error}</p>
          )}
          <DialogActions>
            <Button variant="outline" onClick={onClose} disabled={saving}>Отмена</Button>
            <Button type="submit" loading={saving}>Сохранить</Button>
          </DialogActions>
        </form>
        )}
    </Dialog>
  )
}
