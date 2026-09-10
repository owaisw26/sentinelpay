import json
import os
from datetime import datetime, timezone
from decimal import Decimal
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from app.contracts import RiskAction, RiskFeatures
from app.history import InMemoryRiskHistory
from app.main import create_app
from app.proposals import (
    InMemoryRuleProposalStore,
    ModelCallError,
    ModelUsage,
    OpenAIRuleProposalClient,
    ProposalConflictError,
    ProposalStatus,
    RawModelGeneration,
    RuleProposalService,
    StubRuleProposalClient,
    build_generation_context,
)
from app.risk import RiskEvaluationPipeline
from app.rules import DynamicRuleEvaluator, RuleCondition, RuleProposalOutput, more_severe
from app.synthetic import generate_synthetic_dataset
from tests.factories import screening_event


NOW = datetime(2026, 9, 9, 12, 0, tzinfo=timezone.utc)


def _candidate() -> RuleProposalOutput:
    return RuleProposalOutput.model_validate_json(
        json.dumps(
            {
                "rules": [
                    {
                        "name": "Elevated new payee amount",
                        "sourceClusterIds": ["cluster-1"],
                        "conditions": [
                            {
                                "feature": "amount",
                                "operator": "GREATER_THAN_OR_EQUAL",
                                "value": 1000,
                            },
                            {
                                "feature": "first_time_payee",
                                "operator": "EQUALS",
                                "value": True,
                            },
                        ],
                        "action": "HOLD",
                        "reasonCode": "AI_ELEVATED_NEW_PAYEE_AMOUNT",
                        "expiresInDays": 30,
                        "rationale": (
                            "Elevated amounts to new payees are concentrated "
                            "in the referenced synthetic fraud cluster."
                        ),
                    }
                ]
            }
        )
    )


def _small_dataset():
    return generate_synthetic_dataset(
        training_customers=40,
        validation_customers=15,
        transactions_per_customer=10,
    )


def test_rule_schema_rejects_semantically_unsafe_feature_operator_combinations():
    with pytest.raises(ValidationError, match="boolean features require EQUALS"):
        RuleCondition.model_validate_json(
            '{"feature":"device_changed",'
            '"operator":"GREATER_THAN_OR_EQUAL","value":1}'
        )

    with pytest.raises(ValidationError, match="outside the allowed range"):
        RuleCondition.model_validate_json(
            '{"feature":"amount",'
            '"operator":"GREATER_THAN_OR_EQUAL","value":0.01}'
        )


def test_cluster_context_is_reproducible_and_contains_only_aggregates():
    dataset = _small_dataset()
    first = build_generation_context(dataset)
    second = build_generation_context(dataset)
    serialized = first.model_dump_json(by_alias=True)

    assert first == second
    assert first.synthetic_fraud_case_count > 0
    assert "customerId" not in serialized
    assert "transactionId" not in serialized
    assert "deviceToken" not in serialized


def test_approval_changes_future_decisions_and_versioning_supports_rollback():
    store = InMemoryRuleProposalStore()
    service = RuleProposalService(
        store,
        StubRuleProposalClient(_candidate()),
        dataset=_small_dataset(),
        clock=lambda: NOW,
    )
    proposal = service.generate("analyst-1")

    assert proposal.status is ProposalStatus.DRAFT
    assert store.active_ruleset().version == 0
    approved = service.approve(
        proposal.proposal_id, proposal.version, "analyst-1", "Useful signal"
    )
    assert approved.version == 1

    event = screening_event(amount=Decimal("1500.00"), occurred_at=NOW)
    result = RiskEvaluationPipeline(
        dynamic_ruleset=approved.dynamic_ruleset()
    ).evaluate(
        event,
        InMemoryRiskHistory().snapshot(
            event.payload.customer_id, event.occurred_at
        ),
    )
    assert result.decision_event.payload.action is RiskAction.HOLD
    assert result.decision_event.payload.matched_rule_ids == (
        approved.rules[0].rule_id,
    )
    assert result.decision_event.payload.ruleset_version.endswith("dynamic-1")
    assert more_severe(RiskAction.BLOCK, RiskAction.HOLD) is RiskAction.BLOCK
    assert DynamicRuleEvaluator().evaluate(
        result.audit_record.features,
        approved.dynamic_ruleset(),
        evaluated_at=datetime(2027, 1, 1, tzinfo=timezone.utc),
    ).rule_ids == ()

    with pytest.raises(ProposalConflictError):
        service.approve(
            proposal.proposal_id, proposal.version, "analyst-2", "Stale"
        )

    deactivated = service.deactivate(
        approved.rules[0].rule_id,
        approved.version,
        "analyst-1",
        "False positive review",
    )
    assert deactivated.version == 2
    assert deactivated.rules == ()

    rolled_back = service.rollback(
        1, deactivated.version, "analyst-1", "Restore prior approved version"
    )
    assert rolled_back.version == 3
    assert rolled_back.rules == approved.rules


