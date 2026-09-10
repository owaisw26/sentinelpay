import { useCallback, useEffect, useState } from 'react'
import { apiRequest } from '../api/client'
import type { AuditEntry, CursorPage, Discrepancy, HeldPayment, RuleProposal, RuleSet } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { StatusBadge } from '../components/StatusBadge'
import { formatEnumLabel, formatRiskReason } from '../utils/labels'

const money = new Intl.NumberFormat('en-AU', { style: 'currency', currency: 'AUD' })
const time = new Intl.DateTimeFormat('en-AU', { dateStyle: 'medium', timeStyle: 'short' })

function HeldCase({ item, token, onChanged }: { item: HeldPayment; token: string; onChanged: () => Promise<void> }) {
  const [reason, setReason] = useState('')
  const [audit, setAudit] = useState<AuditEntry[] | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const decide = async (action: 'APPROVE' | 'BLOCK') => {
    if (reason.trim().length < 3) { setError('Add a review reason before deciding.'); return }
    setBusy(true); setError(null)
    try {
      await apiRequest<HeldPayment>(token, `/analyst/held-payments/${item.paymentId}/decision`, {
        method: 'POST',
        headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ expectedVersion: item.version, action, reason }),
      })
      await onChanged()
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Decision failed') }
    finally { setBusy(false) }
  }

  const loadAudit = async () => {
    try { setAudit(await apiRequest<AuditEntry[]>(token, `/analyst/held-payments/${item.paymentId}/audit`)) }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Audit trail failed') }
  }

  return (
    <details className="review-row">
      <summary>
        <div className="review-reference"><strong>{item.reference}</strong><span className="mono">{item.paymentId.slice(0, 8)}</span></div>
        <div className="review-reasons">{item.risk.reasonCodes.slice(0, 2).map((reasonCode) => <span key={reasonCode}>{formatRiskReason(reasonCode)}</span>)}</div>
        <div className="review-score"><strong>{item.risk.score}</strong><span>Risk</span></div>
        <div className="review-amount"><strong>{money.format(item.amount)}</strong><span>{time.format(new Date(item.createdAt))}</span></div>
        <span className="review-open">Review</span>
      </summary>
      <div className="review-details">
        <div>
          <p className="detail-label">Risk signals</p>
          <div className="reason-list">{item.risk.reasonCodes.map((reasonCode) => <span key={reasonCode}>{formatRiskReason(reasonCode)}</span>)}</div>
          <dl className="metadata"><div><dt>Feature version</dt><dd>{item.risk.featureVersion}</dd></div><div><dt>Ruleset</dt><dd>{item.risk.rulesetVersion}</dd></div><div><dt>Model</dt><dd>{item.risk.modelVersion}</dd></div></dl>
        </div>
        <div className="review-action">
          <label className="field"><span>Decision rationale</span><textarea maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} placeholder="Record the evidence behind this decision" /></label>
          {error ? <p className="notice notice-error">{error}</p> : null}
          <div className="button-row"><button className="button button-primary" disabled={busy} type="button" onClick={() => void decide('APPROVE')}>Approve</button><button className="button button-danger" disabled={busy} type="button" onClick={() => void decide('BLOCK')}>Block</button><button className="button button-quiet" type="button" onClick={() => void loadAudit()}>View audit</button></div>
        </div>
        {audit ? <ol className="audit-list review-audit">{audit.map((entry) => <li key={entry.auditId}><StatusBadge status={entry.action} /><span>{entry.reason ?? 'Viewed by analyst'}</span><time>{time.format(new Date(entry.occurredAt))}</time></li>)}</ol> : null}
      </div>
    </details>
  )
}

