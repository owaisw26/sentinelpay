import type { AuthSession, Role } from './types'

let managerPromise: Promise<import('oidc-client-ts').UserManager> | undefined

async function manager() {
  if (!managerPromise) {
    managerPromise = import('oidc-client-ts').then(({ InMemoryWebStorage, UserManager, WebStorageStateStore }) => {
      const authority = import.meta.env.VITE_COGNITO_AUTHORITY
      const clientId = import.meta.env.VITE_COGNITO_CLIENT_ID
      const resource = import.meta.env.VITE_COGNITO_API_AUDIENCE
      if (!authority || !clientId || !resource) {
        throw new Error('Cognito environment configuration is incomplete')
      }
      return new UserManager({
        authority,
        client_id: clientId,
        redirect_uri: `${window.location.origin}/login`,
        post_logout_redirect_uri: `${window.location.origin}/login`,
        response_type: 'code',
        scope: import.meta.env.VITE_COGNITO_SCOPES ?? 'openid profile email',
        resource,
        automaticSilentRenew: true,
        stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
        userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
      })
    })
  }
  return managerPromise
}

function sessionFromUser(user: import('oidc-client-ts').User): AuthSession {
  const rawGroups = user.profile['cognito:groups']
  const groups = Array.isArray(rawGroups) ? rawGroups.map(String) : []
  const role: Role = groups.includes('ANALYST') ? 'ANALYST' : 'CUSTOMER'
  return {
    accessToken: user.access_token,
    subject: user.profile.sub,
    name: String(user.profile.name ?? user.profile.email ?? 'SentinelPay user'),
    role,
  }
}

export async function startCloudLogin() {
  await (await manager()).signinRedirect()
}

export async function finishCloudLogin(): Promise<AuthSession | null> {
  if (!new URLSearchParams(window.location.search).has('code')) return null
  const user = await (await manager()).signinRedirectCallback()
  window.history.replaceState({}, document.title, '/login')
  const session = sessionFromUser(user)
  const response = await fetch('/api/users', {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${session.accessToken}`,
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ name: session.name }),
  })
  if (!response.ok) throw new Error('SentinelPay user provisioning failed')
  return session
}

export async function cloudLogout() {
  await (await manager()).signoutRedirect()
}
