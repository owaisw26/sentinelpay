import { useCallback, useEffect, useState } from 'react'
import { apiRequest } from '../api/client'
import type { CursorPage, PayeeCheck, Payment, Wallet } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { StatusBadge } from '../components/StatusBadge'

const terminalStatuses = new Set(['SETTLED', 'FAILED', 'BLOCKED'])
const money = new Intl.NumberFormat('en-AU', { style: 'currency', currency: 'AUD' })
const time = new Intl.DateTimeFormat('en-AU', { dateStyle: 'medium', timeStyle: 'short' })

type PaymentDraft = { receiverWalletId: string; suppliedName: string; amount: string; reference: string }
const emptyDraft: PaymentDraft = { receiverWalletId: '', suppliedName: '', amount: '', reference: '' }

export default function CustomerDashboard() {
  const { session } = useAuth()
  const token = session!.accessToken
  const [wallets, setWallets] = useState<Wallet[]>([])
  const [payments, setPayments] = useState<Payment[]>([])
  const [nextCursor, setNextCursor] = useState<string | undefined>()
  const [draft, setDraft] = useState<PaymentDraft>(emptyDraft)
  const [pendingCheck, setPendingCheck] = useState<PayeeCheck | null>(null)
  const [walletCopied, setWalletCopied] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      const [walletResult, paymentResult] = await Promise.all([
        apiRequest<Wallet[]>(token, '/wallets'),
        apiRequest<CursorPage<Payment>>(token, '/payments?limit=20'),
      ])
      setWallets(walletResult)
      setPayments(paymentResult.items)
      setNextCursor(paymentResult.nextCursor)
      setError(null)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load payments')
    }
  }, [token])

  const loadMore = async () => {
    if (!nextCursor) return
    try {
      const result = await apiRequest<CursorPage<Payment>>(token, `/payments?limit=20&cursor=${encodeURIComponent(nextCursor)}`)
      setPayments((current) => [...current, ...result.items])
      setNextCursor(result.nextCursor)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not load more payments')
    }
  }

  useEffect(() => {
    const initialLoad = window.setTimeout(() => void load(), 0)
    return () => window.clearTimeout(initialLoad)
  }, [load])
  useEffect(() => {
    if (!payments.some((payment) => !terminalStatuses.has(payment.status))) return
    const timer = window.setInterval(() => void load(), 5000)
    return () => window.clearInterval(timer)
  }, [payments, load])

  const createPayment = async (check: PayeeCheck, acceptNameMismatch: boolean) => {
    const senderWallet = wallets[0]
    if (!senderWallet) throw new Error('Create an AUD wallet before paying')
    await apiRequest<Payment>(token, '/payments', {
      method: 'POST',
      headers: { 'Idempotency-Key': crypto.randomUUID() },
      body: JSON.stringify({
        senderWalletId: senderWallet.walletId,
        receiverWalletId: draft.receiverWalletId,
        amount: Number(draft.amount),
        currency: 'AUD',
        reference: draft.reference,
        payeeCheckId: check.checkId,
        acceptNameMismatch,
      }),
    })
    setDraft(emptyDraft)
    setPendingCheck(null)
    await load()
  }

  const verifyAndPay = async (event: React.FormEvent) => {
    event.preventDefault()
    const senderWallet = wallets[0]
    const requestedAmount = Number(draft.amount)
    if (senderWallet && requestedAmount > senderWallet.availableBalance) {
      setPendingCheck(null)
      setError(`Amount exceeds your available balance of ${money.format(senderWallet.availableBalance)}.`)
      return
    }
    setBusy(true)
    setError(null)
    try {
      const check = await apiRequest<PayeeCheck>(token, '/payee-checks', {
        method: 'POST',
        body: JSON.stringify({ receiverWalletId: draft.receiverWalletId, suppliedName: draft.suppliedName }),
      })
      if (check.outcome === 'MATCH') await createPayment(check, false)
      else setPendingCheck(check)
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Payment could not be created')
    } finally {
      setBusy(false)
    }
  }

  const confirmMismatch = async () => {
    if (!pendingCheck) return
    setBusy(true)
    try { await createPayment(pendingCheck, true) }
    catch (cause) { setError(cause instanceof Error ? cause.message : 'Payment could not be created') }
    finally { setBusy(false) }
  }

  const copyWalletId = async () => {
    if (!wallet) return
    try {
      await navigator.clipboard.writeText(wallet.walletId)
      setWalletCopied(true)
    } catch {
      setError('Could not copy the wallet ID')
    }
  }

  const wallet = wallets[0]
  return (
    <div className="dashboard">
      <header className="page-heading">
        <div><p className="eyebrow">Customer workspace</p><h1>Good {new Date().getHours() < 12 ? 'morning' : 'afternoon'}, {session?.name.split(' ')[0]}</h1><p>Verify the payee, send the payment, then follow its screening status.</p></div>
        <button className="button button-secondary" type="button" onClick={() => void load()}>Refresh</button>
      </header>

      {error ? <p className="notice notice-error">{error}</p> : null}
      <section className="customer-grid">
        <article className="balance-card">
          <div className="balance-card-header"><p className="eyebrow">Available balance</p><span>AUD</span></div>
          <strong className="balance">{money.format(wallet?.availableBalance ?? 0)}</strong>
          <p className="balance-caption">Available to send</p>
          <div className="balance-breakdown">
            <div><span>Ledger balance</span><strong>{money.format(wallet?.balance ?? 0)}</strong></div>
            <div><span>Reserved</span><strong>{money.format(wallet?.reservedBalance ?? 0)}</strong></div>
          </div>
          <div className="wallet-id">
            <div><span>Wallet ID</span><strong>{wallet?.walletId ?? 'Not created'}</strong><small>Share this ID to receive money</small></div>
            <button disabled={!wallet} type="button" onClick={() => void copyWalletId()}>{walletCopied ? 'Copied' : 'Copy'}</button>
          </div>
        </article>

        <article className="panel payment-form-panel">
          <div className="panel-heading"><div><p className="eyebrow">New payment</p><h2>Verify and send</h2></div></div>
          <form onSubmit={verifyAndPay} className="form-grid">
            <label className="field field-wide"><span>Receiver wallet ID</span><input required value={draft.receiverWalletId} onChange={(event) => { setDraft({ ...draft, receiverWalletId: event.target.value }); setPendingCheck(null) }} placeholder="20000000-…" /></label>
            <label className="field"><span>Payee name</span><input required maxLength={200} value={draft.suppliedName} onChange={(event) => { setDraft({ ...draft, suppliedName: event.target.value }); setPendingCheck(null) }} placeholder="Morgan Lee" /></label>
            <label className="field"><span>Amount (AUD)</span><input required min="0.01" max={wallet?.availableBalance} step="0.01" type="number" value={draft.amount} onChange={(event) => setDraft({ ...draft, amount: event.target.value })} placeholder="125.00" /></label>
            <label className="field field-wide"><span>Reference</span><input required maxLength={140} value={draft.reference} onChange={(event) => setDraft({ ...draft, reference: event.target.value })} placeholder="Invoice 1048" /></label>
            <button className="button button-primary field-wide" disabled={busy || !wallet} type="submit">{busy ? 'Checking…' : 'Check payee and continue'}</button>
          </form>
          {pendingCheck ? <div className="mismatch-card"><StatusBadge status={pendingCheck.outcome} /><div><strong>The supplied name could not be confirmed exactly.</strong><p>SentinelPay does not reveal the registered name. Confirm only if you trust the payment details.</p></div><button className="button button-danger" disabled={busy} type="button" onClick={() => void confirmMismatch()}>Accept mismatch and pay</button></div> : null}
        </article>
      </section>

      <section className="panel">
        <div className="panel-heading"><div><p className="eyebrow">Activity</p><h2>Recent payments</h2></div></div>
        <div className="table-wrap"><table><thead><tr><th>Reference</th><th>Receiver</th><th>Created</th><th>Amount</th><th>Status</th></tr></thead><tbody>
          {payments.map((payment) => <tr key={payment.id}><td className="payment-reference"><strong>{payment.reference}</strong><small>{payment.id.slice(0, 8)}</small></td><td className="wallet-reference">{payment.receiverWalletId.slice(0, 8)}…</td><td>{time.format(new Date(payment.createdAt))}</td><td>{money.format(payment.amount)}</td><td><StatusBadge status={payment.status} /></td></tr>)}
          {payments.length === 0 ? <tr><td colSpan={5} className="empty-state">No payments yet. Your first one will appear here.</td></tr> : null}
        </tbody></table></div>
        {nextCursor ? <div className="button-row pagination-row"><button className="button button-secondary" type="button" onClick={() => void loadMore()}>Load more payments</button></div> : null}
      </section>
    </div>
  )
}
