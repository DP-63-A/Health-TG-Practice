
const numberFormatter = new Intl.NumberFormat('ru-RU', {
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

// 330 → 330 ккал
// 247.5 → 247,5 ккал
export function formatCalories(value: number | null): string {
  if (value === null) {
    return '—'
  }

  return `${formatNumber(value)} ккал`
}

// 450 → 7 ч 30 мин
// 60 → 1 ч
// 0 → 0 мин
export function formatSleep(value: number | null): string {
  if (value === null) {
    return '—'
  }

  const hours = Math.floor(value / 60)
  const minutes = value % 60

  if (hours === 0) {
    return `${minutes} мин`
  }

  if (minutes === 0) {
    return `${hours} ч`
  }

  return `${hours} ч ${minutes} мин`
}

// 8432 → 8 432 шага
export function formatSteps(value: number | null): string {
  if (value === null) {
    return '—'
  }

  const plural = new Intl.PluralRules('ru-RU').select(value)

  const unit =
    plural === 'one'
      ? 'шаг'
      : plural === 'few'
        ? 'шага'
        : plural === 'many'
          ? 'шагов'
          : 'шага'

  return `${formatNumber(value)} ${unit}`
}