import { createContext, useCallback, useContext, useEffect, useMemo, useState } from 'react'
import type { AuthSession, DevPersona } from './types'

type AuthContextValue = {
  mode: 'local' | 'cognito'
  session: AuthSession | null
  loading: boolean
  error: string | null
  loginLocal: (persona: DevPersona) => Promise<void>
  loginCloud: () => Promise<void>
  logout: () => Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)
const mode: 'local' | 'cognito' = import.meta.env.VITE_AUTH_MODE === 'cognito' ? 'cognito' : 'local'

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [session, setSession] = useState<AuthSession | null>(null)
  const [loading, setLoading] = useState(mode === 'cognito')
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    if (mode !== 'cognito') return
    let active = true
    import('./oidc').then(({ finishCloudLogin }) => finishCloudLogin())
      .then((result) => { if (active && result) setSession(result) })
      .catch((cause: unknown) => { if (active) setError(cause instanceof Error ? cause.message : 'Sign-in failed') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [])

  const loginLocal = useCallback(async (persona: DevPersona) => {
    setError(null)
    setLoading(true)
    try {
      const response = await fetch(`/api/dev/token/${persona.userId}`, { method: 'POST' })
      if (!response.ok) throw new Error('Could not issue the local development token')
      const accessToken = await response.text()
      setSession({ accessToken, subject: persona.userId, name: persona.name, role: persona.role })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Local sign-in failed')
    } finally {
      setLoading(false)
    }
  }, [])

  const loginCloud = useCallback(async () => {
    setError(null)
    setLoading(true)
    try {
      const { startCloudLogin } = await import('./oidc')
      await startCloudLogin()
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Sign-in failed')
      setLoading(false)
    }
  }, [])

  const logout = useCallback(async () => {
    setSession(null)
    if (mode === 'cognito') {
      const { cloudLogout } = await import('./oidc')
      await cloudLogout()
    }
  }, [])

  const value = useMemo(() => ({ mode, session, loading, error, loginLocal, loginCloud, logout }), [session, loading, error, loginLocal, loginCloud, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

// The provider and its hook intentionally share the private context instance.
// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}
