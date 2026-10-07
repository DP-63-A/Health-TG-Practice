
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

import {
  readContractFixture,
} from './fixtures/fe2-06-contracts'
import { NutritionCard } from '../components/Overview-components/NutritionCard'

// Независимые ручные ожидания отображения.
// Формулы расчёта процентов в тесты не переносим.
// Требуется review смысла процентов и округления.
const cases = [
  {
    fixture: 'normal',
    percentages: ['22%', '30%', '48%'],
  },
  {
    fixture: 'dedup',
    percentages: ['21%', '31%', '48%'],
  },
  {
    fixture: 'filtered',
    percentages: ['21%', '31%', '48%'],
  },
] as const

const labels = ['Белки', 'Жиры', 'Углеводы'] as const

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

function normalize(value: string | null): string {
  return (value ?? '')
    .replace(/[\u00a0\u202f]/g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
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
        throw new Error('Отрисовка диаграммы не завершилась')
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

describe('FE2-06: проценты БЖУ — ожидания для review', () => {
  it.each(cases)(
    '$fixture: проценты легенды и tooltip всех трёх секторов',
    async ({ fixture, percentages }) => {
      const data = readContractFixture(fixture)

      render(
        <NutritionCard nutrition={data.cards.nutrition} />,
      )

      await flushFrames()

      const card = screen.getByRole('region', {
        name: 'Калории и БЖУ',
      })

      const legend = element(
        card,
        '.nutrition-card__legend',
      )

      expect(
        legend.querySelectorAll('.nutrition-card__legend-item'),
      ).toHaveLength(3)

      await waitFor(() => {
        expect(
          card.querySelectorAll('.recharts-pie-sector'),
        ).toHaveLength(3)
      })

      for (const [index, label] of labels.entries()) {
        const legendItem = within(legend)
          .getByText(label, { exact: true })
          .closest('.nutrition-card__legend-item')

        if (!legendItem) {
          throw new Error(`Не найдена легенда "${label}"`)
        }

        const displayedPercent = element(
          legendItem,
          '.nutrition-card__legend-percent',
        )

        expect(displayedPercent).toBeVisible()
        expect(normalize(displayedPercent.textContent))
          .toBe(percentages[index])

        // Берём актуальный сектор после перерисовки.
        const sector = card.querySelectorAll(
          '.recharts-pie-sector',
        )[index]

        if (!sector) {
          throw new Error(`Не найден сектор "${label}"`)
        }

        await act(async () => {
          fireEvent.mouseEnter(sector)
        })

        await flushFrames()

        await waitFor(() => {
          const tooltip = element(
            card,
            '.recharts-tooltip-wrapper',
          )

          expect(tooltip).toBeVisible()

          expect(
            within(tooltip).getByText(label, {
              exact: true,
            }),
          ).toBeVisible()

          expect(
            within(tooltip).getByText(percentages[index], {
              exact: true,
            }),
          ).toBeVisible()
        })

        await act(async () => {
          fireEvent.mouseLeave(sector)
          fireEvent.mouseLeave(
            element(card, '.recharts-wrapper'),
          )
        })

        await flushFrames()

        await waitFor(() => {
          const tooltip = card.querySelector(
            '.recharts-tooltip-wrapper',
          )

          // Recharts может оставить скрытый контейнер в DOM.
          if (tooltip) {
            expect(tooltip).not.toBeVisible()
          }
        })
      }
    },
  )
})
