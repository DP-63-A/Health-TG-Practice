import type {
  AnalyticsSource,
  NutritionPoint,
  SleepPoint,
  StepsPoint,
  CheckinPoint,
} from './analytics.types'

import {
  afterEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest'

import {
  cleanup,
  fireEvent,
  render,
  screen,
  within,
} from '@testing-library/react'

import { NutritionChart } from '../components/Overview-components/charts/NutritionChart'
import { SleepChart } from '../components/Overview-components/charts/SleepChart'
import { StepsChart } from '../components/Overview-components/charts/StepsChart'
import { CheckinChart } from '../components/Overview-components/charts/CheckinChart'

import {
  formatCalories,
  formatSleep,
  formatSteps,
} from './chartFormat'

afterEach(() => {
  cleanup()
})

function getChartTable() {
  const summary = screen.getByText('Daily values')

  const details = summary.closest('details')

  if (!details) {
    throw new Error('Не найден блок details')
  }

  details.open = true

  const table = details.querySelector('table')

  if (!table) {
    throw new Error('Не найдена таблица gрафика')
  }

  return within(table)
}

function source(date: string, type: AnalyticsSource['type']): AnalyticsSource {
  const ids = {
    meal: '22222222-2222-4222-8222-222222222210',
    metrics: '22222222-2222-4222-8222-222222222216',
    checkin: '22222222-2222-4222-8222-222222222225',
  }
  return { entry_id: ids[type], type, local_date: date }
}

function nutritionPoint(date: string, energy_kcal: number | null): NutritionPoint {
  return {
    date,
    energy_kcal,
    source: energy_kcal === null ? [] : [source(date, 'meal')],
  }
}

function sleepPoint(date: string, value: number | null): SleepPoint {
  return {
    date,
    value,
    unit: 'min',
    source: value === null ? null : source(date, 'metrics'),
  }
}

function stepsPoint(date: string, value: number | null): StepsPoint {
  return {
    date,
    value,
    unit: 'count',
    source: value === null ? null : source(date, 'metrics'),
  }
}

function checkinPoint(date: string, value: number | null): CheckinPoint {
  return {
    date,
    value,
    unit: 'score_1_5',
    source: value === null ? null : source(date, 'checkin'),
  }
}

describe('NutritionChart', () => {
  it('отображает калории и передаёт дату выбранного дня', () => {
    const onSelectDay = vi.fn()

    render(
      <NutritionChart
        series={[
          nutritionPoint('2026-09-15', null),
          nutritionPoint('2026-09-16', 330),
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText(formatCalories(330)),
    ).toBeVisible()

    expect(
      table.getByText('No data'),
    ).toBeVisible()

    expect(
      table.getAllByRole('button'),
    ).toHaveLength(2)

    fireEvent.click(
      table.getByRole('button', {
        name: /16\.09\.2026/,
      }),
    )

    expect(onSelectDay).toHaveBeenCalledWith(
      '2026-09-16',
    )

    expect(onSelectDay).toHaveBeenCalledTimes(1)
  })

  it('не путает даты при пропущенном дне', () => {
    const onSelectDay = vi.fn()

    render(
      <NutritionChart
        series={[
          nutritionPoint('2026-09-14', 200),
          nutritionPoint('2026-09-15', null),
          nutritionPoint('2026-09-16', 330),
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    const buttons = table.getAllByRole('button', {
      name: /Select day/,
    })

    expect(buttons).toHaveLength(3)

    // Выбираем день после пропуска.
    fireEvent.click(buttons[2])

    expect(onSelectDay).toHaveBeenCalledWith(
      '2026-09-16',
    )

    expect(onSelectDay).toHaveBeenCalledTimes(1)
  })

  it('считает настоящий ноль существующим значением', () => {
    const onSelectDay = vi.fn()

    render(
      <NutritionChart
        series={[
          nutritionPoint('2026-09-16', 0),
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText(formatCalories(0)),
    ).toBeVisible()

    fireEvent.click(
      table.getByRole('button', {
        name: /16\.09\.2026/,
      }),
    )

    expect(onSelectDay).toHaveBeenCalledWith(
      '2026-09-16',
    )
  })

  it('показывает пустое состояние', () => {
    render(
      <NutritionChart
        series={[
          nutritionPoint('2026-09-16', null),
        ]}
      />,
    )

    expect(
      screen.getByText(
        /No nutrition data for the selected period/,
      ),
    ).toBeVisible()

    expect(
      screen.queryByRole('button', {
        name: /Select day/,
      }),
    ).toBeNull()
  })
})

describe('SleepChart', () => {
  it('отображает сон и передаёт правильную дату', () => {
    const onSelectDay = vi.fn()

    render(
      <SleepChart
        series={[
          sleepPoint('2026-09-15', null),
          sleepPoint('2026-09-16', 450),
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText(formatSleep(450)),
    ).toBeVisible()

    expect(
      table.getByText('No data'),
    ).toBeVisible()

    expect(
      table.getAllByRole('button'),
    ).toHaveLength(2)

    fireEvent.click(
      table.getByRole('button', {
        name: /16\.09\.2026/,
      }),
    )

    expect(onSelectDay).toHaveBeenCalledWith(
      '2026-09-16',
    )

    expect(onSelectDay).toHaveBeenCalledTimes(1)
  })

  it('позволяет выбрать настоящий ноль', () => {
    const onSelectDay = vi.fn()

    render(
      <SleepChart
        series={[
          sleepPoint('2026-09-16', 0),
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText(formatSleep(0)),
    ).toBeVisible()

    fireEvent.click(
      table.getByRole('button', {
        name: /16\.09\.2026/,
      }),
    )

    expect(onSelectDay).toHaveBeenCalledWith(
      '2026-09-16',
    )
  })

  it('показывает сообщение при отсутствии сна', () => {
    render(
      <SleepChart series={[]} />,
    )

    expect(
      screen.getByText(
        /No sleep data for the selected period/,
      ),
    ).toBeVisible()
  })
})

describe('StepsChart', () => {
  it('отображает steps и передаёт правильную дату', () => {
    const onSelectDay = vi.fn()

    render(
      <StepsChart
        series={[
          stepsPoint('2026-09-15', null),
          stepsPoint('2026-09-16', 8432),
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText(formatSteps(8432)),
    ).toBeVisible()

    expect(
      table.getByText('No data'),
    ).toBeVisible()

    expect(
      table.getAllByRole('button'),
    ).toHaveLength(2)

    fireEvent.click(
      table.getByRole('button', {
        name: /16\.09\.2026/,
      }),
    )

    expect(onSelectDay).toHaveBeenCalledWith(
      '2026-09-16',
    )

    expect(onSelectDay).toHaveBeenCalledTimes(1)
  })

  it('позволяет выбрать ноль steps', () => {
    const onSelectDay = vi.fn()

    render(
      <StepsChart
        series={[
          stepsPoint('2026-09-15', null),
          stepsPoint('2026-09-16', 0),
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getAllByRole('button'),
    ).toHaveLength(2)

    expect(
      table.getByText(formatSteps(0)),
    ).toBeVisible()

    fireEvent.click(
      table.getByRole('button', {
        name: /16\.09\.2026/,
      }),
    )

    expect(onSelectDay).toHaveBeenCalledWith(
      '2026-09-16',
    )
  })

  it('показывает пустое состояние', () => {
    render(
      <StepsChart series={[]} />,
    )

    expect(
      screen.getByText(
        /No step data for the selected period/,
      ),
    ).toBeVisible()
  })
})

describe('CheckinChart', () => {
  it('передаёт дату и выбранную категорию', () => {
    const onSelectDay = vi.fn()

    render(
      <CheckinChart
        series={{
          category: 'mood',
          points: [
            checkinPoint('2026-09-15', null),
            checkinPoint('2026-09-16', 4),
          ],
        }}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText('4 out of 5'),
    ).toBeVisible()

    expect(
      table.getByText('No data'),
    ).toBeVisible()

    expect(
      table.getAllByRole('button'),
    ).toHaveLength(2)

    fireEvent.click(
      table.getByRole('button', {
        name: /16\.09\.2026/,
      }),
    )

    expect(onSelectDay).toHaveBeenCalledWith(
      '2026-09-16',
      'mood',
    )

    expect(onSelectDay).toHaveBeenCalledTimes(1)
  })

  it('не позволяет выбрать данные предыдущей категории', () => {
    const onSelectDay = vi.fn()

    render(
      <CheckinChart
        series={{
          category: 'mood',
          points: [
            checkinPoint('2026-09-16', 4),
          ],
        }}
        selectedCategory="wellbeing"
        onSelectDay={onSelectDay}
      />,
    )

    expect(
      screen.getByText(
        /Data for the selected category has not arrived yet/,
      ),
    ).toBeVisible()

    expect(
      screen.queryByRole('button', {
        name: /Select day/,
      }),
    ).toBeNull()

    expect(onSelectDay).not.toHaveBeenCalled()
  })

  it('показывает состояние загрузки', () => {
    const onSelectDay = vi.fn()

    render(
      <CheckinChart
        series={{
          category: 'mood',
          points: [
            checkinPoint('2026-09-16', 4),
          ],
        }}
        isLoading
        onSelectDay={onSelectDay}
      />,
    )

    expect(
      screen.getByText('Loading scores…'),
    ).toBeVisible()

    expect(
      screen.queryByRole('button', {
        name: /Select day/,
      }),
    ).toBeNull()

    expect(onSelectDay).not.toHaveBeenCalled()
  })

  it('показывает отсутствие оценок', () => {
    render(
      <CheckinChart
        series={{
          category: 'mood',
          points: [
            checkinPoint('2026-09-16', null),
          ],
        }}
      />,
    )

    expect(
      screen.getByText(
        /No scores for “Mood” for the selected period/,
      ),
    ).toBeVisible()
  })
})
describe('empty day selection', () => {
  it.each(['nutrition', 'sleep', 'steps', 'checkin'] as const)(
    'allows selecting a day in an entirely empty %s series',
    (kind) => {
      const onSelectDay = vi.fn()
      const date = '2026-09-16'
      if (kind === 'nutrition') {
        render(<NutritionChart series={[nutritionPoint(date, null)]} onSelectDay={onSelectDay} />)
      } else if (kind === 'sleep') {
        render(<SleepChart series={[sleepPoint(date, null)]} onSelectDay={onSelectDay} />)
      } else if (kind === 'steps') {
        render(<StepsChart series={[stepsPoint(date, null)]} onSelectDay={onSelectDay} />)
      } else {
        render(<CheckinChart series={{ category: 'wellbeing', points: [checkinPoint(date, null)] }} onSelectDay={onSelectDay} />)
      }
      expect(screen.getByRole('status')).toBeVisible()
      const table = getChartTable()
      const button = table.getByRole('button', { name: /16\.09\.2026.*No data/ })
      expect(button).toBeEnabled()
      fireEvent.click(button)
      if (kind === 'checkin') {
        expect(onSelectDay).toHaveBeenCalledExactlyOnceWith(date, 'wellbeing')
      } else {
        expect(onSelectDay).toHaveBeenCalledExactlyOnceWith(date)
      }
    },
  )

  it.each(['nutrition', 'sleep', 'steps', 'checkin'] as const)(
    'selects the missing date without substituting its neighbour: %s',
    (kind) => {
      const onSelectDay = vi.fn()
      const date = '2026-09-15'
      const next = '2026-09-16'
      if (kind === 'nutrition') {
        render(<NutritionChart series={[nutritionPoint(date, null), nutritionPoint(next, 330)]} onSelectDay={onSelectDay} />)
      } else if (kind === 'sleep') {
        render(<SleepChart series={[sleepPoint(date, null), sleepPoint(next, 450)]} onSelectDay={onSelectDay} />)
      } else if (kind === 'steps') {
        render(<StepsChart series={[stepsPoint(date, null), stepsPoint(next, 10000)]} onSelectDay={onSelectDay} />)
      } else {
        render(<CheckinChart series={{ category: 'mood', points: [checkinPoint(date, null), checkinPoint(next, 4)] }} onSelectDay={onSelectDay} />)
      }
      fireEvent.click(getChartTable().getByRole('button', { name: /15\.09\.2026.*No data/ }))
      if (kind === 'checkin') {
        expect(onSelectDay).toHaveBeenCalledExactlyOnceWith(date, 'mood')
      } else {
        expect(onSelectDay).toHaveBeenCalledExactlyOnceWith(date)
      }
    },
  )

  it.each(['loading', 'category mismatch'] as const)(
    'does not offer empty checkin dates during %s',
    (reason) => {
      const onSelectDay = vi.fn()
      render(
        <CheckinChart
          series={{ category: 'mood', points: [checkinPoint('2026-09-16', null)] }}
          selectedCategory={reason === 'category mismatch' ? 'wellbeing' : 'mood'}
          isLoading={reason === 'loading'}
          onSelectDay={onSelectDay}
        />,
      )
      expect(screen.queryByText('Daily values')).not.toBeInTheDocument()
      expect(onSelectDay).not.toHaveBeenCalled()
    },
  )
})
