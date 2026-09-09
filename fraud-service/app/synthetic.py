from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from uuid import UUID, uuid5

import numpy as np

from app.contracts import RiskFeatures


SYNTHETIC_NAMESPACE = UUID("f58fc723-75bf-4e17-a54f-1d195653ce1d")
DEFAULT_SYNTHETIC_SEED = 140014


@dataclass(frozen=True)
class SyntheticTransaction:
    transaction_id: UUID
    customer_id: UUID
    occurred_at: datetime
    features: RiskFeatures
    is_fraud: bool
    cohort: str


@dataclass(frozen=True)
class SyntheticDataset:
    seed: int
    training: tuple[SyntheticTransaction, ...]
    validation: tuple[SyntheticTransaction, ...]


def generate_synthetic_dataset(
    *,
    seed: int = DEFAULT_SYNTHETIC_SEED,
    training_customers: int = 400,
    validation_customers: int = 120,
    transactions_per_customer: int = 12,
) -> SyntheticDataset:
    """Generate labelled feature rows without putting labels in the model."""

    rng = np.random.default_rng(seed)
    training = _generate_cohort(
        rng,
        cohort="training",
        customer_count=training_customers,
        transactions_per_customer=transactions_per_customer,
        starts_at=datetime(2026, 1, 1, tzinfo=timezone.utc),
    )
    validation = _generate_cohort(
        rng,
        cohort="validation",
        customer_count=validation_customers,
        transactions_per_customer=transactions_per_customer,
        starts_at=datetime(2026, 7, 1, tzinfo=timezone.utc),
    )
    return SyntheticDataset(seed, training, validation)


def _generate_cohort(
    rng: np.random.Generator,
    *,
    cohort: str,
    customer_count: int,
    transactions_per_customer: int,
    starts_at: datetime,
) -> tuple[SyntheticTransaction, ...]:
    rows: list[SyntheticTransaction] = []
    for customer_index in range(customer_count):
        customer_id = uuid5(
            SYNTHETIC_NAMESPACE, f"{cohort}:customer:{customer_index}"
        )
        for transaction_index in range(transactions_per_customer):
            is_fraud = bool(rng.random() < 0.08)
            features = _fraud_features(rng) if is_fraud else _legit_features(
                rng, transaction_index
            )
            global_index = customer_index * transactions_per_customer + (
                transaction_index
            )
            rows.append(
                SyntheticTransaction(
                    transaction_id=uuid5(
                        SYNTHETIC_NAMESPACE,
                        f"{cohort}:transaction:{global_index}",
                    ),
                    customer_id=customer_id,
                    occurred_at=starts_at + timedelta(minutes=global_index * 3),
                    features=features,
                    is_fraud=is_fraud,
                    cohort=cohort,
                )
            )
    return tuple(sorted(rows, key=lambda row: (row.occurred_at, row.transaction_id)))


def _legit_features(
    rng: np.random.Generator, transaction_index: int
) -> RiskFeatures:
    amount = min(float(rng.lognormal(mean=5.7, sigma=0.85)), 12000.0)
    return RiskFeatures(
        amount=_money(amount),
        first_time_payee=(transaction_index == 0 or rng.random() < 0.10),
        device_changed=bool(rng.random() < 0.025),
        transaction_count_10_minutes=int(min(1 + rng.poisson(0.65), 6)),
        accepted_name_mismatch=bool(rng.random() < 0.008),
    )


def _fraud_features(rng: np.random.Generator) -> RiskFeatures:
    archetype = int(rng.integers(0, 3))
    if archetype == 0:
        amount = float(rng.lognormal(mean=9.0, sigma=0.45))
        device_changed = rng.random() < 0.70
        velocity = int(rng.integers(2, 8))
        mismatch = rng.random() < 0.18
    elif archetype == 1:
        amount = float(rng.lognormal(mean=7.0, sigma=0.65))
        device_changed = rng.random() < 0.80
        velocity = int(rng.integers(5, 12))
        mismatch = rng.random() < 0.30
    else:
        amount = float(rng.lognormal(mean=6.5, sigma=0.75))
        device_changed = rng.random() < 0.45
        velocity = int(rng.integers(2, 10))
        mismatch = rng.random() < 0.72
    return RiskFeatures(
        amount=_money(min(amount, 999999.99)),
        first_time_payee=bool(rng.random() < 0.72),
        device_changed=bool(device_changed),
        transaction_count_10_minutes=velocity,
        accepted_name_mismatch=bool(mismatch),
    )


def _money(value: float) -> Decimal:
    return Decimal(f"{value:.2f}")
