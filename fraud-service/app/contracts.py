from __future__ import annotations

from decimal import Decimal
from enum import StrEnum
from typing import Literal
from uuid import UUID

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    model_validator,
)


def _to_camel(value: str) -> str:
    head, *tail = value.split("_")
    return head + "".join(part.capitalize() for part in tail)


class StrictContract(BaseModel):
    model_config = ConfigDict(
        alias_generator=_to_camel,
        extra="forbid",
        frozen=True,
        populate_by_name=True,
        serialize_by_alias=True,
        strict=True,
    )


class NameCheckOutcome(StrEnum):
    MATCH = "MATCH"
    CLOSE_MATCH = "CLOSE_MATCH"
    NO_MATCH = "NO_MATCH"


class RiskAction(StrEnum):
    APPROVE = "APPROVE"
    HOLD = "HOLD"
    BLOCK = "BLOCK"


class PaymentScreeningRequestedV1(StrictContract):
    payment_id: UUID
    customer_id: UUID
    payee_id: UUID
    amount: Decimal = Field(gt=0, max_digits=19, decimal_places=2)
    currency: Literal["AUD"]
    device_token: str = Field(
        min_length=16,
        max_length=128,
        pattern=r"^[A-Za-z0-9_-]+$",
    )
    name_check_outcome: NameCheckOutcome
    accepted_name_mismatch: bool

    @model_validator(mode="after")
    def validate_name_check_acceptance(self) -> PaymentScreeningRequestedV1:
        mismatch = self.name_check_outcome is not NameCheckOutcome.MATCH
        if mismatch != self.accepted_name_mismatch:
            raise ValueError(
                "acceptedNameMismatch must be true exactly when the "
                "NameCheck outcome is a mismatch"
            )
        return self


class PaymentScreeningEnvelopeV1(StrictContract):
    event_id: UUID
    event_type: Literal["PAYMENT_SCREENING_REQUESTED"]
    schema_version: Literal[1]
    aggregate_id: UUID
    aggregate_sequence: int = Field(ge=1)
    occurred_at: AwareDatetime
    correlation_id: UUID
    causation_id: UUID | None
    payload: PaymentScreeningRequestedV1

    @model_validator(mode="after")
    def validate_aggregate(self) -> PaymentScreeningEnvelopeV1:
        if self.aggregate_id != self.payload.payment_id:
            raise ValueError("aggregateId must equal payload.paymentId")
        return self


class RiskFeatures(StrictContract):
    amount: Decimal
    first_time_payee: bool
    device_changed: bool
    transaction_count_10_minutes: int = Field(ge=1)
    accepted_name_mismatch: bool


class RiskDecisionV1(StrictContract):
    decision_id: UUID
    payment_id: UUID
    source_event_id: UUID
    source_aggregate_sequence: int = Field(ge=1)
    feature_version: str
    ruleset_version: str
    model_version: str = Field(min_length=1, max_length=100)
    score: int = Field(ge=0)
    action: RiskAction
    reason_codes: tuple[str, ...]
    matched_rule_ids: tuple[UUID, ...] = ()
    evaluated_at: AwareDatetime

    @model_validator(mode="after")
    def validate_explanation(self) -> RiskDecisionV1:
        if self.action is not RiskAction.APPROVE and not self.reason_codes:
            raise ValueError("non-approval decisions require reason codes")
        return self


class RiskDecisionEnvelopeV1(StrictContract):
    event_id: UUID
    event_type: Literal["RISK_DECISION_MADE"]
    schema_version: Literal[1]
    aggregate_id: UUID
    aggregate_sequence: int = Field(ge=1)
    occurred_at: AwareDatetime
    correlation_id: UUID
    causation_id: UUID
    payload: RiskDecisionV1


class RiskEvaluationAuditV1(StrictContract):
    source_event_id: UUID
    decision_id: UUID
    feature_version: str
    ruleset_version: str
    model_version: str
    features: RiskFeatures
    deterministic_score: int = Field(ge=0)
    anomaly_score: float | None
    anomaly_contribution: int = Field(ge=0, le=30)
    score: int = Field(ge=0)
    action: RiskAction
    reason_codes: tuple[str, ...]
    matched_rule_ids: tuple[UUID, ...] = ()
    evaluated_at: AwareDatetime
