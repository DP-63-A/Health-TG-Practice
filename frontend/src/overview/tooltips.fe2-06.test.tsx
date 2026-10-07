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

import type { AnalyticsResponse } from './analytics.types'
import {
  readContractFixture,
} from './fixtures/fe2-06-contracts'
import type {
  ContractFixture,
} from './fixtures/fe2-06-contracts'

import { NutritionChart } from '../components/Overview-components/charts/NutritionChart'
import { SleepChart } from '../components/Overview-components/charts/SleepChart'
import { StepsChart } from '../components/Overview-components/charts/StepsChart'
import { CheckinChart } from '../components/Overview-components/charts/CheckinChart'

type ChartKind = 'nutrition' | 'sleep' | 'steps' | 'checkin'

interface TooltipOracle {
  fixture: ContractFixture
  kind: ChartKind
  points: readonly (readonly [string, string])[]
  category?: string
}

const chartNames: Record<ChartKind, string> = {
  nutrition: 'Nutrition',
  sleep: 'Sleep',
  steps: 'Steps',
  checkin: 'Wellbeing',
}

// Фиксированные ожидания независимых ответов BE3.
// Production-formatter и формулы агрегирования не используются.
const cases: TooltipOracle[] = [
  {
    fixture: 'normal',
    kind: 'nutrition',
    points: [['19.09.2026', '930 kcal']],
  },
  {
    fixture: 'normal',
    kind: 'sleep',
    points: [
      ['14.09.2026', '7 h'],
      ['16.09.2026', '8 h'],
    ],
  },
  {
    fixture: 'normal',
    kind: 'steps',
    points: [['19.09.2026', '5,000 steps']],
  },
  {
    fixture: 'normal',
    kind: 'checkin',
    points: [['19.09.2026', '4 out of 5']],
    category: 'Mood',
  },
  {
    fixture: 'dedup',
    kind: 'nutrition',
    points: [['19.09.2026', '600 kcal']],
  },
  {
    fixture: 'dedup',
    kind: 'steps',
    points: [['19.09.2026', '5,000 steps']],
  },
  {
    fixture: 'filtered',
    kind: 'nutrition',
    points: [['19.09.2026', '300 kcal']],
  },
]

function renderChart(
  kind: ChartKind,
  data: AnalyticsResponse,
) {
  switch (kind) {
    case 'nutrition':
      return render(
        <NutritionChart series={data.series.nutrition} />,
      )
    case 'sleep':
      return render(
        <SleepChart series={data.series.sleep} />,
      )
    case 'steps':
      return render(
        <StepsChart series={data.series.steps} />,
      )
    case 'checkin':
      return render(
        <CheckinChart series={data.series.checkin} />,
      )
  }
}

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

// Кадры выполняются явно, без произвольных задержек.
let nextFrameId = 0
const frames = new Map<number, FrameRequestCallback>()

async function flushFrames() {
  await act(async () => {
    let iterations = 0

    while (frames.size > 0) {
      iterations += 1

      if (iterations > 50) {
        throw new Error('Отрисовка gрафика не завершилась')
      }

      const current = [...frames.values()]
      frames.clear()

      for (const callback of current) {
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

async function openChart(kind: ChartKind) {
  const chart = screen.getByRole('region', {
    name: chartNames[kind],
  })

  await flushFrames()

  await waitFor(() => {
    expect(
      chart.querySelector('.recharts-wrapper svg.recharts-surface'),
    ).toBeInTheDocument()
  })

  const wrapper = element(chart, '.recharts-wrapper')

  await act(async () => {
    fireEvent.focus(wrapper)
  })

  await flushFrames()

  return { chart, wrapper }
}

async function nextPoint(wrapper: HTMLElement) {
  await act(async () => {
    fireEvent.keyDown(wrapper, { key: 'ArrowRight' })
  })

  await flushFrames()
}

async function expectTooltip(
  chart: HTMLElement,
  kind: ChartKind,
  date: string,
  value: string,
  category?: string,
) {
  await waitFor(() => {
    // Ищем именно содержимое настоящей подсказки.
    // Значения таблицы не могут удовлетворить этой проверке.
    const tooltip = element(
      chart,
      `.${kind}-chart__tooltip`,
    )

    expect(tooltip).toBeVisible()

    expect(
      within(tooltip).getByText(date, { exact: true }),
    ).toBeVisible()

    expect(
      within(tooltip).getByText(value, { exact: true }),
    ).toBeVisible()

    if (category) {
      expect(
        within(tooltip).getByText(category, { exact: true }),
      ).toBeVisible()
    }
  })
}

describe('FE2-06: настоящие tooltip по ответам BE3', () => {
  it.each(cases)(
    '$fixture / $kind: дата, число, единица и категория',
    async (oracle) => {
      renderChart(
        oracle.kind,
        readContractFixture(oracle.fixture),
      )

      const { chart, wrapper } = await openChart(oracle.kind)

      for (const [index, [date, value]] of oracle.points.entries()) {
        if (index > 0) {
          await nextPoint(wrapper)
        }

        await expectTooltip(
          chart,
          oracle.kind,
          date,
          value,
          oracle.category,
        )
      }

      // После ухода фокуса подсказка закрывается.
      await act(async () => {
        fireEvent.blur(wrapper)
      })

      await flushFrames()

      await waitFor(() => {
        expect(
          chart.querySelector(`.${oracle.kind}-chart__tooltip`),
        ).not.toBeInTheDocument()
      })
    },
  )

  it('sleep: пропуски не получают ноль или значение соседнего дня', async () => {
    renderChart('sleep', readContractFixture('gaps'))

    const { chart, wrapper } = await openChart('sleep')

    const expected: readonly (readonly [string, string | null])[] = [
      ['13.09.2026', null],
      ['14.09.2026', '7 h'],
      ['15.09.2026', null],
      ['16.09.2026', '8 h'],
      ['17.09.2026', null],
      ['18.09.2026', null],
      ['19.09.2026', null],
    ]

    for (const [index, [date, value]] of expected.entries()) {
      if (index > 0) {
        await nextPoint(wrapper)
      }

      if (value === null) {
        await waitFor(() => {
          expect(
            chart.querySelector('.sleep-chart__tooltip'),
          ).not.toBeInTheDocument()
        })
      } else {
        await expectTooltip(chart, 'sleep', date, value)
      }
    }
  })

  it.each([
    'nutrition',
    'sleep',
    'steps',
    'checkin',
  ] as const)(
    'пустой ответ / %s: нет выдуманной подсказки',
    async (kind) => {
      renderChart(kind, readContractFixture('empty'))

      await flushFrames()

      const chart = screen.getByRole('region', {
        name: chartNames[kind],
      })

      expect(
        within(chart).getByRole('status'),
      ).toBeVisible()

      expect(
        chart.querySelector(`.${kind}-chart__tooltip`),
      ).not.toBeInTheDocument()

      expect(
        chart.querySelector('.recharts-wrapper'),
      ).not.toBeInTheDocument()
    },
  )
})