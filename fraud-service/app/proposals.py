from __future__ import annotations

import hashlib
import json
import time
from datetime import datetime, timedelta, timezone
from decimal import Decimal
from enum import StrEnum
from typing import Any, Callable, Protocol
from uuid import UUID, uuid4, uuid5

import httpx
import numpy as np
from pydantic import AwareDatetime, Field, ValidationError
from sklearn.cluster import KMeans
from sklearn.preprocessing import StandardScaler

from app.anomaly import AnomalyFeatureTransformer, IsolationForestAnomalyModel
from app.contracts import RiskAction, StrictContract
from app.risk import DecisionClassifier, DeterministicRuleScorer
from app.rules import (
    ActiveRule,
    DynamicRuleEvaluator,
    DynamicRuleSet,
    RuleAction,
    RuleFeature,
    RuleOperator,
    RuleProposalOutput,
    more_severe,
    rule_signature,
)
from app.synthetic import (
    DEFAULT_SYNTHETIC_SEED,
    SyntheticDataset,
    SyntheticTransaction,
    generate_synthetic_dataset,
)


PROMPT_VERSION = "rule-proposal-prompt-v1"
SCHEMA_VERSION = "rule-proposal-schema-v1"
DEFAULT_OPENAI_MODEL = "gpt-5.4-mini-2026-03-17"
RULE_NAMESPACE = UUID("0bd48648-3336-49fa-b891-6daee4e221a2")


class ProposalStatus(StrEnum):
    DRAFT = "DRAFT"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    VALIDATION_FAILED = "VALIDATION_FAILED"


class ProposalConflictError(RuntimeError):
    pass


class ProposalNotFoundError(LookupError):
    pass


class ModelCallError(RuntimeError):
    pass


_NON_RETRYABLE_OPENAI_CODES = {
    "credit_balance_exhausted",
    "organization_usage_limit_exceeded",
    "organization_spend_limit_exceeded",
    "project_spend_limit_exceeded",
}


class AggregateSummary(StrictContract):
    case_count: int = Field(ge=1)
    amount_median: Decimal
    amount_p90: Decimal
    first_time_payee_rate: float = Field(ge=0, le=1)
    device_changed_rate: float = Field(ge=0, le=1)
    transaction_count_10_minutes_median: float
    transaction_count_10_minutes_p90: float
    accepted_name_mismatch_rate: float = Field(ge=0, le=1)


class FraudClusterSummary(AggregateSummary):
    cluster_id: str = Field(pattern=r"^cluster-[1-9][0-9]*$")
    current_hold_or_block_rate: float = Field(ge=0, le=1)


class RuleGenerationContext(StrictContract):
    dataset_version: str
    seed: int
    training_row_count: int
    synthetic_fraud_case_count: int
    legitimate_baseline: AggregateSummary
    fraud_clusters: tuple[FraudClusterSummary, ...]
    allowed_features: tuple[RuleFeature, ...]
    allowed_operators: tuple[RuleOperator, ...]
    allowed_actions: tuple[RuleAction, ...]
    existing_reason_codes: tuple[str, ...]


class ModelUsage(StrictContract):
    input_tokens: int = Field(ge=0)
    output_tokens: int = Field(ge=0)
    total_tokens: int = Field(ge=0)


class RawModelGeneration(StrictContract):
    response_id: str
    model: str
    output_text: str
    usage: ModelUsage


class ImpactReport(StrictContract):
    validation_rows: int
    validation_fraud_rows: int
    fraud_cases_newly_restricted: int
    legitimate_payments_newly_restricted: int
    newly_held: int
    newly_blocked: int


class ProposalRecord(StrictContract):
    proposal_id: UUID
    status: ProposalStatus
    version: int = Field(ge=0)
    prompt_version: str
    schema_version: str
    model: str
    provider_response_id: str
    response_sha256: str = Field(pattern=r"^[a-f0-9]{64}$")
    input_summary: RuleGenerationContext
    candidate: RuleProposalOutput | None
    impact: ImpactReport | None
    usage: ModelUsage
    validation_failures: tuple[str, ...]
    generated_by: str
    generated_at: AwareDatetime
    reviewed_by: str | None = None
    reviewed_at: AwareDatetime | None = None
    review_reason: str | None = None
    approved_ruleset_version: int | None = None


