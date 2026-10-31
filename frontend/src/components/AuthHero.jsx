import { memo } from 'react'
import { CalendarClock, FileText, MessageSquareText, ShieldCheck } from 'lucide-react'

const VALUE_POINTS = [
  { icon: CalendarClock, text: 'ИИ находит дедлайны' },
  { icon: ShieldCheck, text: 'Личные данные защищены' },
  { icon: MessageSquareText, text: 'Ответы по документам курса' },
]

/*
 * Cloud layers, far to near: the farther a layer, the higher, smaller, fainter and slower it is (160 s / 110 s / 70 s
 * for one full loop). Clouds stay clear of the strip's edges (x 140..1060 of 1200), so the two halves join without a
 * cut cloud.
 */
const LAYERS = [
  { top: '2%', speed: '160s', opacity: 0.55, clouds: [[220, 70, 0.55], [610, 40, 0.45], [930, 85, 0.6]] },
  { top: '9%', speed: '110s', opacity: 0.75, clouds: [[160, 110, 0.8], [520, 80, 0.7], [880, 120, 0.75]] },
  { top: '17%', speed: '70s', opacity: 0.95, clouds: [[330, 120, 1.05], [800, 100, 1.15]] },
]

function Cloud({ x, y, scale }) {
  return (
    <g transform={`translate(${x} ${y}) scale(${scale})`}>
      <rect x="-82" y="-6" width="164" height="36" rx="18" />
      <circle cx="-38" cy="-4" r="30" />
      <circle cx="6" cy="-22" r="40" />
      <circle cx="48" cy="-2" r="26" />
    </g>
  )
}

function CloudStrip({ clouds }) {
  return (
    <svg viewBox="0 0 1200 220" className="block h-auto w-1/2 shrink-0" fill="var(--cloud-fill)" aria-hidden="true" focusable="false">
      {clouds.map(([x, y, scale]) => <Cloud key={x} x={x} y={y} scale={scale} />)}
    </svg>
  )
}

// No props: switching between login and registration never re-renders it, so the drift never restarts.
const SkyClouds = memo(function SkyClouds() {
  return (
    <div className="pointer-events-none absolute inset-0 overflow-hidden" aria-hidden="true">
      {LAYERS.map(({ top, speed, opacity, clouds }) => (
        <div key={speed} className="absolute inset-x-0" style={{ top }}>
          <div className="cloud-layer" style={{ '--cloud-speed': speed, '--layer-opacity': opacity }}>
            <CloudStrip clouds={clouds} />
            <CloudStrip clouds={clouds} />
          </div>
        </div>
      ))}
      {/* Soft hills on the horizon, fading into the page background. */}
      <svg viewBox="0 0 1200 200" preserveAspectRatio="none" className="absolute inset-x-0 bottom-0 h-[22%] w-full">
        <path d="M0 120 C 180 60 360 70 520 105 S 860 150 1020 95 S 1160 70 1200 80 V200 H0 Z" fill="var(--hill-far)" />
        <path d="M0 160 C 220 115 420 130 620 150 S 980 175 1200 135 V200 H0 Z" fill="var(--hill-near)" />
      </svg>
    </div>
  )
})

/**
 * The calm illustrated half of the auth page: sky, drifting clouds and hills, the product name, one line for the
 * current tab and three value points (the points from lg up; below lg it is a 180px header).
 */
function AuthHero({ isRegister }) {
  return (
    <aside className="auth-sky relative h-[180px] shrink-0 overflow-hidden lg:h-auto lg:w-[55%]">
      <SkyClouds />
      <div className="relative z-10 flex h-full flex-col justify-end p-5 sm:p-8 lg:justify-between lg:p-12">
        <div className="hidden items-center gap-2.5 lg:flex">
          <span className="flex h-9 w-9 items-center justify-center rounded-control bg-primary text-on-primary shadow-soft">
            <FileText className="h-5 w-5" aria-hidden="true" />
          </span>
          <span className="text-lg font-semibold tracking-tight text-fg">UniDocs</span>
        </div>

        <div className="max-w-lg">
          <h1 className="flex items-center gap-2.5 text-[1.625rem] leading-8 font-semibold tracking-tight text-fg lg:text-[2.5rem] lg:leading-[3rem]">
            <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-control bg-primary text-on-primary lg:hidden">
              <FileText className="h-4.5 w-4.5" aria-hidden="true" />
            </span>
            UniDocs Assistant
          </h1>
          <p className="mt-1 text-body text-fg-secondary lg:mt-3 lg:text-lg">
            {isRegister ? 'Создайте аккаунт' : 'С возвращением'}
          </p>
          <ul className="mt-10 hidden space-y-3 lg:block">
            {VALUE_POINTS.map(({ icon: Icon, text }) => (
              <li key={text} className="flex items-center gap-3 text-body font-medium text-fg">
                <span className="flex h-9 w-9 items-center justify-center rounded-control bg-surface/80 text-primary shadow-soft backdrop-blur-sm">
                  <Icon className="h-4.5 w-4.5" aria-hidden="true" />
                </span>
                {text}
              </li>
            ))}
          </ul>
        </div>

        <p className="hidden text-small text-fg-secondary lg:block">© {new Date().getFullYear()} UniDocs Assistant</p>
      </div>
    </aside>
  )
}

export default memo(AuthHero)
