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

import { NutritionChart } from '../components/Overview-components/charts/NutritionChart'
import { SleepChart } from '../components/Overview-components/charts/SleepChart'
import { StepsChart } from '../components/Overview-components/charts/StepsChart'
import { CheckinChart } from '../components/Overview-components/charts/CheckinChart'

type ChartKind = 'nutrition' | 'sleep' | 'steps' | 'checkin'

interface HoverOracle {
  kind: ChartKind
  region: string
  points: readonly (readonly [string, string])[]
  category?: string
}

const cases: HoverOracle[] = [
  {
    kind: 'nutrition',
    region: 'Питание',
    points: [['19.09.2026', '930 ккал']],
  },
  {
    kind: 'sleep',
    region: 'Сон',
    points: [
      ['14.09.2026', '7 ч'],
      ['16.09.2026', '8 ч'],
    ],
  },
  {
    kind: 'steps',
    region: 'Шаги',
    points: [['19.09.2026', '5 000 шагов']],
  },
  {
    kind: 'checkin',
    region: 'Состояние',
    points: [['19.09.2026', '4 из 5']],
    category: 'Настроение',
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

function numericAttribute(
  target: Element,
  name: string,
): number {
  const raw = target.getAttribute(name)

  if (raw === null) {
    throw new Error(`У столбика отсутствует атрибут ${name}`)
  }

  const value = Number(raw)

  if (!Number.isFinite(value)) {
    throw new Error(`Некорректный атрибут ${name}: ${raw}`)
  }

  return value
}

// Размер контейнера нужен jsdom для отрисовки Recharts.
// Значения и координаты столбиков рассчитывает сам Recharts.
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

describe('FE2-06: наведение мышью на столбики', () => {
  it.each(cases)(
    '$region: наведение открывает правильный tooltip',
    async (oracle) => {
      renderChart(
        oracle.kind,
        readContractFixture('normal'),
      )

      await flushFrames()

      const chart = screen.getByRole('region', {
        name: oracle.region,
      })

      const tooltipSelector =
        `.${oracle.kind}-chart__tooltip`

      // До взаимодействия подсказка не открыта.
      expect(
        chart.querySelector(tooltipSelector),
      ).not.toBeInTheDocument()

      await waitFor(() => {
        expect(
          chart.querySelectorAll(
            '.recharts-bar-rectangle path.recharts-rectangle',
          ),
        ).toHaveLength(oracle.points.length)
      })

      const wrapper = element(chart, '.recharts-wrapper')

      for (const [index, [date, value]] of oracle.points.entries()) {
        // Берём актуальный SVG после возможной перерисовки.
        const bars = chart.querySelectorAll(
          '.recharts-bar-rectangle path.recharts-rectangle',
        )

        const bar = bars[index]

        if (!bar) {
          throw new Error(`Не найден столбик ${index}`)
        }

        const x = numericAttribute(bar, 'x')
        const y = numericAttribute(bar, 'y')
        const width = numericAttribute(bar, 'width')
        const height = numericAttribute(bar, 'height')

        expect(width).toBeGreaterThan(0)
        expect(height).toBeGreaterThan(0)

        // Геометрия взаимодействия, не формула аналитики.
        const clientX = x + width / 2
        const clientY = y + height / 2

        await act(async () => {
          fireEvent.mouseEnter(bar, {
            clientX,
            clientY,
          })

          // Событие всплывает до настоящего wrapper Recharts.
          fireEvent.mouseMove(bar, {
            clientX,
            clientY,
          })
        })

        await flushFrames()

        await waitFor(() => {
          const tooltip = element(
            chart,
            tooltipSelector,
          )

          expect(tooltip).toBeVisible()

          expect(
            within(tooltip).getByText(date, {
              exact: true,
            }),
          ).toBeVisible()

          expect(
            within(tooltip).getByText(value, {
              exact: true,
            }),
          ).toBeVisible()

          if (oracle.category) {
            expect(
              within(tooltip).getByText(oracle.category, {
                exact: true,
              }),
            ).toBeVisible()
          }
        })

        // Уводим мышь за пределы графика.
        await act(async () => {
          fireEvent.mouseLeave(wrapper, {
            clientX: 700,
            clientY: 350,
          })
        })

        await flushFrames()

        await waitFor(() => {
          expect(
            chart.querySelector(tooltipSelector),
          ).not.toBeInTheDocument()
        })
      }
    },
  )
})