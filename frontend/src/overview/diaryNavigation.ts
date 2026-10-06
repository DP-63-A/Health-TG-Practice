export type ChartKind =
  | 'nutrition'
  | 'sleep'
  | 'steps'
  | 'checkin'

type DiaryEntryType =
  | 'meal'
  | 'metrics'
  | 'checkin'

const diaryEntryTypes: Record<ChartKind, DiaryEntryType> = {
  nutrition: 'meal',
  sleep: 'metrics',
  steps: 'metrics',
  checkin: 'checkin',
}

export function buildDiaryUrl(
  date: string,
  kind: ChartKind,
): string {
  const params = new URLSearchParams({
    from: date,
    to: date,
    type: diaryEntryTypes[kind],
  })

  return `/diary?${params.toString()}`
}
