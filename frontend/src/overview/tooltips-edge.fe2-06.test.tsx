
/// <reference types="node" />

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cwd } from 'node:process'

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

import { NutritionChart } from '../components/Overview-components/charts/NutritionChart'
import { SleepChart } from '../components/Overview-components/charts/SleepChart'
import { StepsChart } from '../components/Overview-components/charts/StepsChart'

type Kind = 'nutrition' | 'sleep' | 'steps'

interface EdgeCase {
  name: string
  kind: Kind
  input: 'fractional-energy' | 'zero'
  interaction: 'keyboard' | 'mouse'
  date: string
  expected: string
}

const cases: EdgeCase[] = [
  {
    name: 'дробная энергия — клавиатура',
    kind: 'nutrition',
    input: 'fractional-energy',
    interaction: 'keyboard',
    date: '19.09.2026',
    expected: '847.5 kcal',
  },
  {
    name: 'дробная энергия — мышь',
    kind: 'nutrition',
    input: 'fractional-energy',
    interaction: 'mouse',
    date: '19.09.2026',
    expected: '847.5 kcal',
  },
  {
    name: 'настоящие нулевые калории',
    kind: 'nutrition',
    input: 'zero',
    interaction: 'keyboard',
    date: '19.09.2026',
    expected: '0 kcal',
  },
  {
    name: 'настоящий нулевой сон',
    kind: 'sleep',
    input: 'zero',
    interaction: 'keyboard',
    date: '14.09.2026',
    expected: '0 min',
  },
  {
    name: 'настоящие нулевые steps',
    kind: 'steps',
    input: 'zero',
    interaction: 'keyboard',
    date: '19.09.2026',
    expected: '0 steps',
  },
]

const names: Record<Kind, string> = {
  nutrition: 'Nutrition',
  sleep: 'Sleep',
  steps: 'Steps',
}

function energyOracle(): number {
  const path = resolve(
    cwd(),
    '..',
    'contracts',
    'fixtures',
    'analytics_expected_mass_changed.json',
  )

  const oracle = JSON.parse(
    readFileSync(path, 'utf8'),
  ) as { energy_kcal: number }

  return oracle.energy_kcal
}

function renderInput(kind: Kind, value: number) {
  const normal = readContractFixture('normal')

  // Одиночные компонентные точки с метаданными normal.
  // Это не полные согласованные ответы аналитики BE3.
  switch (kind) {
    case 'nutrition':
      return render(
        <NutritionChart
          series={[{
            ...normal.series.nutrition[0],
            energy_kcal: value,
          }]}
        />,
      )
    case 'sleep':
      return render(
        <SleepChart
          series={[{
            ...normal.series.sleep[0],
            value,
          }]}
        />,
      )
    case 'steps':
      return render(
        <StepsChart
          series={[{
            ...normal.series.steps[0],
            value,
          }]}
        />,
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

  if (raw === null || !Number.isFinite(Number(raw))) {
    throw new Error(`Некорректный атрибут ${name}: ${raw}`)
  }

  return Number(raw)
}

let nextFrameId = 0
const frames = new Map<number, FrameRequestCallback>()

async function flushFrames() {
  await act(async () => {
    let iterations = 0

    while (frames.size > 0) {
      if (++iterations > 50) {
        throw new Error('Отрисовка не завершилась')
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
    .mockReturnValue(new DOMRect(0, 0, 640, 320))

  // ResponsiveContainer прочитает начальный размер
  // через getBoundingClientRect.
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
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

describe('FE2-06: дроби и настоящие нули в tooltip', () => {
  it.each(cases)('$name', async (oracle) => {
    const value =
      oracle.input === 'fractional-energy'
        ? energyOracle()
        : 0

    renderInput(oracle.kind, value)

    await flushFrames()

    const chart = screen.getByRole('region', {
      name: names[oracle.kind],
    })

    await waitFor(() => {
      expect(
        chart.querySelector(
          '.recharts-wrapper svg.recharts-surface',
        ),
      ).toBeInTheDocument()
    })

    const wrapper = element(chart, '.recharts-wrapper')
    const tooltipSelector = `.${oracle.kind}-chart__tooltip`

    expect(
      chart.querySelector(tooltipSelector),
    ).not.toBeInTheDocument()

    if (oracle.interaction === 'keyboard') {
      await act(async () => {
        fireEvent.focus(wrapper)
      })
    } else {
      const bar = element(
        chart,
        '.recharts-bar-rectangle path.recharts-rectangle',
      )

      const x = numericAttribute(bar, 'x')
      const y = numericAttribute(bar, 'y')
      const width = numericAttribute(bar, 'width')
      const height = numericAttribute(bar, 'height')

      expect(width).toBeGreaterThan(0)
      expect(height).toBeGreaterThan(0)

      const coordinates = {
        clientX: x + width / 2,
        clientY: y + height / 2,
      }

      await act(async () => {
        fireEvent.mouseEnter(bar, coordinates)
        fireEvent.mouseMove(bar, coordinates)
      })
    }

    await flushFrames()

    await waitFor(() => {
      const tooltip = element(chart, tooltipSelector)

      expect(tooltip).toBeVisible()

      expect(
        within(tooltip).getByText(oracle.date, {
          exact: true,
        }),
      ).toBeVisible()

      expect(
        within(tooltip).getByText(oracle.expected, {
          exact: true,
        }),
      ).toBeVisible()

      expect(
        within(tooltip).queryByText('No data', {
          exact: true,
        }),
      ).not.toBeInTheDocument()

      expect(
        within(tooltip).queryByText('—', {
          exact: true,
        }),
      ).not.toBeInTheDocument()

      if (oracle.input === 'fractional-energy') {
        expect(
          within(tooltip).queryByText('848 kcal', {
            exact: true,
          }),
        ).not.toBeInTheDocument()

        expect(
          within(tooltip).queryByText('847 kcal', {
            exact: true,
          }),
        ).not.toBeInTheDocument()
      }
    })
  })
})
