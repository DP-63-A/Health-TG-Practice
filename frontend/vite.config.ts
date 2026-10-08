import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'
import { fileURLToPath } from 'node:url'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': {
        target: process.env.LOCAL_API_ORIGIN || 'http://127.0.0.1:8081',
        changeOrigin: true,
      },
    },
  },
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
    pool: 'vmThreads',
    setupFiles: './src/setupTests.ts',
  },
})
