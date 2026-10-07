import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  build: {
    rollupOptions: {
      input: {
        main: fileURLToPath(new URL('./index.html', import.meta.url)),
        datetimePicker: fileURLToPath(new URL('./datetime-picker.html', import.meta.url)),
      },
    },
  },
  test: {
    env: { VITE_API_MODE: 'fixture' },
    environment: 'jsdom',
    setupFiles: './src/setupTests.ts',
  },
})
