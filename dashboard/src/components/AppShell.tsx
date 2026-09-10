import { useAuth } from '../auth/AuthContext'

export function AppShell({ children }: { children: React.ReactNode }) {
  const { session, logout } = useAuth()
  return (
    <div className="app-shell">
      <header className="topbar">
        <a className="brand" href={session?.role === 'ANALYST' ? '/analyst' : '/customer'}>
          <span className="brand-mark">S</span>
          <span>SentinelPay</span>
        </a>
        <div className="identity">
          <span className="role-pill">{session?.role === 'ANALYST' ? 'Operations analyst' : 'Customer'}</span>
          <span className="identity-name">{session?.name}</span>
          <button className="button button-quiet" type="button" onClick={() => void logout()}>Sign out</button>
        </div>
      </header>
      <main className="workspace">{children}</main>
    </div>
  )
}
