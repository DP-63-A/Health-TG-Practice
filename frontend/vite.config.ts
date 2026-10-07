import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  test: {
    env: { VITE_API_MODE: 'fixture' },
    environment: 'jsdom',
    pool: 'vmThreads',
    setupFiles: './src/setupTests.ts',
  },
})
