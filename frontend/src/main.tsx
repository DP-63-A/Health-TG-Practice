import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import { AuthGate } from './auth/AuthGate'
import { AuthProvider } from './auth/AuthProvider'
import './index.css'
import { RefreshProvider } from './refresh/RefreshProvider'
import { router } from './router/router'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AuthProvider>
      <AuthGate>
        <RefreshProvider>
          <RouterProvider router={router} />
        </RefreshProvider>
      </AuthGate>
    </AuthProvider>
  </StrictMode>,
)
