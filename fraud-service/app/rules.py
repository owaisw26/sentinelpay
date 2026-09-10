from __future__ import annotations

from datetime import datetime, timezone
from decimal import Decimal
from enum import StrEnum
from typing import Annotated, Any
from uuid import UUID

from pydantic import AwareDatetime, Field, model_validator

from app.contracts import RiskAction, RiskFeatures, StrictContract


class RuleFeature(StrEnum):
    AMOUNT = "amount"
    FIRST_TIME_PAYEE = "first_time_payee"
    DEVICE_CHANGED = "device_changed"
    TRANSACTION_COUNT_10_MINUTES = "transaction_count_10_minutes"
    ACCEPTED_NAME_MISMATCH = "accepted_name_mismatch"


class RuleOperator(StrEnum):
    EQUALS = "EQUALS"
    GREATER_THAN_OR_EQUAL = "GREATER_THAN_OR_EQUAL"
    LESS_THAN_OR_EQUAL = "LESS_THAN_OR_EQUAL"


class RuleAction(StrEnum):
    HOLD = "HOLD"
    BLOCK = "BLOCK"


RuleValue = bool | int | Decimal


class RuleCondition(StrictContract):
    feature: RuleFeature
    operator: RuleOperator
    value: RuleValue

    @model_validator(mode="after")
    def validate_feature_operator_and_value(self) -> RuleCondition:
        boolean_features = {
            RuleFeature.FIRST_TIME_PAYEE,
            RuleFeature.DEVICE_CHANGED,
            RuleFeature.ACCEPTED_NAME_MISMATCH,
        }
        if self.feature in boolean_features:
            if self.operator is not RuleOperator.EQUALS or type(self.value) is not bool:
                raise ValueError("boolean features require EQUALS and a boolean value")
            return self

        if self.operator is RuleOperator.EQUALS:
            raise ValueError("numeric features do not permit EQUALS")
        if type(self.value) is bool:
            raise ValueError("numeric features require a numeric value")
        if self.feature is RuleFeature.AMOUNT:
            value = Decimal(self.value)
            if value < Decimal("1.00") or value > Decimal("999999.99"):
                raise ValueError("amount threshold is outside the allowed range")
            if value.as_tuple().exponent < -2:
                raise ValueError("amount threshold has excess precision")
            return self
        if type(self.value) is not int or not 1 <= self.value <= 20:
            raise ValueError("transaction velocity threshold must be an integer from 1 to 20")
        return self


class ProposedRule(StrictContract):
    name: str = Field(min_length=3, max_length=80, pattern=r"^[A-Za-z0-9][A-Za-z0-9 _-]+$")
    source_cluster_ids: tuple[
        Annotated[str, Field(pattern=r"^cluster-[1-9][0-9]*$")], ...
    ] = Field(min_length=1, max_length=6)
    conditions: tuple[RuleCondition, ...] = Field(min_length=1, max_length=4)
    action: RuleAction
    reason_code: str = Field(
        min_length=4,
        max_length=80,
        pattern=r"^AI_[A-Z0-9]+(?:_[A-Z0-9]+)*$",
    )
    expires_in_days: int = Field(ge=1, le=90)
    rationale: str = Field(min_length=20, max_length=500)

    @model_validator(mode="after")
    def validate_unique_conditions(self) -> ProposedRule:
        features = [condition.feature for condition in self.conditions]
        if len(features) != len(set(features)):
            raise ValueError("a rule cannot repeat a feature")
        if len(self.source_cluster_ids) != len(set(self.source_cluster_ids)):
            raise ValueError("source cluster IDs must be unique")
        return self


class RuleProposalOutput(StrictContract):
    rules: tuple[ProposedRule, ...] = Field(min_length=1, max_length=5)

    @model_validator(mode="after")
    def validate_unique_rules(self) -> RuleProposalOutput:
        reason_codes = [rule.reason_code for rule in self.rules]
        if len(reason_codes) != len(set(reason_codes)):
            raise ValueError("proposal reason codes must be unique")
        signatures = [
            tuple(sorted(
                (condition.feature, condition.operator, str(condition.value))
                for condition in rule.conditions
            ))
            for rule in self.rules
        ]
        if len(signatures) != len(set(signatures)):
            raise ValueError("proposal contains duplicate rule conditions")
        return self


class ActiveRule(StrictContract):
    rule_id: UUID
    source_proposal_id: UUID
    name: str
    conditions: tuple[RuleCondition, ...]
    action: RuleAction
    reason_code: str
    source_cluster_ids: tuple[str, ...]
    rationale: str
    expires_at: AwareDatetime


class DynamicRuleSet(StrictContract):
    version: int = Field(ge=0)
    rules: tuple[ActiveRule, ...] = ()


EMPTY_DYNAMIC_RULESET = DynamicRuleSet(version=0)


class DynamicRuleMatch(StrictContract):
    action: RiskAction | None
    rule_ids: tuple[UUID, ...]
    reason_codes: tuple[str, ...]


_ACTION_SEVERITY = {
    RiskAction.APPROVE: 0,
    RiskAction.HOLD: 1,
    RiskAction.BLOCK: 2,
}


def more_severe(left: RiskAction, right: RiskAction) -> RiskAction:
    return left if _ACTION_SEVERITY[left] >= _ACTION_SEVERITY[right] else right


def rule_signature(rule: ProposedRule | ActiveRule) -> tuple[tuple[str, str, str], ...]:
    return tuple(
        sorted(
            (
                condition.feature.value,
                condition.operator.value,
                str(condition.value),
            )
            for condition in rule.conditions
        )
    )


class DynamicRuleEvaluator:
    def evaluate(
        self,
        features: RiskFeatures,
        ruleset: DynamicRuleSet,
        *,
        evaluated_at: datetime | None = None,
    ) -> DynamicRuleMatch:
        now = evaluated_at or datetime.now(timezone.utc)
        matched: list[ActiveRule] = []
        for rule in ruleset.rules:
            if rule.expires_at <= now:
                continue
            if all(self._matches(condition, features) for condition in rule.conditions):
                matched.append(rule)
        if not matched:
            return DynamicRuleMatch(action=None, rule_ids=(), reason_codes=())
        action = RiskAction.HOLD
        for rule in matched:
            action = more_severe(action, RiskAction(rule.action.value))
        return DynamicRuleMatch(
            action=action,
            rule_ids=tuple(rule.rule_id for rule in matched),
            reason_codes=tuple(rule.reason_code for rule in matched),
        )

    def _matches(self, condition: RuleCondition, features: RiskFeatures) -> bool:
        actual: Any = getattr(features, condition.feature.value)
        expected = condition.value
        if condition.feature is RuleFeature.AMOUNT:
            expected = Decimal(expected)
        if condition.operator is RuleOperator.EQUALS:
            return actual == expected
        if condition.operator is RuleOperator.GREATER_THAN_OR_EQUAL:
            return actual >= expected
        if condition.operator is RuleOperator.LESS_THAN_OR_EQUAL:
            return actual <= expected
        raise ValueError(f"unsupported operator: {condition.operator}")
