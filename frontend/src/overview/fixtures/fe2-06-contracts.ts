/// <reference types="node" />

import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { cwd } from 'node:process'

import type { AnalyticsResponse } from '../analytics.types'

const contractFiles = {
  normal: 'analytics_expected_normal.json',
  empty: 'analytics_expected_empty.json',
  gaps: 'analytics_expected_gaps.json',
  dedup: 'analytics_expected_dedup.json',
  filtered: 'analytics_expected_filtered.json',
} as const

export type ContractFixture = keyof typeof contractFiles

export function readContractFixture(
  name: ContractFixture,
): AnalyticsResponse {
  const path = resolve(
    cwd(),
    '..',
    'contracts',
    'fixtures',
    contractFiles[name],
  )

  return JSON.parse(
    readFileSync(path, 'utf8'),
  ) as AnalyticsResponse
}