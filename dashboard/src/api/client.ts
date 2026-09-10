export type Problem = {
  title?: string
  detail?: string
  errorCode?: string
  status?: number
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: Problem

  constructor(status: number, problem: Problem) {
    super(problem.detail ?? problem.title ?? `Request failed (${status})`)
    this.status = status
    this.problem = problem
  }
}

export async function apiRequest<T>(
  accessToken: string,
  path: string,
  init: RequestInit = {},
): Promise<T> {
  const headers = new Headers(init.headers)
  headers.set('Authorization', `Bearer ${accessToken}`)
  if (init.body && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json')
  const response = await fetch(`/api${path}`, { ...init, headers })
  if (!response.ok) {
    let problem: Problem = { status: response.status }
    try { problem = await response.json() as Problem } catch { /* non-JSON upstream error */ }
    throw new ApiError(response.status, problem)
  }
  if (response.status === 204) return undefined as T
  return response.json() as Promise<T>
}
