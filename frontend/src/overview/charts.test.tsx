
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

// Открываем существующую таблицу под графиком.
// Так тесты проверяют доступный выбор дня,
// который работает без мыши и без наведения на SVG.

function getChartTable() {
  const summary = screen.getByText('Значения по дням')

  const details = summary.closest('details')

  if (!details) {
    throw new Error('Не найден блок details')
  }

  details.open = true

  const table = details.querySelector('table')

  if (!table) {
    throw new Error('Не найдена таблица графика')
  }

  return within(table)
}

describe('NutritionChart', () => {
  it('отображает калории и передаёт дату выбранного дня', () => {
    const onSelectDay = vi.fn()

    render(
      <NutritionChart
        series={[
          {
            date: '2026-09-15',
            value: null,
          },
          {
            date: '2026-09-16',
            value: 330,
          },
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText(formatCalories(330)),
    ).toBeTruthy()

    expect(
      table.getByText('Нет данных'),
    ).toBeTruthy()

    expect(
      table.getAllByRole('button'),
    ).toHaveLength(1)

    fireEvent.click(
      table.getByRole('button', {
        name: /Выбрать день/,
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
          {
            date: '2026-09-14',
            value: 200,
          },
          {
            date: '2026-09-15',
            value: null,
          },
          {
            date: '2026-09-16',
            value: 330,
          },
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    const buttons = table.getAllByRole('button', {
      name: /Выбрать день/,
    })

    expect(buttons).toHaveLength(2)

    // Выбираем второй существующий день.
    fireEvent.click(buttons[1])

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
          {
            date: '2026-09-16',
            value: 0,
          },
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText(formatCalories(0)),
    ).toBeTruthy()

    fireEvent.click(
      table.getByRole('button', {
        name: /Выбрать день/,
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
          {
            date: '2026-09-16',
            value: null,
          },
        ]}
      />,
    )

    expect(
      screen.getByText(
        /Нет данных о питании за выбранный период/,
      ),
    ).toBeTruthy()

    expect(
      screen.queryByRole('button', {
        name: /Выбрать день/,
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
          {
            date: '2026-09-15',
            value: null,
          },
          {
            date: '2026-09-16',
            value: 450,
          },
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText(formatSleep(450)),
    ).toBeTruthy()

    expect(
      table.getByText('Нет данных'),
    ).toBeTruthy()

    expect(
      table.getAllByRole('button'),
    ).toHaveLength(1)

    fireEvent.click(
      table.getByRole('button', {
        name: /Выбрать день/,
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
          {
            date: '2026-09-16',
            value: 0,
          },
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText(formatSleep(0)),
    ).toBeTruthy()

    fireEvent.click(
      table.getByRole('button', {
        name: /Выбрать день/,
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
        /Нет данных о сне за выбранный период/,
      ),
    ).toBeTruthy()
  })
})

describe('StepsChart', () => {
  it('отображает шаги и передаёт правильную дату', () => {
    const onSelectDay = vi.fn()

    render(
      <StepsChart
        series={[
          {
            date: '2026-09-15',
            value: null,
          },
          {
            date: '2026-09-16',
            value: 8432,
          },
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText(formatSteps(8432)),
    ).toBeTruthy()

    expect(
      table.getByText('Нет данных'),
    ).toBeTruthy()

    expect(
      table.getAllByRole('button'),
    ).toHaveLength(1)

    fireEvent.click(
      table.getByRole('button', {
        name: /Выбрать день/,
      }),
    )

    expect(onSelectDay).toHaveBeenCalledWith(
      '2026-09-16',
    )

    expect(onSelectDay).toHaveBeenCalledTimes(1)
  })

  it('позволяет выбрать ноль шагов', () => {
    const onSelectDay = vi.fn()

    render(
      <StepsChart
        series={[
          {
            date: '2026-09-15',
            value: null,
          },
          {
            date: '2026-09-16',
            value: 0,
          },
        ]}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getAllByRole('button'),
    ).toHaveLength(1)

    expect(
      table.getByText(formatSteps(0)),
    ).toBeTruthy()

    fireEvent.click(
      table.getByRole('button', {
        name: /Выбрать день/,
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
        /Нет данных о шагах за выбранный период/,
      ),
    ).toBeTruthy()
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
            {
              date: '2026-09-15',
              value: null,
            },
            {
              date: '2026-09-16',
              value: 4,
            },
          ],
        }}
        onSelectDay={onSelectDay}
      />,
    )

    const table = getChartTable()

    expect(
      table.getByText('4 из 5'),
    ).toBeTruthy()

    expect(
      table.getByText('Нет данных'),
    ).toBeTruthy()

    expect(
      table.getAllByRole('button'),
    ).toHaveLength(1)

    fireEvent.click(
      table.getByRole('button', {
        name: /Выбрать день/,
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
            {
              date: '2026-09-16',
              value: 4,
            },
          ],
        }}
        selectedCategory="wellbeing"
        onSelectDay={onSelectDay}
      />,
    )

    expect(
      screen.getByText(
        /Данные выбранной категории ещё не получены/,
      ),
    ).toBeTruthy()

    expect(
      screen.queryByRole('button', {
        name: /Выбрать день/,
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
            {
              date: '2026-09-16',
              value: 4,
            },
          ],
        }}
        isLoading
        onSelectDay={onSelectDay}
      />,
    )

    expect(
      screen.getByText('Загрузка оценок…'),
    ).toBeTruthy()

    expect(
      screen.queryByRole('button', {
        name: /Выбрать день/,
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
            {
              date: '2026-09-16',
              value: null,
            },
          ],
        }}
      />,
    )

    expect(
      screen.getByText(
        /Нет оценок «Настроение» за выбранный период/,
      ),
    ).toBeTruthy()
  })
})