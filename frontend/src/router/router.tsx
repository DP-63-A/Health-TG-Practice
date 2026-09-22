import { createBrowserRouter, Navigate } from 'react-router-dom'
import AppLayout from '../components/AppLayout'
import DiaryPage from '../pages/DiaryPage'
import EntryPage from '../pages/EntryPage'
import OverviewPage from '../pages/OverviewPage'

export const appRoutes = [
  {
    path: '/',
    element: <AppLayout />,
    children: [
      {
        index: true,
        element: <Navigate to="/diary" replace />,
      },
      {
        path: 'diary',
        element: <DiaryPage />,
      },
      { path: 'diary/:id', element: <EntryPage /> },
      {
        path: 'overview',
        element: <OverviewPage />,
      },
    ],
  },
]

export const router = createBrowserRouter(appRoutes)
