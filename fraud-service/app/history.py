from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta
from threading import RLock
from typing import Protocol
from uuid import UUID

from app.contracts import PaymentScreeningEnvelopeV1


@dataclass(frozen=True)
class RiskHistorySnapshot:
    known_payee_ids: frozenset[UUID] = frozenset()
    last_device_token: str | None = None
    transaction_count_10_minutes: int = 0


class RiskHistory(Protocol):
    def snapshot(
        self,
        customer_id: UUID,
        occurred_at: datetime,
        exclude_payment_id: UUID | None = None,
    ) -> RiskHistorySnapshot: ...

    def record(self, event: PaymentScreeningEnvelopeV1) -> None: ...


@dataclass(frozen=True)
class _ObservedPayment:
    payment_id: UUID
    payee_id: UUID
    device_token: str
    occurred_at: datetime


class InMemoryRiskHistory:
    """Day 12 history adapter; Day 13 replaces this with PostgreSQL."""

    def __init__(self) -> None:
        self._events: dict[UUID, dict[UUID, _ObservedPayment]] = {}
        self._lock = RLock()

    def snapshot(
        self,
        customer_id: UUID,
        occurred_at: datetime,
        exclude_payment_id: UUID | None = None,
    ) -> RiskHistorySnapshot:
        window_start = occurred_at - timedelta(minutes=10)
        with self._lock:
            prior = [
                item
                for item in self._events.get(customer_id, {}).values()
                if item.occurred_at <= occurred_at
                and item.payment_id != exclude_payment_id
            ]

        latest = max(prior, key=lambda item: item.occurred_at, default=None)
        return RiskHistorySnapshot(
            known_payee_ids=frozenset(item.payee_id for item in prior),
            last_device_token=(latest.device_token if latest else None),
            transaction_count_10_minutes=sum(
                window_start <= item.occurred_at <= occurred_at
                for item in prior
            ),
        )

    def record(self, event: PaymentScreeningEnvelopeV1) -> None:
        payload = event.payload
        observed = _ObservedPayment(
            payment_id=payload.payment_id,
            payee_id=payload.payee_id,
            device_token=payload.device_token,
            occurred_at=event.occurred_at,
        )
        with self._lock:
            customer_events = self._events.setdefault(payload.customer_id, {})
            existing = customer_events.get(payload.payment_id)
            if existing is not None and existing != observed:
                raise ValueError("payment history cannot be rewritten")
            customer_events[payload.payment_id] = observed
