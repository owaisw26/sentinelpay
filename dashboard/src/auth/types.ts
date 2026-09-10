export type Role = 'CUSTOMER' | 'ANALYST'

export type AuthSession = {
  accessToken: string
  subject: string
  name: string
  role: Role
}

export type DevPersona = {
  userId: string
  name: string
  role: Role
}