class RuleSetRecord(StrictContract):
    version: int = Field(ge=0)
    parent_version: int | None
    operation: str
    rules: tuple[ActiveRule, ...]
    source_proposal_id: UUID | None
    created_by: str
    created_at: AwareDatetime
    reason: str

    def dynamic_ruleset(self) -> DynamicRuleSet:
        return DynamicRuleSet(version=self.version, rules=self.rules)


class RuleProposalModelClient(Protocol):
    def generate(self, context: RuleGenerationContext) -> RawModelGeneration: ...


class RuleProposalStore(Protocol):
    def insert_proposal(self, proposal: ProposalRecord) -> ProposalRecord: ...

    def get_proposal(self, proposal_id: UUID) -> ProposalRecord: ...

    def approve(
        self,
        proposal_id: UUID,
        *,
        expected_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> RuleSetRecord: ...

    def reject(
        self,
        proposal_id: UUID,
        *,
        expected_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> ProposalRecord: ...

    def deactivate(
        self,
        rule_id: UUID,
        *,
        expected_ruleset_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> RuleSetRecord: ...

    def rollback(
        self,
        target_version: int,
        *,
        expected_ruleset_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> RuleSetRecord: ...

    def active_ruleset(self) -> RuleSetRecord: ...


def build_generation_context(
    dataset: SyntheticDataset | None = None,
    *,
    seed: int = DEFAULT_SYNTHETIC_SEED,
    cluster_count: int = 3,
) -> RuleGenerationContext:
    data = dataset or generate_synthetic_dataset(seed=seed)
    fraud_rows = [row for row in data.training if row.is_fraud]
    legitimate_rows = [row for row in data.training if not row.is_fraud]
    if len(fraud_rows) < cluster_count:
        raise ValueError("not enough synthetic fraud rows to form clusters")

    transformer = AnomalyFeatureTransformer()
    matrix = transformer.transform_many([row.features for row in fraud_rows])
    scaled = StandardScaler().fit_transform(matrix)
    labels = KMeans(
        n_clusters=cluster_count,
        random_state=seed,
        n_init=20,
        algorithm="lloyd",
    ).fit_predict(scaled)
    groups = [
        [row for row, label in zip(fraud_rows, labels, strict=True) if label == index]
        for index in range(cluster_count)
    ]
    groups.sort(
        key=lambda rows: (
            _percentile(rows, "amount", 50),
            _percentile(rows, "transaction_count_10_minutes", 50),
        )
    )
    scorer = DeterministicRuleScorer()
    classifier = DecisionClassifier()
    clusters: list[FraudClusterSummary] = []
    for index, rows in enumerate(groups, start=1):
        aggregate = _aggregate(rows)
        restricted = sum(
            classifier.decide(scorer.score(row.features)[0]) is not RiskAction.APPROVE
            for row in rows
        )
        clusters.append(
            FraudClusterSummary(
                **aggregate.model_dump(),
                cluster_id=f"cluster-{index}",
                current_hold_or_block_rate=restricted / len(rows),
            )
        )
    return RuleGenerationContext(
        dataset_version=f"synthetic-v1-seed-{data.seed}",
        seed=data.seed,
        training_row_count=len(data.training),
        synthetic_fraud_case_count=len(fraud_rows),
        legitimate_baseline=_aggregate(legitimate_rows),
        fraud_clusters=tuple(clusters),
        allowed_features=tuple(RuleFeature),
        allowed_operators=tuple(RuleOperator),
        allowed_actions=tuple(RuleAction),
        existing_reason_codes=(
            "AMOUNT_GTE_AUD_5000",
            "FIRST_TIME_PAYEE",
            "DEVICE_CHANGED",
            "FIVE_TRANSACTIONS_10_MINUTES",
            "NAMECHECK_MISMATCH_ACCEPTED",
        ),
    )


def _aggregate(rows: list[SyntheticTransaction]) -> AggregateSummary:
    if not rows:
        raise ValueError("cannot aggregate an empty population")
    return AggregateSummary(
        case_count=len(rows),
        amount_median=Decimal(f"{_percentile(rows, 'amount', 50):.2f}"),
        amount_p90=Decimal(f"{_percentile(rows, 'amount', 90):.2f}"),
        first_time_payee_rate=_rate(rows, "first_time_payee"),
        device_changed_rate=_rate(rows, "device_changed"),
        transaction_count_10_minutes_median=_percentile(
            rows, "transaction_count_10_minutes", 50
        ),
        transaction_count_10_minutes_p90=_percentile(
            rows, "transaction_count_10_minutes", 90
        ),
        accepted_name_mismatch_rate=_rate(rows, "accepted_name_mismatch"),
    )


def _percentile(rows: list[SyntheticTransaction], feature: str, percentile: int) -> float:
    values = [float(getattr(row.features, feature)) for row in rows]
    return float(np.percentile(values, percentile, method="linear"))


def _rate(rows: list[SyntheticTransaction], feature: str) -> float:
    return sum(bool(getattr(row.features, feature)) for row in rows) / len(rows)


class StubRuleProposalClient:
    def __init__(self, output: RuleProposalOutput | None = None) -> None:
        self._output = output

    def generate(self, context: RuleGenerationContext) -> RawModelGeneration:
        output = self._output or RuleProposalOutput.model_validate_json(
            json.dumps({
                "rules": [
                    {
                        "name": "Rapid activity on changed device",
                        "sourceClusterIds": [context.fraud_clusters[-1].cluster_id],
                        "conditions": [
                            {
                                "feature": "device_changed",
                                "operator": "EQUALS",
                                "value": True,
                            },
                            {
                                "feature": "transaction_count_10_minutes",
                                "operator": "GREATER_THAN_OR_EQUAL",
                                "value": 7,
                            },
                            {
                                "feature": "first_time_payee",
                                "operator": "EQUALS",
                                "value": True,
                            },
                        ],
                        "action": "HOLD",
                        "reasonCode": "AI_RAPID_ACTIVITY_CHANGED_DEVICE",
                        "expiresInDays": 30,
                        "rationale": (
                            "Changed devices and rapid activity are concentrated "
                            "in the referenced synthetic fraud cluster."
                        ),
                    }
                ]
            })
        )
        text = output.model_dump_json(by_alias=True)
        return RawModelGeneration(
            response_id="stub-response-v1",
            model="contract-stub-v1",
            output_text=text,
            usage=ModelUsage(input_tokens=0, output_tokens=0, total_tokens=0),
        )


class OpenAIRuleProposalClient:
    def __init__(
        self,
        api_key: str,
        *,
        model: str = DEFAULT_OPENAI_MODEL,
        timeout_seconds: float = 20.0,
        max_output_tokens: int = 8000,
        max_retries: int = 2,
        transport: httpx.BaseTransport | None = None,
        sleeper: Callable[[float], None] = time.sleep,
    ) -> None:
        if not api_key:
            raise ValueError("OpenAI API key is required")
        if not 1000 <= max_output_tokens <= 12000:
            raise ValueError("max_output_tokens must be from 1000 to 12000")
        self._api_key = api_key
        self._model = model
        self._max_output_tokens = max_output_tokens
        self._max_retries = max_retries
        self._sleeper = sleeper
        self._client = httpx.Client(
            base_url="https://api.openai.com/v1",
            timeout=timeout_seconds,
            transport=transport,
        )

    def generate(self, context: RuleGenerationContext) -> RawModelGeneration:
        output_schema = _model_output_schema()
        request = {
            "model": self._model,
            "instructions": (
                "You propose narrow fraud-control hypotheses from aggregated, "
                "synthetic cluster statistics. Do not infer identities or invent "
                "features. Avoid duplicating existing rules. Rules may only raise "
                "a decision to HOLD or BLOCK and always require analyst approval. "
                "Return exactly one concise rule with no more than three conditions "
                "and keep its rationale under 200 characters."
            ),
            "input": context.model_dump_json(by_alias=True),
            "text": {
                "verbosity": "low",
                "format": {
                    "type": "json_schema",
                    "name": "sentinelpay_rule_proposal",
                    "strict": True,
                    "schema": output_schema,
                }
            },
            "reasoning": {"effort": "none"},
            "store": False,
            "tools": [],
            "max_output_tokens": self._max_output_tokens,
        }
        response: httpx.Response | None = None
        for attempt in range(self._max_retries + 1):
            response = self._client.post(
                "/responses",
                headers={"Authorization": f"Bearer {self._api_key}"},
                json=request,
            )
            error_code, _, _ = _openai_error_details(response)
            retryable = (
                response.status_code >= 500
                or (
                    response.status_code == 429
                    and error_code not in _NON_RETRYABLE_OPENAI_CODES
                )
            )
            if not retryable:
                break
            if attempt < self._max_retries:
                self._sleeper(0.25 * (2**attempt))
        assert response is not None
        if response.is_error:
            error_code, error_type, error_message = _openai_error_details(response)
            request_id = response.headers.get("x-request-id", "unknown")
            detail = error_code or error_type or "unknown_error"
            if error_message:
                detail += f": {error_message}"
            raise ModelCallError(
                f"OpenAI Responses API returned {response.status_code} "
                f"({detail}; request_id={request_id})"
            )
        document = response.json()
        if document.get("status") != "completed":
            incomplete = document.get("incomplete_details")
            reason = (
                incomplete.get("reason")
                if isinstance(incomplete, dict)
                else None
            )
            usage = document.get("usage")
            output_tokens = (
                usage.get("output_tokens") if isinstance(usage, dict) else None
            )
            output_details = (
                usage.get("output_tokens_details")
                if isinstance(usage, dict)
                else None
            )
            reasoning_tokens = (
                output_details.get("reasoning_tokens")
                if isinstance(output_details, dict)
                else None
            )
            raise ModelCallError(
                "OpenAI response did not complete "
                f"(status={document.get('status', 'unknown')}; "
                f"reason={reason or 'unknown'}; "
                f"output_tokens={output_tokens if output_tokens is not None else 'unknown'}; "
                "reasoning_tokens="
                f"{reasoning_tokens if reasoning_tokens is not None else 'unknown'}; "
                f"response_id={document.get('id', 'unknown')})"
            )
        output_text = _response_output_text(document)
        if not output_text:
            raise ModelCallError("OpenAI response contained no structured output")
        usage = document.get("usage") or {}
        return RawModelGeneration(
            response_id=str(document.get("id", "unknown")),
            model=str(document.get("model", self._model)),
            output_text=output_text,
            usage=ModelUsage(
                input_tokens=int(usage.get("input_tokens", 0)),
                output_tokens=int(usage.get("output_tokens", 0)),
                total_tokens=int(usage.get("total_tokens", 0)),
            ),
        )


def _model_output_schema() -> dict[str, Any]:
    """Return a decoder-friendly wire schema; business validation stays local."""
    schema = RuleProposalOutput.model_json_schema(by_alias=True)
    schema["properties"]["rules"]["maxItems"] = 1
    proposed_rule = schema["$defs"]["ProposedRule"]
    proposed_rule["properties"]["conditions"]["maxItems"] = 3
    proposed_rule["properties"]["rationale"]["maxLength"] = 200
    condition = schema["$defs"]["RuleCondition"]
    condition["properties"]["value"] = {
        "anyOf": [{"type": "boolean"}, {"type": "number"}]
    }
    return schema


def _response_output_text(document: dict[str, Any]) -> str | None:
    convenience_text = document.get("output_text")
    if isinstance(convenience_text, str) and convenience_text:
        return convenience_text
    parts: list[str] = []
    output = document.get("output")
    if not isinstance(output, list):
        return None
    for item in output:
        if not isinstance(item, dict) or item.get("type") != "message":
            continue
        content = item.get("content")
        if not isinstance(content, list):
            continue
        for part in content:
            if not isinstance(part, dict) or part.get("type") != "output_text":
                continue
            value = part.get("text")
            if isinstance(value, str):
                parts.append(value)
    return "".join(parts) or None


def _openai_error_details(
    response: httpx.Response,
) -> tuple[str | None, str | None, str | None]:
    try:
        document = response.json()
    except (json.JSONDecodeError, ValueError):
        return None, None, None
    error = document.get("error") if isinstance(document, dict) else None
    if not isinstance(error, dict):
        return None, None, None
    code = error.get("code")
    error_type = error.get("type")
    message = error.get("message")
    safe_message = None
    if isinstance(message, str):
        safe_message = " ".join(message.split())[:500]
    return (
        str(code) if code else None,
        str(error_type) if error_type else None,
        safe_message,
    )


def simulate_impact(
    candidate: RuleProposalOutput,
    dataset: SyntheticDataset,
    *,
    anomaly_model: IsolationForestAnomalyModel | None = None,
) -> ImpactReport:
    proposal_id = UUID("34de5756-624d-4ffb-a30c-090a26b23ddf")
    rules = tuple(
        ActiveRule(
            rule_id=uuid5(RULE_NAMESPACE, f"simulation:{index}"),
            source_proposal_id=proposal_id,
            name=rule.name,
            conditions=rule.conditions,
            action=rule.action,
            reason_code=rule.reason_code,
            source_cluster_ids=rule.source_cluster_ids,
            rationale=rule.rationale,
            expires_at=datetime(2100, 1, 1, tzinfo=timezone.utc),
        )
        for index, rule in enumerate(candidate.rules)
    )
    ruleset = DynamicRuleSet(version=1, rules=rules)
    evaluator = DynamicRuleEvaluator()
    scorer = DeterministicRuleScorer()
    classifier = DecisionClassifier()
    fraud_new = legitimate_new = newly_held = newly_blocked = 0
    for row in dataset.validation:
        score = scorer.score(row.features)[0]
        if anomaly_model is not None:
            score += anomaly_model.assess(row.features).contribution
        base = classifier.decide(score)
        match = evaluator.evaluate(row.features, ruleset, evaluated_at=row.occurred_at)
        final = base if match.action is None else more_severe(base, match.action)
        if final is base:
            continue
        if row.is_fraud:
            fraud_new += 1
        else:
            legitimate_new += 1
        if final is RiskAction.HOLD:
            newly_held += 1
        if final is RiskAction.BLOCK:
            newly_blocked += 1
    return ImpactReport(
        validation_rows=len(dataset.validation),
        validation_fraud_rows=sum(row.is_fraud for row in dataset.validation),
        fraud_cases_newly_restricted=fraud_new,
        legitimate_payments_newly_restricted=legitimate_new,
        newly_held=newly_held,
        newly_blocked=newly_blocked,
    )


class RuleProposalService:
    def __init__(
        self,
        store: RuleProposalStore,
        model_client: RuleProposalModelClient,
        *,
        dataset: SyntheticDataset | None = None,
        anomaly_model: IsolationForestAnomalyModel | None = None,
        clock: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
    ) -> None:
        self._store = store
        self._model_client = model_client
        self._dataset = dataset or generate_synthetic_dataset()
        self._anomaly_model = anomaly_model
        self._clock = clock

    def generate(self, actor: str) -> ProposalRecord:
        context = build_generation_context(self._dataset)
        generation = self._model_client.generate(context)
        digest = hashlib.sha256(generation.output_text.encode()).hexdigest()
        candidate: RuleProposalOutput | None = None
        impact: ImpactReport | None = None
        failures: tuple[str, ...] = ()
        status = ProposalStatus.DRAFT
        try:
            candidate = RuleProposalOutput.model_validate_json(generation.output_text)
            known_clusters = {item.cluster_id for item in context.fraud_clusters}
            referenced = {
                cluster_id
                for rule in candidate.rules
                for cluster_id in rule.source_cluster_ids
            }
            if not referenced <= known_clusters:
                raise ValueError("proposal references an unknown cluster")
            impact = simulate_impact(
                candidate,
                self._dataset,
                anomaly_model=self._anomaly_model,
            )
        except (ValidationError, ValueError) as error:
            status = ProposalStatus.VALIDATION_FAILED
            failures = (str(error)[:1000],)
        record = ProposalRecord(
            proposal_id=uuid4(),
            status=status,
            version=0,
            prompt_version=PROMPT_VERSION,
            schema_version=SCHEMA_VERSION,
            model=generation.model,
            provider_response_id=generation.response_id,
            response_sha256=digest,
            input_summary=context,
            candidate=candidate,
            impact=impact,
            usage=generation.usage,
            validation_failures=failures,
            generated_by=actor,
            generated_at=self._clock(),
        )
        return self._store.insert_proposal(record)

    def get(self, proposal_id: UUID) -> ProposalRecord:
        return self._store.get_proposal(proposal_id)

    def approve(
        self, proposal_id: UUID, expected_version: int, actor: str, reason: str
    ) -> RuleSetRecord:
        return self._store.approve(
            proposal_id,
            expected_version=expected_version,
            actor=actor,
            reason=reason,
            now=self._clock(),
        )

    def reject(
        self, proposal_id: UUID, expected_version: int, actor: str, reason: str
    ) -> ProposalRecord:
        return self._store.reject(
            proposal_id,
            expected_version=expected_version,
            actor=actor,
            reason=reason,
            now=self._clock(),
        )

    def deactivate(
        self, rule_id: UUID, expected_ruleset_version: int, actor: str, reason: str
    ) -> RuleSetRecord:
        return self._store.deactivate(
            rule_id,
            expected_ruleset_version=expected_ruleset_version,
            actor=actor,
            reason=reason,
            now=self._clock(),
        )

    def rollback(
        self, target_version: int, expected_ruleset_version: int, actor: str, reason: str
    ) -> RuleSetRecord:
        return self._store.rollback(
            target_version,
            expected_ruleset_version=expected_ruleset_version,
            actor=actor,
            reason=reason,
            now=self._clock(),
        )

    def active_ruleset(self) -> RuleSetRecord:
        return self._store.active_ruleset()


class InMemoryRuleProposalStore:
    def __init__(self) -> None:
        now = datetime(2026, 1, 1, tzinfo=timezone.utc)
        self.proposals: dict[UUID, ProposalRecord] = {}
        self.rulesets: dict[int, RuleSetRecord] = {
            0: RuleSetRecord(
                version=0,
                parent_version=None,
                operation="INITIAL",
                rules=(),
                source_proposal_id=None,
                created_by="system",
                created_at=now,
                reason="Initial empty dynamic ruleset",
            )
        }
        self.active_version = 0
        self.audit: list[dict[str, Any]] = []

    def insert_proposal(self, proposal: ProposalRecord) -> ProposalRecord:
        self.proposals[proposal.proposal_id] = proposal
        self.audit.append({"event": "GENERATED", "proposalId": proposal.proposal_id})
        return proposal

    def get_proposal(self, proposal_id: UUID) -> ProposalRecord:
        try:
            return self.proposals[proposal_id]
        except KeyError as error:
            raise ProposalNotFoundError("rule proposal not found") from error

    def approve(
        self,
        proposal_id: UUID,
        *,
        expected_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> RuleSetRecord:
        proposal = self.get_proposal(proposal_id)
        if proposal.status is not ProposalStatus.DRAFT or proposal.version != expected_version:
            raise ProposalConflictError("proposal is stale or is not a draft")
        assert proposal.candidate is not None
        active = self.active_ruleset()
        reason_codes = {rule.reason_code for rule in active.rules}
        if reason_codes & {rule.reason_code for rule in proposal.candidate.rules}:
            raise ProposalConflictError("proposal duplicates an active reason code")
        active_signatures = {rule_signature(rule) for rule in active.rules}
        if active_signatures & {
            rule_signature(rule) for rule in proposal.candidate.rules
        }:
            raise ProposalConflictError("proposal duplicates active rule conditions")
        rules = list(active.rules)
        for index, proposed in enumerate(proposal.candidate.rules):
            rules.append(
                ActiveRule(
                    rule_id=uuid5(RULE_NAMESPACE, f"{proposal_id}:{index}"),
                    source_proposal_id=proposal_id,
                    name=proposed.name,
                    conditions=proposed.conditions,
                    action=proposed.action,
                    reason_code=proposed.reason_code,
                    source_cluster_ids=proposed.source_cluster_ids,
                    rationale=proposed.rationale,
                    expires_at=now + timedelta(days=proposed.expires_in_days),
                )
            )
        ruleset = self._activate(
            rules=tuple(rules),
            operation="APPROVE",
            actor=actor,
            reason=reason,
            now=now,
            source_proposal_id=proposal_id,
        )
        self.proposals[proposal_id] = proposal.model_copy(
            update={
                "status": ProposalStatus.APPROVED,
                "version": proposal.version + 1,
                "reviewed_by": actor,
                "reviewed_at": now,
                "review_reason": reason,
                "approved_ruleset_version": ruleset.version,
            }
        )
        return ruleset

    def reject(
        self,
        proposal_id: UUID,
        *,
        expected_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> ProposalRecord:
        proposal = self.get_proposal(proposal_id)
        if proposal.status is not ProposalStatus.DRAFT or proposal.version != expected_version:
            raise ProposalConflictError("proposal is stale or is not a draft")
        rejected = proposal.model_copy(
            update={
                "status": ProposalStatus.REJECTED,
                "version": proposal.version + 1,
                "reviewed_by": actor,
                "reviewed_at": now,
                "review_reason": reason,
            }
        )
        self.proposals[proposal_id] = rejected
        self.audit.append({"event": "REJECT", "proposalId": proposal_id})
        return rejected

    def deactivate(
        self,
        rule_id: UUID,
        *,
        expected_ruleset_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> RuleSetRecord:
        active = self._expected_active(expected_ruleset_version)
        rules = tuple(rule for rule in active.rules if rule.rule_id != rule_id)
        if len(rules) == len(active.rules):
            raise ProposalNotFoundError("active rule not found")
        return self._activate(
            rules=rules,
            operation="DEACTIVATE",
            actor=actor,
            reason=reason,
            now=now,
        )

    def rollback(
        self,
        target_version: int,
        *,
        expected_ruleset_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> RuleSetRecord:
        self._expected_active(expected_ruleset_version)
        try:
            target = self.rulesets[target_version]
        except KeyError as error:
            raise ProposalNotFoundError("target ruleset not found") from error
        return self._activate(
            rules=target.rules,
            operation="ROLLBACK",
            actor=actor,
            reason=reason,
            now=now,
        )

    def active_ruleset(self) -> RuleSetRecord:
        return self.rulesets[self.active_version]

    def _expected_active(self, expected: int) -> RuleSetRecord:
        if self.active_version != expected:
            raise ProposalConflictError("active ruleset version is stale")
        return self.active_ruleset()

    def _activate(
        self,
        *,
        rules: tuple[ActiveRule, ...],
        operation: str,
        actor: str,
        reason: str,
        now: datetime,
        source_proposal_id: UUID | None = None,
    ) -> RuleSetRecord:
        version = max(self.rulesets) + 1
        record = RuleSetRecord(
            version=version,
            parent_version=self.active_version,
            operation=operation,
            rules=rules,
            source_proposal_id=source_proposal_id,
            created_by=actor,
            created_at=now,
            reason=reason,
        )
        self.rulesets[version] = record
        self.active_version = version
        self.audit.append({"event": operation, "rulesetVersion": version})
        return record
