
/// <reference types="node" />

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cwd } from 'node:process'

import {
  render,
  screen,
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
import { SleepCard } from '../components/Overview-components/SleepCard'
import { StepsCard } from '../components/Overview-components/StepsCard'

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

function expectText(
  root: ParentNode,
  selector: string,
  expected: string,
) {
  const target = element(root, selector)

  expect(target).toBeVisible()
  expect(normalize(target.textContent)).toBe(expected)
}

function expectStat(
  root: HTMLElement,
  label: string,
  expected: string,
) {
  const parent = within(root)
    .getByText(label, { exact: true })
    .parentElement

  if (!parent) {
    throw new Error(`Не найден контейнер "${label}"`)
  }

  expectText(parent, 'strong', expected)
}

function directText(root: Element): string {
  return normalize(
    Array.from(root.childNodes)
      .filter((node) => node.nodeType === Node.TEXT_NODE)
      .map((node) => node.textContent ?? '')
      .join(''),
  )
}

beforeEach(() => {
  vi.stubGlobal(
    'ResizeObserver',
    class {
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = vi.fn()
    },
  )
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('FE2-06: дополнительные проверки отображения компонентов', () => {
  it('дробная энергия из независимого oracle отображается без потери дроби', () => {
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

    const normal = readContractFixture('normal')

    // Компонентный вход:
    // независимый oracle энергии + остальные поля normal.
    // Это не полный согласованный ответ аналитики BE3.
    render(
      <NutritionCard
        nutrition={{
          ...normal.cards.nutrition,
          energy_kcal: oracle.energy_kcal,
        }}
      />,
    )

    const card = screen.getByRole('region', {
      name: 'Калории и БЖУ',
    })

    expectText(
      card,
      '.nutrition-card__energy',
      '847,5 ккал',
    )

    expect(
      within(card).queryByText('848 ккал', { exact: true }),
    ).not.toBeInTheDocument()

    expect(
      within(card).queryByText('847 ккал', { exact: true }),
    ).not.toBeInTheDocument()
  })

  it('неполнота сохраняет известные значения и не заменяет null нулём', () => {
    // Синтетический вход компонента.
    // Числа не вычисляются из записей или backend-формул.
    render(
      <NutritionCard
        nutrition={{
          energy_kcal: 300,
          protein_g: null,
          fat_g: 0,
          carbs_g: 35,
          incomplete: true,
          meals_with_energy: 1,
        }}
      />,
    )

    const card = screen.getByRole('region', {
      name: 'Калории и БЖУ',
    })

    expectText(card, '.nutrition-card__energy', '300 ккал')
    expectStat(card, 'Белки', '— г')
    expectStat(card, 'Жиры', '0 г')
    expectStat(card, 'Углеводы', '35 г')

    const warning = within(card).getByRole('status')

    expect(warning).toBeVisible()
    expect(warning).toHaveTextContent(
      'Данные о рационе неполные.',
    )
    expect(warning).toHaveTextContent(
      'Показаны только записанные показатели.',
    )

    // При неизвестном нутриенте проценты состава
    // не должны изображать полный известный состав.
    expect(
      card.querySelector('.nutrition-card__legend'),
    ).not.toBeInTheDocument()

    expect(
      card.querySelector('.nutrition-card__chart'),
    ).not.toBeInTheDocument()
  })

  it('настоящие нулевые калории и нутриенты остаются числами', () => {
    // Синтетический вход компонента, не BE3 response.
    render(
      <NutritionCard
        nutrition={{
          energy_kcal: 0,
          protein_g: 0,
          fat_g: 0,
          carbs_g: 0,
          incomplete: false,
          meals_with_energy: 1,
        }}
      />,
    )

    const card = screen.getByRole('region', {
      name: 'Калории и БЖУ',
    })

    expectText(card, '.nutrition-card__energy', '0 ккал')
    expectStat(card, 'Белки', '0 г')
    expectStat(card, 'Жиры', '0 г')
    expectStat(card, 'Углеводы', '0 г')

    expect(
      within(card).queryByRole('status'),
    ).not.toBeInTheDocument()

    expect(
      within(card).queryByText('— ккал', { exact: true }),
    ).not.toBeInTheDocument()

    // У нулевого состава не должно быть выдуманных процентов.
    expect(
      card.querySelector('.nutrition-card__legend'),
    ).not.toBeInTheDocument()
  })

  it('нулевой сон остаётся значением и сохраняет день с данными', () => {
    const period = readContractFixture('normal').period

    // Синтетический вход компонента.
    render(
      <SleepCard
        sleep={{
          total_minutes: 0,
          average_minutes: 0,
          days_with_data: 1,
        }}
        period={period}
      />,
    )

    const card = screen.getByRole('region', {
      name: 'Аналитика сна',
    })

    expectText(card, '.sleep-card__total', '0 мин')
    expectStat(card, 'Среднее', '0 мин')
    expectStat(card, 'Дней с данными', '1')

    expectText(
      card,
      '.sleep-card__period',
      '13.09.2026 — 19.09.2026',
    )
  })

  it('нулевые шаги остаются значением и сохраняют день с данными', () => {
    const period = readContractFixture('normal').period

    // Синтетический вход компонента.
    render(
      <StepsCard
        steps={{
          total: 0,
          average: 0,
          days_with_data: 1,
        }}
        period={period}
      />,
    )

    const card = screen.getByRole('region', {
      name: 'Аналитика шагов',
    })

    const total = element(card, '.steps-card__total')

    expect(total).toBeVisible()
    expect(directText(total)).toBe('0')

    expect(
      within(total).getByText('шагов', { exact: true }),
    ).toBeVisible()

    expectStat(card, 'Среднее', '0')
    expectStat(card, 'Дней с данными', '1')

    // Нулевое измерение учитывается как имеющиеся данные.
    expectText(
      card,
      '.steps-card__coverage-description',
      'Данные записаны за 1 из 7 дней',
    )

    expect(
      within(card).getByRole('progressbar'),
    ).toHaveAttribute('aria-valuenow', '14')
  })
})
