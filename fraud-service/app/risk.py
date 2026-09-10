from __future__ import annotations

from dataclasses import dataclass
from decimal import Decimal
from uuid import UUID, uuid5

from app.anomaly import (
    SEVERE_ANOMALY_POINTS,
    AnomalyAssessment,
    IsolationForestAnomalyModel,
)
from app.contracts import (
    PaymentScreeningEnvelopeV1,
    RiskAction,
    RiskDecisionEnvelopeV1,
    RiskDecisionV1,
    RiskEvaluationAuditV1,
    RiskFeatures,
)
from app.history import RiskHistorySnapshot
from app.rules import (
    EMPTY_DYNAMIC_RULESET,
    DynamicRuleEvaluator,
    DynamicRuleSet,
    more_severe,
)


FEATURE_VERSION = "payment-features-v1"
RULESET_VERSION = "deterministic-rules-v1"
DECISION_NAMESPACE = UUID("50577826-b11d-44af-9042-17a77e292025")


@dataclass(frozen=True)
class RiskRule:
    reason_code: str
    points: int


@dataclass(frozen=True)
class RiskRuleset:
    version: str
    amount_threshold: Decimal
    amount_rule: RiskRule
    first_payee_rule: RiskRule
    device_rule: RiskRule
    velocity_rule: RiskRule
    namecheck_rule: RiskRule
    hold_at: int
    block_at: int


DEFAULT_RULESET_V1 = RiskRuleset(
    version=RULESET_VERSION,
    amount_threshold=Decimal("5000.00"),
    amount_rule=RiskRule("AMOUNT_GTE_AUD_5000", 25),
    first_payee_rule=RiskRule("FIRST_TIME_PAYEE", 20),
    device_rule=RiskRule("DEVICE_CHANGED", 20),
    velocity_rule=RiskRule("FIVE_TRANSACTIONS_10_MINUTES", 35),
    namecheck_rule=RiskRule("NAMECHECK_MISMATCH_ACCEPTED", 40),
    hold_at=40,
    block_at=70,
)


@dataclass(frozen=True)
class EvaluationResult:
    decision_event: RiskDecisionEnvelopeV1
    audit_record: RiskEvaluationAuditV1


class FeatureEnricher:
    def enrich(
        self,
        event: PaymentScreeningEnvelopeV1,
        history: RiskHistorySnapshot,
    ) -> RiskFeatures:
        payload = event.payload
        return RiskFeatures(
            amount=payload.amount,
            first_time_payee=payload.payee_id not in history.known_payee_ids,
            device_changed=(
                history.last_device_token is not None
                and history.last_device_token != payload.device_token
            ),
            transaction_count_10_minutes=(
                history.transaction_count_10_minutes + 1
            ),
            accepted_name_mismatch=payload.accepted_name_mismatch,
        )


class DeterministicRuleScorer:
    def __init__(self, ruleset: RiskRuleset = DEFAULT_RULESET_V1) -> None:
        self.ruleset = ruleset

    def score(self, features: RiskFeatures) -> tuple[int, tuple[str, ...]]:
        triggered: list[RiskRule] = []
        if features.amount >= self.ruleset.amount_threshold:
            triggered.append(self.ruleset.amount_rule)
        if features.first_time_payee:
            triggered.append(self.ruleset.first_payee_rule)
        if features.device_changed:
            triggered.append(self.ruleset.device_rule)
        if features.transaction_count_10_minutes >= 5:
            triggered.append(self.ruleset.velocity_rule)
        if features.accepted_name_mismatch:
            triggered.append(self.ruleset.namecheck_rule)
        return (
            sum(rule.points for rule in triggered),
            tuple(rule.reason_code for rule in triggered),
        )


class DecisionClassifier:
    def __init__(self, ruleset: RiskRuleset = DEFAULT_RULESET_V1) -> None:
        self.ruleset = ruleset

    def decide(self, score: int) -> RiskAction:
        if score < self.ruleset.hold_at:
            return RiskAction.APPROVE
        if score < self.ruleset.block_at:
            return RiskAction.HOLD
        return RiskAction.BLOCK


