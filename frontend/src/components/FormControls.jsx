import { useEffect, useRef, useState } from 'react'
import { AlertCircle, CheckCircle2, Eye, EyeOff } from 'lucide-react'
import { OTP_LENGTH } from '../constants/otp'
import Button from './ui/Button'
import { controlClass, controlErrorClass, errorTextClass, labelClass } from './ui/styles'

export function TextField({ label, icon: Icon, id, type = 'text', error, ...props }) {
  const [showPassword, setShowPassword] = useState(false)
  const isPassword = type === 'password'

  return (
    <div>
      <label htmlFor={id} className={labelClass}>
        {label}
      </label>
      <div className="relative">
        {Icon && (
          <Icon className="pointer-events-none absolute left-3 top-1/2 h-4.5 w-4.5 -translate-y-1/2 text-fg-muted" aria-hidden="true" />
        )}
        <input
          id={id}
          type={isPassword && showPassword ? 'text' : type}
          className={`${controlClass} h-11 ${Icon ? 'pl-10' : ''} ${isPassword ? 'pr-10' : ''} ${error ? controlErrorClass : ''}`}
          aria-invalid={Boolean(error)}
          aria-describedby={typeof error === 'string' && error ? `${id}-error` : undefined}
          {...props}
        />
        {isPassword && (
          <button
            type="button"
            onClick={() => setShowPassword((value) => !value)}
            className="absolute right-2 top-1/2 -translate-y-1/2 rounded-md p-1.5 text-fg-muted transition hover:bg-page-alt hover:text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-primary"
            aria-label={showPassword ? 'Скрыть пароль' : 'Показать пароль'}
            aria-pressed={showPassword}
          >
            {showPassword ? <EyeOff className="h-4.5 w-4.5" /> : <Eye className="h-4.5 w-4.5" />}
          </button>
        )}
      </div>
      {/* error={true} only outlines the field; the message is shown elsewhere (e.g. "wrong email or password"). */}
      {typeof error === 'string' && error && <p id={`${id}-error`} className={errorTextClass}>{error}</p>}
    </div>
  )
}

export function SubmitButton({ loading, children, ...props }) {
  return (
    <Button type="submit" loading={loading} className="h-11 w-full" {...props}>
      {children}
    </Button>
  )
}

export function Alert({ type = 'error', children }) {
  if (!children) return null
  const isError = type === 'error'
  const Icon = isError ? AlertCircle : CheckCircle2
  return (
    <div
      role={isError ? 'alert' : 'status'}
      className={`flex items-start gap-2 rounded-control border border-current/20 px-3 py-2.5 text-sm ${
        isError ? 'bg-danger-soft text-danger-fg' : 'bg-success-soft text-success-fg'
      }`}
    >
      <Icon className="mt-0.5 h-4 w-4 shrink-0" aria-hidden="true" />
      <span>{children}</span>
    </div>
  )
}

// Six single-digit cells; supports typing, backspace/arrow navigation and pasting the whole code into any cell.
export function OtpInput({ value, onChange, error, disabled, autoFocus }) {
  const refs = useRef([])
  const isEmpty = value.every((digit) => !digit)

  // Focus the first cell on mount and whenever the code is cleared (e.g. after "resend").
  useEffect(() => {
    if (autoFocus && isEmpty) refs.current[0]?.focus()
  }, [autoFocus, isEmpty])

  const handleChange = (index, rawValue) => {
    const digits = rawValue.replace(/\D/g, '')
    if (!digits) {
      onChange(value.map((digit, i) => (i === index ? '' : digit)))
      return
    }
    const next = [...value]
    digits.slice(0, OTP_LENGTH - index).split('').forEach((digit, offset) => {
      next[index + offset] = digit
    })
    onChange(next)
    refs.current[Math.min(index + digits.length, OTP_LENGTH - 1)]?.focus()
  }

  const handleKeyDown = (index, event) => {
    if (event.key === 'Backspace' && !value[index] && index > 0) {
      refs.current[index - 1]?.focus()
    } else if (event.key === 'ArrowLeft' && index > 0) {
      refs.current[index - 1]?.focus()
    } else if (event.key === 'ArrowRight' && index < OTP_LENGTH - 1) {
      refs.current[index + 1]?.focus()
    }
  }

  return (
    <div className={`flex justify-between gap-2 ${error ? 'animate-shake' : ''}`}>
      {value.map((digit, index) => (
        <input
          key={index}
          ref={(element) => {
            refs.current[index] = element
          }}
          value={digit}
          onChange={(event) => handleChange(index, event.target.value)}
          onKeyDown={(event) => handleKeyDown(index, event)}
          onFocus={(event) => event.target.select()}
          inputMode="numeric"
          autoComplete={index === 0 ? 'one-time-code' : 'off'}
          maxLength={OTP_LENGTH}
          disabled={disabled}
          aria-label={`Цифра ${index + 1}`}
          aria-invalid={Boolean(error)}
          className={`h-12 w-full rounded-control border bg-surface text-center text-lg font-semibold tabular-nums text-fg outline-none transition focus:border-primary focus:ring-4 focus:ring-ring disabled:bg-page-alt ${
            error ? controlErrorClass : digit ? 'border-primary' : 'border-line-strong'
          }`}
        />
      ))}
    </div>
  )
}
