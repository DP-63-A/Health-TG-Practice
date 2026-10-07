
import { describe, expect, it } from 'vitest'

import {
  formatChartDate,
  formatTooltipDate,
  formatCalories,
  formatSleep,
  formatSteps,
} from './chartFormat'

describe('Chart date formatting', () => {
  it('formats a local date for the chart axis', () => {
    expect(formatChartDate('2026-09-16')).toBe('16.09')
  })

  it('formats a full local date for tooltip', () => {
    expect(formatTooltipDate('2026-09-16')).toBe('16.09.2026')
  })
})

describe('Chart value formatting', () => {
  it('formats calories', () => {
    expect(formatCalories(330)).toBe('330 kcal')
  })

  it('keeps fractional calories in the displayed value', () => {
    expect(formatCalories(247.5)).toBe('247.5 kcal')
  })

  it('formats sleep duration', () => {
    expect(formatSleep(450)).toBe('7 h 30 min')
  })

  it('formats steps', () => {
    expect(formatSteps(8432)).toBe('8,432 steps')
  })

  it('does not confuse missing values with zero', () => {
    expect(formatCalories(null)).toBe('—')
    expect(formatCalories(0)).toBe('0 kcal')

    expect(formatSleep(null)).toBe('—')
    expect(formatSleep(0)).toBe('0 min')

    expect(formatSteps(null)).toBe('—')
    expect(formatSteps(0)).toBe('0 steps')
  })
})