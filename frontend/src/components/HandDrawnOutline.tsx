import { useId } from 'react'

const upperCrossing = {
  line: 'M270 24C288 42 297 63 292 91C285 130 250 148 185 151C117 159 45 149 19 120C-1 97 4 53 24 30C48 9 94 4 151 7C204 3 256 5 277 28C298 51 297 78 284 98',
  retrace: 'M15 63C6 102 31 133 74 143',
}

const lowerCrossing = {
  line: 'M30 139C9 123 3 96 9 65C15 27 58 9 120 7C186 1 253 8 280 33C303 55 299 103 277 126C250 151 185 157 120 153C72 157 38 151 24 128C10 105 2 78 12 53',
  retrace: 'M103 10C166 4 237 11 267 30M291 82C289 103 278 120 260 132',
}

const outlines: readonly { line: string; retrace: string; transform?: string }[] = [
  {
    line: 'M14 43C23 14 88 4 153 8C223 4 281 17 290 52C306 98 268 145 194 150C112 164 27 149 12 113C5 97 1 83 5 68',
    retrace: 'M19 37C45 8 112 6 160 10C222 7 275 19 287 48',
  },
  {
    ...upperCrossing,
    transform: 'translate(300 0) scale(-1 1)',
  },
  {
    ...lowerCrossing,
    transform: 'translate(300 0) scale(-1 1)',
  },
  upperCrossing,
  lowerCrossing,
]

export function HandDrawnOutline({ index }: { index: number }) {
  const inkId = useId()
  // Continue the five-stroke cycle across diary pages.
  const variant = index % outlines.length
  const outline = outlines[variant]

  return (
    <svg className="entry-outline" data-outline-variant={variant + 1} viewBox="0 0 300 160" preserveAspectRatio="none" fill="none" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id={inkId} x1="0%" y1="0%" x2="100%" y2="75%">
          <stop offset="0%" stopColor="var(--pen-ink)" stopOpacity="0.95" />
          <stop offset="18%" stopColor="var(--pen-ink)" stopOpacity="0.4" />
          <stop offset="36%" stopColor="var(--pen-ink-bright)" />
          <stop offset="51%" stopColor="var(--pen-ink)" stopOpacity="0.55" />
          <stop offset="68%" stopColor="var(--pen-ink)" />
          <stop offset="83%" stopColor="var(--pen-ink-bright)" stopOpacity="0.5" />
          <stop offset="100%" stopColor="var(--pen-ink)" stopOpacity="0.9" />
        </linearGradient>
      </defs>
      <path d={outline.line} transform={outline.transform} stroke={`url(#${inkId})`} vectorEffect="non-scaling-stroke" />
      <path className="entry-outline-retrace" d={outline.retrace} transform={outline.transform} stroke={`url(#${inkId})`} vectorEffect="non-scaling-stroke" />
    </svg>
  )
}
