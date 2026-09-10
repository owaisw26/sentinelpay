import { lazy, Suspense } from 'react'
import { Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from './components/AppShell'
import { LoginPage } from './components/LoginPage'
import { useAuth } from './auth/AuthContext'
import './App.css'

const CustomerDashboard = lazy(() => import('./pages/CustomerDashboard'))
const AnalystDashboard = lazy(() => import('./pages/AnalystDashboard'))

function HomeRedirect() {
  const { session } = useAuth()
  if (!session) return <Navigate to="/login" replace />
  return <Navigate to={session.role === 'ANALYST' ? '/analyst' : '/customer'} replace />
}

function ProtectedRoute({ role, children }: { role: 'CUSTOMER' | 'ANALYST'; children: React.ReactNode }) {
  const { session } = useAuth()
  if (!session) return <Navigate to="/login" replace />
  if (session.role !== role) return <HomeRedirect />
  return <AppShell>{children}</AppShell>
}

export default function App() {
  return (
    <Suspense fallback={<div className="page-loader">Loading SentinelPay…</div>}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/customer" element={<ProtectedRoute role="CUSTOMER"><CustomerDashboard /></ProtectedRoute>} />
        <Route path="/analyst" element={<ProtectedRoute role="ANALYST"><AnalystDashboard /></ProtectedRoute>} />
        <Route path="*" element={<HomeRedirect />} />
      </Routes>
    </Suspense>
  )
}