def test_rejection_never_changes_the_active_ruleset():
    store = InMemoryRuleProposalStore()
    service = RuleProposalService(
        store,
        StubRuleProposalClient(_candidate()),
        dataset=_small_dataset(),
        clock=lambda: NOW,
    )
    proposal = service.generate("analyst-1")

    rejected = service.reject(
        proposal.proposal_id, 0, "analyst-1", "Insufficient benefit"
    )

    assert rejected.status is ProposalStatus.REJECTED
    assert store.active_ruleset().version == 0
    with pytest.raises(ProposalConflictError):
        service.approve(proposal.proposal_id, 0, "analyst-1", "Changed mind")


def test_malformed_model_output_is_audited_but_cannot_be_approved():
    class MalformedClient:
        def generate(self, _context):
            return RawModelGeneration(
                response_id="malformed-1",
                model="contract-stub-v1",
                output_text='{"rules":[{"action":"APPROVE"}]}',
                usage=ModelUsage(
                    input_tokens=10, output_tokens=5, total_tokens=15
                ),
            )

    store = InMemoryRuleProposalStore()
    service = RuleProposalService(
        store,
        MalformedClient(),
        dataset=_small_dataset(),
        clock=lambda: NOW,
    )
    proposal = service.generate("analyst-1")

    assert proposal.status is ProposalStatus.VALIDATION_FAILED
    assert proposal.candidate is None
    assert proposal.validation_failures
    with pytest.raises(ProposalConflictError):
        service.approve(proposal.proposal_id, 0, "analyst-1", "Unsafe")


def test_openai_adapter_sends_stateless_tool_free_structured_request():
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        captured.update(json.loads(request.content))
        output = _candidate().model_dump_json(by_alias=True)
        return httpx.Response(
            200,
            json={
                "id": "resp_test",
                "model": "gpt-5.4-mini-2026-03-17",
                "status": "completed",
                "output": [
                    {
                        "type": "message",
                        "status": "completed",
                        "content": [{"type": "output_text", "text": output}],
                    }
                ],
                "usage": {
                    "input_tokens": 100,
                    "output_tokens": 50,
                    "total_tokens": 150,
                },
            },
        )

    client = OpenAIRuleProposalClient(
        "test-key",
        transport=httpx.MockTransport(handler),
        sleeper=lambda _seconds: None,
    )
    result = client.generate(build_generation_context(_small_dataset()))

    assert result.response_id == "resp_test"
    assert captured["store"] is False
    assert captured["tools"] == []
    assert captured["max_output_tokens"] == 8000
    assert captured["reasoning"] == {"effort": "none"}
    assert captured["text"]["verbosity"] == "low"
    assert captured["text"]["format"]["type"] == "json_schema"
    assert captured["text"]["format"]["strict"] is True
    schema = captured["text"]["format"]["schema"]
    assert schema["properties"]["rules"]["maxItems"] == 1
    assert schema["$defs"]["RuleCondition"]["properties"]["value"] == {
        "anyOf": [{"type": "boolean"}, {"type": "number"}]
    }