class RiskEvaluationPipeline:
    def __init__(
        self,
        enricher: FeatureEnricher | None = None,
        scorer: DeterministicRuleScorer | None = None,
        classifier: DecisionClassifier | None = None,
        ruleset: RiskRuleset = DEFAULT_RULESET_V1,
        anomaly_model: IsolationForestAnomalyModel | None = None,
        dynamic_ruleset: DynamicRuleSet = EMPTY_DYNAMIC_RULESET,
        dynamic_evaluator: DynamicRuleEvaluator | None = None,
    ) -> None:
        self._enricher = enricher or FeatureEnricher()
        self._scorer = scorer or DeterministicRuleScorer(ruleset)
        self._classifier = classifier or DecisionClassifier(ruleset)
        if self._scorer.ruleset != self._classifier.ruleset:
            raise ValueError("scorer and classifier must use the same ruleset")
        self._ruleset = self._scorer.ruleset
        self._anomaly_model = anomaly_model
        self._dynamic_ruleset = dynamic_ruleset
        self._dynamic_evaluator = dynamic_evaluator or DynamicRuleEvaluator()

    def evaluate(
        self,
        event: PaymentScreeningEnvelopeV1,
        history: RiskHistorySnapshot,
    ) -> EvaluationResult:
        features = self._enricher.enrich(event, history)
        deterministic_score, deterministic_reasons = self._scorer.score(
            features
        )
        assessment = self._assess_anomaly(features)
        score = deterministic_score + assessment.contribution
        reasons = deterministic_reasons + assessment.reason_codes
        action = self._classifier.decide(score)
        dynamic_match = self._dynamic_evaluator.evaluate(
            features,
            self._dynamic_ruleset,
            evaluated_at=event.occurred_at,
        )
        if dynamic_match.action is not None:
            action = more_severe(action, dynamic_match.action)
        reasons = reasons + dynamic_match.reason_codes
        model_version = (
            self._anomaly_model.version if self._anomaly_model else "none"
        )
        ruleset_version = (
            f"{self._ruleset.version}+dynamic-{self._dynamic_ruleset.version}"
        )
        decision_id = uuid5(
            DECISION_NAMESPACE,
            f"{event.event_id}:{FEATURE_VERSION}:{ruleset_version}:"
            f"{model_version}",
        )
        decision = RiskDecisionV1(
            decision_id=decision_id,
            payment_id=event.payload.payment_id,
            source_event_id=event.event_id,
            source_aggregate_sequence=event.aggregate_sequence,
            feature_version=FEATURE_VERSION,
            ruleset_version=ruleset_version,
            model_version=model_version,
            score=score,
            action=action,
            reason_codes=reasons,
            matched_rule_ids=dynamic_match.rule_ids,
            evaluated_at=event.occurred_at,
        )
        decision_event = RiskDecisionEnvelopeV1(
            event_id=decision_id,
            event_type="RISK_DECISION_MADE",
            schema_version=1,
            aggregate_id=event.aggregate_id,
            aggregate_sequence=event.aggregate_sequence,
            occurred_at=event.occurred_at,
            correlation_id=event.correlation_id,
            causation_id=event.event_id,
            payload=decision,
        )
        audit = RiskEvaluationAuditV1(
            source_event_id=event.event_id,
            decision_id=decision_id,
            feature_version=FEATURE_VERSION,
            ruleset_version=ruleset_version,
            model_version=model_version,
            features=features,
            deterministic_score=deterministic_score,
            anomaly_score=(
                assessment.anomaly_score if self._anomaly_model else None
            ),
            anomaly_contribution=assessment.contribution,
            score=score,
            action=action,
            reason_codes=reasons,
            matched_rule_ids=dynamic_match.rule_ids,
            evaluated_at=event.occurred_at,
        )
        return EvaluationResult(decision_event, audit)

    def _assess_anomaly(self, features: RiskFeatures) -> AnomalyAssessment:
        if self._anomaly_model is None:
            return AnomalyAssessment(0.0, 0, ())
        assessment = self._anomaly_model.assess(features)
        if not 0 <= assessment.contribution <= SEVERE_ANOMALY_POINTS:
            raise ValueError("anomaly contribution exceeds its safety bound")
        if assessment.contribution > 0 and not assessment.reason_codes:
            raise ValueError("anomaly contribution requires an explanation")
        return assessment
