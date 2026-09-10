import { useEffect, useState } from 'react'
import { Navigate } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import type { DevPersona } from '../auth/types'

export function LoginPage() {
  const { mode, session, loading, error, loginLocal, loginCloud } = useAuth()
  const [personas, setPersonas] = useState<DevPersona[]>([])
  const [personaError, setPersonaError] = useState<string | null>(null)

  useEffect(() => {
    if (mode !== 'local') return
    const controller = new AbortController()
    fetch('/api/dev/personas', { signal: controller.signal })
      .then(async (response) => {
        if (!response.ok) throw new Error('Start the payments service with the local profile')
        return response.json() as Promise<DevPersona[]>
      })
      .then(setPersonas)
      .catch((cause: unknown) => {
        if (!controller.signal.aborted) setPersonaError(cause instanceof Error ? cause.message : 'Could not load local users')
      })
    return () => controller.abort()
  }, [mode])

  if (session) return <Navigate to={session.role === 'ANALYST' ? '/analyst' : '/customer'} replace />

  return (
    <main className="login-page">
      <section className="login-intro" aria-labelledby="welcome-heading">
        <div className="brand brand-large"><span className="brand-mark">S</span><span>SentinelPay</span></div>
        <div className="login-welcome">
          <h1 id="welcome-heading">Welcome to SentinelPay.</h1>
          <p>Sign in to continue.</p>
        </div>
      </section>
      <div className="login-access">
        <section className="login-panel" aria-labelledby="login-heading">
          <div className="login-panel-heading">
            <h2 id="login-heading">Select a workspace</h2>
            {mode === 'local' ? <span className="environment-label">Local environment</span> : null}
          </div>
          {mode === 'local' ? (
            <div className="persona-list">
              {personas.map((persona) => (
                <button className="persona" type="button" key={persona.userId} disabled={loading} onClick={() => void loginLocal(persona)}>
                  <span className={`avatar ${persona.role === 'ANALYST' ? 'avatar-analyst' : ''}`}>{persona.name.slice(0, 1)}</span>
                  <span><strong>{persona.name}</strong><small>{persona.role === 'ANALYST' ? 'Analyst' : 'Customer'}</small></span>
                  <span aria-hidden="true">→</span>
                </button>
              ))}
              {personas.length === 0 && !personaError ? <p className="muted">Loading local profiles…</p> : null}
            </div>
          ) : (
            <button className="button button-primary button-wide" type="button" disabled={loading} onClick={() => void loginCloud()}>
              Continue with Cognito
            </button>
          )}
          {error || personaError ? <p className="notice notice-error">{error ?? personaError}</p> : null}
        </section>
      </div>
    </main>
  )
}
