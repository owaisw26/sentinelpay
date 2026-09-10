export type Wallet = {
  walletId: string
  userId: string
  currency: string
  balance: number
  reservedBalance: number
  availableBalance: number
  createdAt: string
}

export type Payment = {
  id: string
  senderWalletId: string
  receiverWalletId: string
  amount: number
  currency: string
  reference: string
  status: string
  createdAt: string
  updatedAt: string
}

export type CursorPage<T> = { items: T[]; nextCursor?: string }

export type PayeeCheck = {
  checkId: string
  outcome: 'MATCH' | 'CLOSE_MATCH' | 'NO_MATCH'
  reasonCode: string
  expiresAt: string
}

export type HeldPayment = {
  paymentId: string
  senderWalletId: string
  receiverWalletId: string
  amount: number
  currency: string
  reference: string
  status: string
  version: number
  createdAt: string
  updatedAt: string
  risk: {
    decisionId: string
    score: number
    reasonCodes: string[]
    featureVersion: string
    rulesetVersion: string
    modelVersion: string
    matchedRuleIds: string[]
    decidedAt: string
  }
}

export type Discrepancy = {
  discrepancyId: string
  paymentId: string
  type: string
  status: string
  localPaymentStatus: string
  providerStatus: string
  recommendedAction: 'CAPTURE' | 'RELEASE' | 'IGNORE'
  detectedAt: string
  lastObservedAt: string
  version: number
  resolutionAction?: string
  resolutionReason?: string
}

export type RuleProposal = {
  proposalId: string
  status: string
  version: number
  model: string
  candidate: unknown
  impact: unknown
  validationFailures: string[]
}

export type RuleSet = {
  version: number
  operation: string
  rules: unknown[]
  createdAt: string
  reason: string
}

export type AuditEntry = {
  auditId: string
  action: string
  actorId: string
  reason?: string
  occurredAt: string
}
