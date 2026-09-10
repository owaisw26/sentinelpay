const riskReasonLabels: Record<string, string> = {
  AMOUNT_GTE_AUD_5000: 'Amount at least AUD 5,000',
  FIRST_TIME_PAYEE: 'First-time payee',
  DEVICE_CHANGED: 'New device',
  FIVE_TRANSACTIONS_10_MINUTES: 'Five payments within 10 minutes',
  NAMECHECK_MISMATCH_ACCEPTED: 'Payee name mismatch accepted',
  ANOMALOUS_AMOUNT: 'Unusual payment amount',
  ANOMALOUS_FIRST_TIME_PAYEE: 'Unusual first-time payee',
  ANOMALOUS_DEVICE_CHANGE: 'Unusual device change',
  ANOMALOUS_TRANSACTION_VELOCITY: 'Unusual payment frequency',
  ANOMALOUS_NAMECHECK_MISMATCH: 'Unusual payee-name mismatch',
  VELOCITY_SPIKE: 'Unusual payment frequency',
}

export function formatEnumLabel(value: string): string {
  const words = value.replaceAll('_', ' ').toLowerCase()
  return words.charAt(0).toUpperCase() + words.slice(1)
}

export function formatRiskReason(reasonCode: string): string {
  return riskReasonLabels[reasonCode] ?? formatEnumLabel(reasonCode)
}
