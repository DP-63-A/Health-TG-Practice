
const numberFormatter = new Intl.NumberFormat('en-GB', {
  maximumFractionDigits: 10,
})

function formatNumber(value: number): string {
  return numberFormatter
    .format(value)
    .replace(/[\u00A0\u202F]/g, ' ')
}

// 2026-09-16 → 16.09
export function formatChartDate(localDate: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(localDate)

  if (!match) {
    throw new Error('Expected a local date in YYYY-MM-DD format')
  }

  const [, , month, day] = match

  return `${day}.${month}`
}

// 2026-09-16 → 16.09.2026
export function formatTooltipDate(localDate: string): string {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(localDate)

  if (!match) {
    throw new Error('Expected a local date in YYYY-MM-DD format')
  }

  const [, year, month, day] = match

  return `${day}.${month}.${year}`
}

// 330 → 330 kcal
// 247.5 → 247,5 kcal
export function formatCalories(value: number | null): string {
  if (value === null) {
    return '—'
  }

  return `${formatNumber(value)} kcal`
}

// 450 → 7 h 30 min
// 60 → 1 h
// 0 → 0 min
export function formatSleep(value: number | null): string {
  if (value === null) {
    return '—'
  }

  const hours = Math.floor(value / 60)
  const minutes = value % 60

  if (hours === 0) {
    return `${minutes} min`
  }

  if (minutes === 0) {
    return `${hours} h`
  }

  return `${hours} h ${minutes} min`
}

// 8432 → 8 432 steps
export function formatSteps(value: number | null): string {
  if (value === null) {
    return '—'
  }

  const unit = value === 1 ? 'step' : 'steps'

  return `${formatNumber(value)} ${unit}`
}