function DiscrepancyCard({ item, token, onChanged }: { item: Discrepancy; token: string; onChanged: () => Promise<void> }) {
  const [reason, setReason] = useState('')
  const [audit, setAudit] = useState<AuditEntry[] | null>(null)
  const [error, setError] = useState<string | null>(null)
  const resolve = async () => {
    if (reason.trim().length === 0) { setError('Add a resolution reason.'); return }
    try {
      await apiRequest(token, `/analyst/reconciliation/discrepancies/${item.discrepancyId}/resolve`, {
        method: 'POST', headers: { 'Idempotency-Key': crypto.randomUUID() },
        body: JSON.stringify({ expectedVersion: item.version, action: item.recommendedAction, reason }),
      })
      await onChanged()
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Resolution failed') }
  }
  const showAudit = async () => {
    try { setAudit(await apiRequest<AuditEntry[]>(token, `/analyst/reconciliation/discrepancies/${item.discrepancyId}/audit`)) }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Audit trail failed') }
  }
  return (
    <article className="discrepancy-card">
      <div className="discrepancy-summary">
        <p className="discrepancy-type">{formatEnumLabel(item.type)}</p>
        <h3>Payment {item.paymentId.slice(0, 8)}</h3>
        <dl className="discrepancy-statuses">
          <div><dt>Local</dt><dd>{formatEnumLabel(item.localPaymentStatus)}</dd></div>
          <div><dt>Provider</dt><dd>{formatEnumLabel(item.providerStatus)}</dd></div>
        </dl>
      </div>
      <div className="resolution">
        <label className="field">
          <span>Resolution note</span>
          <input maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} placeholder="Add a reason" />
        </label>
        <div className="button-row">
          <button className="button button-primary" type="button" onClick={() => void resolve()}>{formatEnumLabel(item.recommendedAction)}</button>
          <button className="button button-quiet" type="button" onClick={() => void showAudit()}>View audit</button>
        </div>
      </div>
      {error ? <p className="notice notice-error">{error}</p> : null}
      {audit ? <ol className="audit-list">{audit.map((entry) => <li key={entry.auditId}><span>{formatEnumLabel(entry.action)}</span><span>{entry.actorId}</span><time>{time.format(new Date(entry.occurredAt))}</time></li>)}</ol> : null}
    </article>
  )
}

