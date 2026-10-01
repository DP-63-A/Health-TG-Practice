import { expect, it } from 'vitest'
import html from '../index.html?raw'

it('loads the official Telegram SDK in head before the application module', () => {
  const document = new DOMParser().parseFromString(html, 'text/html')
  const scripts = [...document.querySelectorAll('script')]
  const sdk = document.head.querySelector('script')
  const entrypoint = document.querySelector<HTMLScriptElement>('script[type="module"][src="/src/main.tsx"]')

  expect(sdk?.getAttribute('src')).toBe('https://telegram.org/js/telegram-web-app.js?63')
  expect(scripts[0]).toBe(sdk)
  expect(sdk?.hasAttribute('async')).toBe(false)
  expect(sdk?.hasAttribute('defer')).toBe(false)
  expect(sdk?.hasAttribute('type')).toBe(false)
  expect(entrypoint).not.toBeNull()
  expect(scripts.indexOf(sdk!)).toBeLessThan(scripts.indexOf(entrypoint!))
})
