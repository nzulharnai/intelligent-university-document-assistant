import { useEffect, useState } from 'react'
import { ArrowLeft, CheckCircle2, Loader2, MailCheck, RotateCw } from 'lucide-react'
import { useAuth } from '../context/AuthContext'
import { getErrorMessage } from '../api/axios'
import { Alert, OtpInput, SubmitButton } from './FormControls'
import { emptyOtp } from '../constants/otp'

const RESEND_COOLDOWN_SECONDS = 60
const REDIRECT_DELAY_MS = 1500

export default function EmailVerificationStep({ email, initialInfo = '', initialCooldown = 0, onBack }) {
  const { verifyEmail, resendVerification } = useAuth()

  const [otp, setOtp] = useState(emptyOtp)
  const [error, setError] = useState('')
  const [info, setInfo] = useState(initialInfo)
  const [loading, setLoading] = useState(false)
  const [resending, setResending] = useState(false)
  const [cooldown, setCooldown] = useState(initialCooldown)
  const [failedAttempts, setFailedAttempts] = useState(0)
  const [startSession, setStartSession] = useState(null)

  const otpValue = otp.join('')

  useEffect(() => {
    if (cooldown <= 0) return undefined
    const timer = setTimeout(() => setCooldown((value) => value - 1), 1000)
    return () => clearTimeout(timer)
  }, [cooldown])

  // Show the success state briefly, then log in; PublicOnlyRoute takes the user to /dashboard.
  useEffect(() => {
    if (!startSession) return undefined
    const timer = setTimeout(startSession, REDIRECT_DELAY_MS)
    return () => clearTimeout(timer)
  }, [startSession])

  const handleOtpChange = (next) => {
    setOtp(next)
    setError('')
  }

  const handleSubmit = async (event) => {
    event.preventDefault()
    setInfo('')
    if (!/^\d{6}$/.test(otpValue)) {
      setError('Введите все 6 цифр кода')
      setFailedAttempts((value) => value + 1)
      return
    }

    setError('')
    setLoading(true)
    try {
      const complete = await verifyEmail(email, otpValue)
      // Wrapped because useState treats a function argument as an updater.
      setStartSession(() => complete)
    } catch (err) {
      setError(getErrorMessage(err))
      setFailedAttempts((value) => value + 1)
      setLoading(false)
    }
  }

  const handleResend = async () => {
    setError('')
    setInfo('')
    setResending(true)
    try {
      setInfo(await resendVerification(email))
      setOtp(emptyOtp())
      setCooldown(RESEND_COOLDOWN_SECONDS)
    } catch (err) {
      setError(getErrorMessage(err))
      const retryAfter = err.response?.data?.retryAfterSeconds
      if (retryAfter) setCooldown(retryAfter)
    } finally {
      setResending(false)
    }
  }

  if (startSession) {
    return (
      <div className="animate-step-in flex flex-col items-center py-8 text-center" role="status">
        <span className="flex h-16 w-16 items-center justify-center rounded-full bg-success-soft text-success">
          <CheckCircle2 className="h-8 w-8" aria-hidden="true" />
        </span>
        <h2 className="mt-5 text-section text-fg">Email подтверждён!</h2>
        <p className="mt-2 text-sm text-fg-muted">Аккаунт активирован. Перенаправляем в личный кабинет…</p>
        <Loader2 className="mt-6 h-5 w-5 animate-spin text-primary" aria-hidden="true" />
      </div>
    )
  }

  return (
    <div className="animate-step-in">
      <span className="flex h-12 w-12 items-center justify-center rounded-control bg-primary-soft text-primary">
        <MailCheck className="h-6 w-6" aria-hidden="true" />
      </span>
      <h2 className="mt-5 text-section text-fg">Подтвердите email</h2>
      <p className="mt-1 text-sm text-fg-muted">
        Мы отправили 6-значный код на <b className="break-all text-fg">{email}</b>. Введите его ниже.
      </p>

      <form onSubmit={handleSubmit} className="mt-6 space-y-4" noValidate>
        <Alert type="success">{info}</Alert>
        <Alert>{error}</Alert>

        {/* Remounting on each failed attempt replays the shake animation. */}
        <OtpInput key={failedAttempts} value={otp} onChange={handleOtpChange} error={error} autoFocus />

        <SubmitButton loading={loading}>Подтвердить</SubmitButton>

        <div className="flex flex-wrap items-center justify-between gap-2 text-sm">
          <button
            type="button"
            onClick={onBack}
            className="flex items-center gap-1 rounded-sm text-fg-muted hover:text-fg focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary"
          >
            <ArrowLeft className="h-4 w-4" aria-hidden="true" /> Назад
          </button>
          <button
            type="button"
            onClick={handleResend}
            disabled={cooldown > 0 || resending || loading}
            className="flex items-center gap-1 rounded-sm font-medium tabular-nums text-primary-fg hover:underline focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-primary disabled:cursor-not-allowed disabled:text-fg-muted disabled:no-underline"
          >
            <RotateCw className={`h-4 w-4 ${resending ? 'animate-spin' : ''}`} aria-hidden="true" />
            {cooldown > 0 ? `Отправить повторно через ${cooldown} с` : 'Отправить код повторно'}
          </button>
        </div>

        <p className="text-small text-fg-muted">Не пришло письмо? Проверьте папку «Спам».</p>
      </form>
    </div>
  )
}
