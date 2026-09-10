from __future__ import annotations

import hmac
import os
from functools import lru_cache
from uuid import UUID

from fastapi import Depends, FastAPI, Header, HTTPException, status
from pydantic import Field

from app.contracts import StrictContract
from app.proposal_store import PostgresRuleProposalStore
from app.proposals import (
    DEFAULT_OPENAI_MODEL,
    OpenAIRuleProposalClient,
    ProposalConflictError,
    ProposalNotFoundError,
    ProposalRecord,
    RuleProposalService,
    RuleSetRecord,
    StubRuleProposalClient,
)
from app.store import apply_migrations
from app.worker import _load_anomaly_model


class ProposalReviewRequest(StrictContract):
    expected_version: int = Field(ge=0)
    reason: str = Field(min_length=3, max_length=500)


class RuleDeactivationRequest(StrictContract):
    expected_ruleset_version: int = Field(ge=0)
    reason: str = Field(min_length=3, max_length=500)


class RulesetRollbackRequest(StrictContract):
    expected_ruleset_version: int = Field(ge=0)
    reason: str = Field(min_length=3, max_length=500)


def create_app(
    service: RuleProposalService | None = None,
    *,
    internal_token: str | None = None,
) -> FastAPI:
    application = FastAPI(title="SentinelPay Fraud Service", version="0.2.0")

    def proposal_service() -> RuleProposalService:
        return service or _runtime_service()

    def analyst_subject(
        x_internal_token: str | None = Header(
            default=None, alias="X-Internal-Token"
        ),
        x_analyst_subject: str | None = Header(
            default=None, alias="X-Analyst-Subject", max_length=255
        ),
    ) -> str:
        expected = internal_token or os.environ.get("FRAUD_INTERNAL_TOKEN")
        if (
            not expected
            or not x_internal_token
            or not hmac.compare_digest(x_internal_token, expected)
            or not x_analyst_subject
        ):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail="Invalid internal service credential",
            )
        return x_analyst_subject

    @application.exception_handler(ProposalNotFoundError)
    async def not_found_handler(_request, error: ProposalNotFoundError):
        return _problem(404, "RULE_RESOURCE_NOT_FOUND", str(error))

    @application.exception_handler(ProposalConflictError)
    async def conflict_handler(_request, error: ProposalConflictError):
        return _problem(409, "RULE_VERSION_CONFLICT", str(error))

    @application.get("/health")
    def health():
        return {"status": "up"}

    @application.post(
        "/internal/rule-proposals",
        response_model=ProposalRecord,
        status_code=status.HTTP_201_CREATED,
    )
    def generate_rule_proposal(
        actor: str = Depends(analyst_subject),
        workflow: RuleProposalService = Depends(proposal_service),
    ) -> ProposalRecord:
        return workflow.generate(actor)

    @application.get(
        "/internal/rule-proposals/{proposal_id}",
        response_model=ProposalRecord,
    )
    def get_rule_proposal(
        proposal_id: UUID,
        _actor: str = Depends(analyst_subject),
        workflow: RuleProposalService = Depends(proposal_service),
    ) -> ProposalRecord:
        return workflow.get(proposal_id)

    @application.post(
        "/internal/rule-proposals/{proposal_id}/approve",
        response_model=RuleSetRecord,
    )
    def approve_rule_proposal(
        proposal_id: UUID,
        request: ProposalReviewRequest,
        actor: str = Depends(analyst_subject),
        workflow: RuleProposalService = Depends(proposal_service),
    ) -> RuleSetRecord:
        return workflow.approve(
            proposal_id, request.expected_version, actor, request.reason
        )

    @application.post(
        "/internal/rule-proposals/{proposal_id}/reject",
        response_model=ProposalRecord,
    )
    def reject_rule_proposal(
        proposal_id: UUID,
        request: ProposalReviewRequest,
        actor: str = Depends(analyst_subject),
        workflow: RuleProposalService = Depends(proposal_service),
    ) -> ProposalRecord:
        return workflow.reject(
            proposal_id, request.expected_version, actor, request.reason
        )

    @application.post(
        "/internal/rules/{rule_id}/deactivate",
        response_model=RuleSetRecord,
    )
    def deactivate_rule(
        rule_id: UUID,
        request: RuleDeactivationRequest,
        actor: str = Depends(analyst_subject),
        workflow: RuleProposalService = Depends(proposal_service),
    ) -> RuleSetRecord:
        return workflow.deactivate(
            rule_id,
            request.expected_ruleset_version,
            actor,
            request.reason,
        )

    @application.post(
        "/internal/rulesets/{target_version}/rollback",
        response_model=RuleSetRecord,
    )
    def rollback_ruleset(
        target_version: int,
        request: RulesetRollbackRequest,
        actor: str = Depends(analyst_subject),
        workflow: RuleProposalService = Depends(proposal_service),
    ) -> RuleSetRecord:
        return workflow.rollback(
            target_version,
            request.expected_ruleset_version,
            actor,
            request.reason,
        )

    @application.get(
        "/internal/rulesets/active",
        response_model=RuleSetRecord,
    )
    def active_ruleset(
        _actor: str = Depends(analyst_subject),
        workflow: RuleProposalService = Depends(proposal_service),
    ) -> RuleSetRecord:
        return workflow.active_ruleset()

    return application


@lru_cache(maxsize=1)
def _runtime_service() -> RuleProposalService:
    import psycopg

    connection = psycopg.connect(os.environ["FRAUD_DB_DSN"], autocommit=True)
    if os.getenv("FRAUD_DB_MIGRATE", "false").lower() == "true":
        apply_migrations(connection)
    mode = os.getenv("RULE_PROPOSAL_PROVIDER", "stub").lower()
    if mode == "openai":
        client = OpenAIRuleProposalClient(
            os.environ["OPENAI_API_KEY"],
            model=os.getenv("OPENAI_RULE_MODEL", DEFAULT_OPENAI_MODEL),
            timeout_seconds=float(os.getenv("OPENAI_TIMEOUT_SECONDS", "20")),
            max_output_tokens=int(
                os.getenv("OPENAI_MAX_OUTPUT_TOKENS", "8000")
            ),
        )
    elif mode == "stub":
        client = StubRuleProposalClient()
    else:
        raise RuntimeError("RULE_PROPOSAL_PROVIDER must be 'stub' or 'openai'")
    return RuleProposalService(
        PostgresRuleProposalStore(connection),
        client,
        anomaly_model=_load_anomaly_model(),
    )


def _problem(status_code: int, error_code: str, detail: str):
    from fastapi.responses import JSONResponse

    return JSONResponse(
        status_code=status_code,
        content={
            "type": (
                "urn:sentinelpay:problem:"
                + error_code.lower().replace("_", "-")
            ),
            "title": "Conflict" if status_code == 409 else "Not Found",
            "status": status_code,
            "detail": detail,
            "errorCode": error_code,
        },
    )


app = create_app()
