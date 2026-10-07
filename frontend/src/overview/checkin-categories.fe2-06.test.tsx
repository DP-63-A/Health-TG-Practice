import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import {
  afterEach,
  beforeEach,
  describe,
  expect,
  it,
  vi,
} from 'vitest'

import type {
  CheckinCategory,
} from './analytics.types'
import {
  readContractFixture,
} from './fixtures/fe2-06-contracts'

import { CheckinChart } from '../components/Overview-components/charts/CheckinChart'

interface CategoryOracle {
  category: CheckinCategory
  label: string
  value: string
  date: string
}

const cases: CategoryOracle[] = [
  {
    category: 'sleep_quality',
    label: 'Качество сна',
    value: '4 из 5',
    date: '19.09.2026',
  },
  {
    category: 'digestion_comfort',
    label: 'Комфорт пищеварения',
    value: '5 из 5',
    date: '19.09.2026',
  },
  {
    category: 'wellbeing',
    label: 'Самочувствие',
    value: '3 из 5',
    date: '19.09.2026',
  },
  {
    category: 'mood',
    label: 'Настроение',
    value: '4 из 5',
    date: '19.09.2026',
  },
]

function element(
  root: ParentNode,
  selector: string,
): HTMLElement {
  const found = root.querySelector<HTMLElement>(selector)

  if (!found) {
    throw new Error(`Не найден элемент: ${selector}`)
  }

  return found
}

const rect = {
  x: 0,
  y: 0,
  top: 0,
  left: 0,
  right: 640,
  bottom: 320,
  width: 640,
  height: 320,
  toJSON: () => ({}),
}

let nextFrameId = 0
const frames = new Map<number, FrameRequestCallback>()

async function flushFrames() {
  await act(async () => {
    let iterations = 0

    while (frames.size > 0) {
      iterations += 1

      if (iterations > 50) {
        throw new Error('Отрисовка графика не завершилась')
      }

      const callbacks = [...frames.values()]
      frames.clear()

      for (const callback of callbacks) {
        callback(performance.now())
      }
    }
  })
}

beforeEach(() => {
  nextFrameId = 0
  frames.clear()

  vi.spyOn(Element.prototype, 'getBoundingClientRect')
    .mockReturnValue(rect)

  vi.stubGlobal(
    'ResizeObserver',
    class implements ResizeObserver {
      private callback: ResizeObserverCallback

      constructor(callback: ResizeObserverCallback) {
        this.callback = callback
      }

      observe(target: Element) {
        this.callback(
          [{
            target,
            contentRect: target.getBoundingClientRect(),
            borderBoxSize: [],
            contentBoxSize: [],
            devicePixelContentBoxSize: [],
          }],
          this,
        )
      }

      unobserve() {}
      disconnect() {}
    },
  )

  vi.stubGlobal(
    'requestAnimationFrame',
    (callback: FrameRequestCallback) => {
      const id = ++nextFrameId
      frames.set(id, callback)
      return id
    },
  )

  vi.stubGlobal('cancelAnimationFrame', (id: number) => {
    frames.delete(id)
  })
})

afterEach(() => {
  frames.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

describe('FE2-06: отображение всех категорий состояния', () => {
  it.each(cases)(
    '$label: правильные категория, дата и оценка в tooltip и таблице',
    async (oracle) => {
      const data = readContractFixture('normal')
      const rating = data.cards.checkins[oracle.category]

      if (
        rating.score === null ||
        rating.date === null ||
        rating.entry_id === null
      ) {
        throw new Error(
          `В эталоне отсутствует оценка ${oracle.category}`,
        )
      }

      // Одиночный компонентный ряд из готовой оценки BE3.
      // Агрегаты и дневные ряды backend здесь не рассчитываются.
      // Это не новый полный AnalyticsResponse.
      render(
        <CheckinChart
          series={{
            category: oracle.category,
            points: [{
              date: rating.date,
              value: rating.score,
              unit: 'score_1_5',
              source: {
                entry_id: rating.entry_id,
                type: 'checkin',
                local_date: rating.date,
              },
            }],
          }}
          selectedCategory={oracle.category}
          onCategoryChange={vi.fn()}
        />,
      )

      await flushFrames()

      const chart = screen.getByRole('region', {
        name: 'Состояние',
      })

      expect(
        within(chart).getByLabelText('Категория'),
      ).toHaveValue(oracle.category)

      expect(
        within(chart).getByLabelText('Категория'),
      ).toHaveDisplayValue(oracle.label)

      await waitFor(() => {
        expect(
          chart.querySelector(
            '.recharts-wrapper svg.recharts-surface',
          ),
        ).toBeInTheDocument()
      })

      const wrapper = element(
        chart,
        '.recharts-wrapper',
      )

      await act(async () => {
        fireEvent.focus(wrapper)
      })

      await flushFrames()

      await waitFor(() => {
        const tooltip = element(
          chart,
          '.checkin-chart__tooltip',
        )

        expect(tooltip).toBeVisible()

        expect(
          within(tooltip).getByText(oracle.date, {
            exact: true,
          }),
        ).toBeVisible()

        expect(
          within(tooltip).getByText(oracle.label, {
            exact: true,
          }),
        ).toBeVisible()

        expect(
          within(tooltip).getByText(oracle.value, {
            exact: true,
          }),
        ).toBeVisible()

        // Подсказка не должна содержать чужую категорию.
        for (const other of cases) {
          if (other.category !== oracle.category) {
            expect(
              within(tooltip).queryByText(other.label, {
                exact: true,
              }),
            ).not.toBeInTheDocument()
          }
        }
      })

      fireEvent.click(
        within(chart).getByText('Значения по дням'),
      )

      const table = within(chart).getByRole('table', {
        name: `${oracle.label}: оценки по дням`,
      })

      expect(table).toBeVisible()

      expect(
        within(table).getByRole('rowheader', {
          name: oracle.date,
        }),
      ).toBeVisible()

      expect(
        within(table).getByRole('cell', {
          name: oracle.value,
        }),
      ).toBeVisible()

      // Заголовок и одна точка.
      expect(
        within(table).getAllByRole('row'),
      ).toHaveLength(2)
    },
  )
})