def test_openai_adapter_reports_incomplete_response_reason():
    def handler(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json={
                "id": "resp_incomplete",
                "status": "incomplete",
                "incomplete_details": {"reason": "max_output_tokens"},
                "usage": {
                    "output_tokens": 8000,
                    "output_tokens_details": {"reasoning_tokens": 7900},
                },
            },
        )

    client = OpenAIRuleProposalClient(
        "test-key",
        transport=httpx.MockTransport(handler),
    )

    with pytest.raises(ModelCallError, match="reasoning_tokens=7900"):
        client.generate(build_generation_context(_small_dataset()))


def test_openai_adapter_reports_quota_code_without_retrying():
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(
            429,
            headers={"x-request-id": "req_quota"},
            json={
                "error": {
                    "code": "credit_balance_exhausted",
                    "type": "insufficient_quota",
                    "message": "Add credits to continue.",
                }
            },
        )

    client = OpenAIRuleProposalClient(
        "test-key",
        transport=httpx.MockTransport(handler),
        sleeper=lambda _seconds: None,
    )

    with pytest.raises(ModelCallError, match="credit_balance_exhausted") as error:
        client.generate(build_generation_context(_small_dataset()))

    assert calls == 1
    assert "req_quota" in str(error.value)


def test_openai_adapter_retries_a_temporary_rate_limit():
    calls = 0

    def handler(_request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        if calls == 1:
            return httpx.Response(
                429,
                json={
                    "error": {
                        "code": "rate_limit_exceeded",
                        "type": "requests",
                        "message": "Please retry.",
                    }
                },
            )
        return httpx.Response(
            200,
            json={
                "id": "resp_after_retry",
                "model": "gpt-5.4-mini-2026-03-17",
                "status": "completed",
                "output": [
                    {
                        "type": "message",
                        "status": "completed",
                        "content": [
                            {
                                "type": "output_text",
                                "text": _candidate().model_dump_json(by_alias=True),
                            }
                        ],
                    }
                ],
                "usage": {
                    "input_tokens": 100,
                    "output_tokens": 50,
                    "total_tokens": 150,
                },
            },
        )

    client = OpenAIRuleProposalClient(
        "test-key",
        transport=httpx.MockTransport(handler),
        sleeper=lambda _seconds: None,
    )

    assert client.generate(build_generation_context(_small_dataset())).response_id == (
        "resp_after_retry"
    )
    assert calls == 2


def test_internal_api_requires_service_credential_and_uses_analyst_subject():
    store = InMemoryRuleProposalStore()
    service = RuleProposalService(
        store,
        StubRuleProposalClient(_candidate()),
        dataset=_small_dataset(),
        clock=lambda: NOW,
    )
    client = TestClient(create_app(service, internal_token="internal-test-token"))

    assert client.post("/internal/rule-proposals").status_code == 401
    response = client.post(
        "/internal/rule-proposals",
        headers={
            "X-Internal-Token": "internal-test-token",
            "X-Analyst-Subject": "analyst-42",
        },
    )
    assert response.status_code == 201
    assert response.json()["status"] == "DRAFT"
    assert response.json()["generatedBy"] == "analyst-42"


@pytest.mark.live_openai
@pytest.mark.skipif(
    not os.getenv("OPENAI_API_KEY"),
    reason="OPENAI_API_KEY is required for the opt-in live acceptance call",
)
def test_live_openai_call_produces_a_valid_draft():
    store = InMemoryRuleProposalStore()
    service = RuleProposalService(
        store,
        OpenAIRuleProposalClient(os.environ["OPENAI_API_KEY"]),
        clock=lambda: NOW,
    )

    proposal = service.generate("live-acceptance-analyst")

    assert proposal.status is ProposalStatus.DRAFT
    assert proposal.model == "gpt-5.4-mini-2026-03-17"
    assert proposal.candidate is not None
    assert proposal.usage.total_tokens > 0
