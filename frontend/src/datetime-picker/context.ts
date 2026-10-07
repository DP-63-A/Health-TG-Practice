export interface PickerContext {
  token: string
  revision: number
  zone: string
  label: string
  withTime: boolean
  dateLocked: boolean
  timeLocked: boolean
  date: string
  time: string
}

export function readPickerContext(fragment: string): PickerContext | null {
  if (fragment.length > 2048) return null
  const params = new URLSearchParams(fragment.replace(/^#/, ''))
  const token = params.get('token') ?? ''
  const revision = Number(params.get('revision'))
  const zone = params.get('zone') ?? ''
  const label = params.get('label') ?? ''
  const date = params.get('date') ?? ''
  const time = params.get('time') ?? ''
  if (!/^[a-f0-9]{64}$/.test(token) || !Number.isSafeInteger(revision) || revision < 1
      || zone.length > 80 || label.length > 120 || !label
      || (date && !/^\d{4}-\d{2}-\d{2}$/.test(date))
      || (time && !/^\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?$/.test(time))) return null
  try { new Intl.DateTimeFormat('ru', { timeZone: zone }).format() } catch { return null }
  for (const key of ['withTime', 'dateLocked', 'timeLocked']) {
    if (!['true', 'false'].includes(params.get(key) ?? '')) return null
  }
  const withTime = params.get('withTime') === 'true'
  const dateLocked = params.get('dateLocked') === 'true'
  const timeLocked = params.get('timeLocked') === 'true'
  if ((dateLocked && !date) || (timeLocked && (!withTime || !time))) return null
  return { token, revision, zone, label, date, time, withTime, dateLocked, timeLocked }
}