function RuleApproval({ token, active, onChanged }: { token: string; active: RuleSet | null; onChanged: () => Promise<void> }) {
  const [proposal, setProposal] = useState<RuleProposal | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const generate = async () => {
    setBusy(true); setError(null)
    try { setProposal(await apiRequest<RuleProposal>(token, '/analyst/risk/rule-proposals', { method: 'POST' })) }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Proposal generation failed') }
    finally { setBusy(false) }
  }
  const review = async (action: 'approve' | 'reject') => {
    if (!proposal || reason.trim().length < 3) { setError('Add a review reason first.'); return }
    setBusy(true); setError(null)
    try {
      await apiRequest(token, `/analyst/risk/rule-proposals/${proposal.proposalId}/${action}`, { method: 'POST', body: JSON.stringify({ expectedVersion: proposal.version, reason }) })
      setProposal(null); setReason(''); await onChanged()
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Rule review failed') }
    finally { setBusy(false) }
  }
  return (
    <section className="panel operations-panel rules-panel">
      <div className="panel-heading">
        <div><h2>Risk rule approval</h2><p className="section-description">Generate and review proposed fraud rules before they become active.</p></div>
        <p className="ruleset-version"><span>Active ruleset</span><strong>v{active?.version ?? '—'}</strong></p>
      </div>
      {proposal ? (
        <div className="proposal rules-content">
          <div className="proposal-meta"><StatusBadge status={proposal.status} /><span>{proposal.model}</span></div>
          <div className="json-grid"><div><h3>Candidate</h3><pre>{JSON.stringify(proposal.candidate, null, 2)}</pre></div><div><h3>Held-out impact</h3><pre>{JSON.stringify(proposal.impact, null, 2)}</pre></div></div>
          <label className="field"><span>Review reason</span><textarea maxLength={500} value={reason} onChange={(event) => setReason(event.target.value)} /></label>
          <div className="button-row"><button className="button button-primary" disabled={busy} type="button" onClick={() => void review('approve')}>Approve draft</button><button className="button button-danger" disabled={busy} type="button" onClick={() => void review('reject')}>Reject</button></div>
        </div>
      ) : (
        <div className="rules-empty">
          <div><h3>No proposal awaiting review</h3><p>Create a draft from aggregated synthetic cases. It remains inactive until an analyst approves it.</p></div>
          <button className="button button-secondary" disabled={busy} type="button" onClick={() => void generate()}>{busy ? 'Generating…' : 'Generate proposal'}</button>
        </div>
      )}
      {error ? <p className="notice notice-error rules-notice">{error}</p> : null}
    </section>
  )
}

export default function AnalystDashboard() {
  const { session } = useAuth()
  const token = session!.accessToken
  const [held, setHeld] = useState<HeldPayment[]>([])
  const [discrepancies, setDiscrepancies] = useState<Discrepancy[]>([])
  const [heldCursor, setHeldCursor] = useState<string | undefined>()
  const [discrepancyCursor, setDiscrepancyCursor] = useState<string | undefined>()
  const [activeRules, setActiveRules] = useState<RuleSet | null>(null)
  const [activeView, setActiveView] = useState<'reviews' | 'reconciliation' | 'rules'>('reviews')
  const [error, setError] = useState<string | null>(null)
  const [running, setRunning] = useState(false)

  const load = useCallback(async () => {
    try {
      const [heldResult, discrepancyResult, ruleset] = await Promise.all([
        apiRequest<CursorPage<HeldPayment>>(token, '/analyst/held-payments?limit=20'),
        apiRequest<CursorPage<Discrepancy>>(token, '/analyst/reconciliation/discrepancies?status=OPEN&limit=20'),
        apiRequest<RuleSet>(token, '/analyst/risk/rulesets/active'),
      ])
      setHeld(heldResult.items); setHeldCursor(heldResult.nextCursor)
      setDiscrepancies(discrepancyResult.items); setDiscrepancyCursor(discrepancyResult.nextCursor)
      setActiveRules(ruleset); setError(null)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Operations data could not be loaded') }
  }, [token])

  const loadMoreHeld = async () => {
    if (!heldCursor) return
    try {
      const result = await apiRequest<CursorPage<HeldPayment>>(token, `/analyst/held-payments?limit=20&cursor=${encodeURIComponent(heldCursor)}`)
      setHeld((current) => [...current, ...result.items]); setHeldCursor(result.nextCursor)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load more held payments') }
  }

  const loadMoreDiscrepancies = async () => {
    if (!discrepancyCursor) return
    try {
      const result = await apiRequest<CursorPage<Discrepancy>>(token, `/analyst/reconciliation/discrepancies?status=OPEN&limit=20&cursor=${encodeURIComponent(discrepancyCursor)}`)
      setDiscrepancies((current) => [...current, ...result.items]); setDiscrepancyCursor(result.nextCursor)
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Could not load more discrepancies') }
  }
  useEffect(() => {
    const initialLoad = window.setTimeout(() => void load(), 0)
    return () => window.clearTimeout(initialLoad)
  }, [load])

  const runReconciliation = async () => {
    setRunning(true)
    try { await apiRequest(token, '/analyst/reconciliation/runs', { method: 'POST' }); await load() }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Reconciliation failed') }
    finally { setRunning(false) }
  }

  return (
    <div className="dashboard">
      <header className="page-heading">
        <div><p className="eyebrow">Operations</p><h1>Analyst workspace</h1><p>Review exceptions and record controlled decisions.</p></div>
        <button className="button button-secondary" type="button" onClick={() => void load()}>Refresh workspace</button>
      </header>
      {error ? <p className="notice notice-error">{error}</p> : null}
      <nav className="operations-tabs" aria-label="Analyst workflows">
        <button className={activeView === 'reviews' ? 'active' : ''} type="button" onClick={() => setActiveView('reviews')}><span>Payment reviews</span><strong>{held.length}</strong></button>
        <button className={activeView === 'reconciliation' ? 'active' : ''} type="button" onClick={() => setActiveView('reconciliation')}><span>Reconciliation</span><strong>{discrepancies.length}</strong></button>
        <button className={activeView === 'rules' ? 'active' : ''} type="button" onClick={() => setActiveView('rules')}><span>Risk rules</span><strong>v{activeRules?.version ?? '—'}</strong></button>
      </nav>
      {activeView === 'reviews' ? <section className="panel operations-panel">
        <div className="panel-heading"><div><h2>Payments requiring review</h2><p className="section-description">Open a payment to inspect its risk signals and record a decision.</p></div><StatusBadge status={held.length ? 'HELD' : 'RESOLVED'} /></div>
        <div className="review-list">{held.map((item) => <HeldCase key={item.paymentId} item={item} token={token} onChanged={load} />)}{held.length === 0 ? <p className="empty-state">No held payments need attention.</p> : null}</div>
        {heldCursor ? <div className="button-row pagination-row"><button className="button button-secondary" type="button" onClick={() => void loadMoreHeld()}>Load more held payments</button></div> : null}
      </section> : null}
      {activeView === 'reconciliation' ? <section className="panel operations-panel">
        <div className="panel-heading"><div><h2>Reconciliation discrepancies</h2><p className="section-description">Compare provider truth with the local payment ledger.</p></div><button className="button button-secondary" disabled={running} type="button" onClick={() => void runReconciliation()}>{running ? 'Scanning…' : 'Run reconciliation'}</button></div>
        <div className="stack">{discrepancies.map((item) => <DiscrepancyCard key={item.discrepancyId} item={item} token={token} onChanged={load} />)}{discrepancies.length === 0 ? <p className="empty-state">No open discrepancies.</p> : null}</div>
        {discrepancyCursor ? <div className="button-row pagination-row"><button className="button button-secondary" type="button" onClick={() => void loadMoreDiscrepancies()}>Load more discrepancies</button></div> : null}
      </section> : null}
      {activeView === 'rules' ? <RuleApproval token={token} active={activeRules} onChanged={load} /> : null}
    </div>
  )
}
