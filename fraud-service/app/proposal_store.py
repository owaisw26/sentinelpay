from __future__ import annotations

import json
from datetime import datetime, timedelta
from typing import Any
from uuid import UUID, uuid4, uuid5

from psycopg.types.json import Jsonb

from app.proposals import (
    RULE_NAMESPACE,
    ProposalConflictError,
    ProposalNotFoundError,
    ProposalRecord,
    ProposalStatus,
    RuleSetRecord,
)
from app.rules import ActiveRule, rule_signature


class PostgresRuleProposalStore:
    def __init__(self, connection: Any) -> None:
        self._connection = connection

    def insert_proposal(self, proposal: ProposalRecord) -> ProposalRecord:
        with self._connection.transaction():
            with self._connection.cursor() as cursor:
                cursor.execute(
                    """
                    INSERT INTO fraud.rule_proposals(
                        proposal_id, status, version, prompt_version,
                        schema_version, model, provider_response_id,
                        response_sha256, input_summary, candidate, impact,
                        usage, validation_failures, generated_by, generated_at
                    ) VALUES (
                        %s, %s, %s, %s, %s, %s, %s, %s, %s, %s,
                        %s, %s, %s, %s, %s
                    )
                    """,
                    (
                        proposal.proposal_id,
                        proposal.status.value,
                        proposal.version,
                        proposal.prompt_version,
                        proposal.schema_version,
                        proposal.model,
                        proposal.provider_response_id,
                        proposal.response_sha256,
                        self._json(proposal.input_summary),
                        self._json(proposal.candidate) if proposal.candidate else None,
                        self._json(proposal.impact) if proposal.impact else None,
                        self._json(proposal.usage),
                        Jsonb(list(proposal.validation_failures)),
                        proposal.generated_by,
                        proposal.generated_at,
                    ),
                )
                self._audit(
                    cursor,
                    "GENERATED",
                    actor=proposal.generated_by,
                    reason="Rule proposal generated",
                    occurred_at=proposal.generated_at,
                    proposal_id=proposal.proposal_id,
                    details={"status": proposal.status.value},
                )
        return proposal

    def get_proposal(self, proposal_id: UUID) -> ProposalRecord:
        with self._connection.cursor() as cursor:
            row = self._select_proposal(cursor, proposal_id)
        if row is None:
            raise ProposalNotFoundError("rule proposal not found")
        return self._proposal(row)

    def approve(
        self,
        proposal_id: UUID,
        *,
        expected_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> RuleSetRecord:
        with self._connection.transaction():
            with self._connection.cursor() as cursor:
                row = self._select_proposal(cursor, proposal_id, for_update=True)
                if row is None:
                    raise ProposalNotFoundError("rule proposal not found")
                proposal = self._proposal(row)
                if (
                    proposal.status is not ProposalStatus.DRAFT
                    or proposal.version != expected_version
                ):
                    raise ProposalConflictError("proposal is stale or is not a draft")
                assert proposal.candidate is not None
                active = self._active(cursor, for_update=True)
                active_codes = {item.reason_code for item in active.rules}
                candidate_codes = {
                    item.reason_code for item in proposal.candidate.rules
                }
                if active_codes & candidate_codes:
                    raise ProposalConflictError(
                        "proposal duplicates an active reason code"
                    )
                active_signatures = {rule_signature(item) for item in active.rules}
                candidate_signatures = {
                    rule_signature(item) for item in proposal.candidate.rules
                }
                if active_signatures & candidate_signatures:
                    raise ProposalConflictError(
                        "proposal duplicates active rule conditions"
                    )
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
                            expires_at=now
                            + timedelta(days=proposed.expires_in_days),
                        )
                    )
                ruleset = self._activate(
                    cursor,
                    parent=active,
                    rules=tuple(rules),
                    operation="APPROVE",
                    actor=actor,
                    reason=reason,
                    now=now,
                    source_proposal_id=proposal_id,
                )
                cursor.execute(
                    """
                    UPDATE fraud.rule_proposals
                    SET status = 'APPROVED', version = version + 1,
                        reviewed_by = %s, reviewed_at = %s,
                        review_reason = %s, approved_ruleset_version = %s
                    WHERE proposal_id = %s AND version = %s AND status = 'DRAFT'
                    """,
                    (
                        actor,
                        now,
                        reason,
                        ruleset.version,
                        proposal_id,
                        expected_version,
                    ),
                )
                if cursor.rowcount != 1:
                    raise ProposalConflictError("proposal approval lost its lease")
                self._audit(
                    cursor,
                    "APPROVED",
                    actor=actor,
                    reason=reason,
                    occurred_at=now,
                    proposal_id=proposal_id,
                    ruleset_version=ruleset.version,
                    details={"previousRulesetVersion": active.version},
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
        with self._connection.transaction():
            with self._connection.cursor() as cursor:
                cursor.execute(
                    """
                    UPDATE fraud.rule_proposals
                    SET status = 'REJECTED', version = version + 1,
                        reviewed_by = %s, reviewed_at = %s, review_reason = %s
                    WHERE proposal_id = %s AND version = %s AND status = 'DRAFT'
                    """,
                    (actor, now, reason, proposal_id, expected_version),
                )
                if cursor.rowcount != 1:
                    if self._select_proposal(cursor, proposal_id) is None:
                        raise ProposalNotFoundError("rule proposal not found")
                    raise ProposalConflictError("proposal is stale or is not a draft")
                self._audit(
                    cursor,
                    "REJECTED",
                    actor=actor,
                    reason=reason,
                    occurred_at=now,
                    proposal_id=proposal_id,
                )
                row = self._select_proposal(cursor, proposal_id)
                assert row is not None
                return self._proposal(row)

    def deactivate(
        self,
        rule_id: UUID,
        *,
        expected_ruleset_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> RuleSetRecord:
        with self._connection.transaction():
            with self._connection.cursor() as cursor:
                active = self._active(cursor, for_update=True)
                if active.version != expected_ruleset_version:
                    raise ProposalConflictError("active ruleset version is stale")
                rules = tuple(item for item in active.rules if item.rule_id != rule_id)
                if len(rules) == len(active.rules):
                    raise ProposalNotFoundError("active rule not found")
                ruleset = self._activate(
                    cursor,
                    parent=active,
                    rules=rules,
                    operation="DEACTIVATE",
                    actor=actor,
                    reason=reason,
                    now=now,
                )
                self._audit(
                    cursor,
                    "DEACTIVATED",
                    actor=actor,
                    reason=reason,
                    occurred_at=now,
                    ruleset_version=ruleset.version,
                    details={"ruleId": str(rule_id)},
                )
                return ruleset

    def rollback(
        self,
        target_version: int,
        *,
        expected_ruleset_version: int,
        actor: str,
        reason: str,
        now: datetime,
    ) -> RuleSetRecord:
        with self._connection.transaction():
            with self._connection.cursor() as cursor:
                active = self._active(cursor, for_update=True)
                if active.version != expected_ruleset_version:
                    raise ProposalConflictError("active ruleset version is stale")
                target = self._ruleset(cursor, target_version)
                if target is None:
                    raise ProposalNotFoundError("target ruleset not found")
                ruleset = self._activate(
                    cursor,
                    parent=active,
                    rules=target.rules,
                    operation="ROLLBACK",
                    actor=actor,
                    reason=reason,
                    now=now,
                )
                self._audit(
                    cursor,
                    "ROLLED_BACK",
                    actor=actor,
                    reason=reason,
                    occurred_at=now,
                    ruleset_version=ruleset.version,
                    details={"targetRulesetVersion": target_version},
                )
                return ruleset

    def active_ruleset(self) -> RuleSetRecord:
        with self._connection.cursor() as cursor:
            return self._active(cursor)

    def _activate(
        self,
        cursor: Any,
        *,
        parent: RuleSetRecord,
        rules: tuple[ActiveRule, ...],
        operation: str,
        actor: str,
        reason: str,
        now: datetime,
        source_proposal_id: UUID | None = None,
    ) -> RuleSetRecord:
        cursor.execute("SELECT pg_advisory_xact_lock(hashtext('fraud-ruleset-version'))")
        cursor.execute("SELECT COALESCE(MAX(version), 0) + 1 FROM fraud.rule_sets")
        version = cursor.fetchone()[0]
        record = RuleSetRecord(
            version=version,
            parent_version=parent.version,
            operation=operation,
            rules=rules,
            source_proposal_id=source_proposal_id,
            created_by=actor,
            created_at=now,
            reason=reason,
        )
        cursor.execute(
            """
            INSERT INTO fraud.rule_sets(
                version, parent_version, operation, rules,
                source_proposal_id, created_by, created_at, reason
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                record.version,
                record.parent_version,
                record.operation,
                Jsonb(
                    [
                        item.model_dump(mode="json", by_alias=True)
                        for item in record.rules
                    ]
                ),
                record.source_proposal_id,
                record.created_by,
                record.created_at,
                record.reason,
            ),
        )
        cursor.execute(
            """
            UPDATE fraud.active_rule_set
            SET version = %s, activated_at = %s, activated_by = %s
            WHERE singleton = TRUE AND version = %s
            """,
            (record.version, now, actor, parent.version),
        )
        if cursor.rowcount != 1:
            raise ProposalConflictError("active ruleset version changed")
        return record

    def _select_proposal(
        self, cursor: Any, proposal_id: UUID, *, for_update: bool = False
    ) -> Any:
        cursor.execute(
            """
            SELECT proposal_id, status, version, prompt_version,
                   schema_version, model, provider_response_id,
                   response_sha256, input_summary, candidate, impact,
                   usage, validation_failures, generated_by, generated_at,
                   reviewed_by, reviewed_at, review_reason,
                   approved_ruleset_version
            FROM fraud.rule_proposals
            WHERE proposal_id = %s
            """ + (" FOR UPDATE" if for_update else ""),
            (proposal_id,),
        )
        return cursor.fetchone()

    def _proposal(self, row: Any) -> ProposalRecord:
        return ProposalRecord.model_validate_json(
            json.dumps(
                {
                    "proposalId": str(row[0]),
                    "status": row[1],
                    "version": row[2],
                    "promptVersion": row[3],
                    "schemaVersion": row[4],
                    "model": row[5],
                    "providerResponseId": row[6],
                    "responseSha256": row[7],
                    "inputSummary": row[8],
                    "candidate": row[9],
                    "impact": row[10],
                    "usage": row[11],
                    "validationFailures": row[12],
                    "generatedBy": row[13],
                    "generatedAt": row[14].isoformat(),
                    "reviewedBy": row[15],
                    "reviewedAt": row[16].isoformat() if row[16] else None,
                    "reviewReason": row[17],
                    "approvedRulesetVersion": row[18],
                }
            )
        )

    def _active(self, cursor: Any, *, for_update: bool = False) -> RuleSetRecord:
        cursor.execute(
            "SELECT version FROM fraud.active_rule_set WHERE singleton = TRUE"
            + (" FOR UPDATE" if for_update else "")
        )
        row = cursor.fetchone()
        if row is None:
            raise RuntimeError("active ruleset pointer is missing")
        ruleset = self._ruleset(cursor, row[0])
        if ruleset is None:
            raise RuntimeError("active ruleset is missing")
        return ruleset

    def _ruleset(self, cursor: Any, version: int) -> RuleSetRecord | None:
        cursor.execute(
            """
            SELECT version, parent_version, operation, rules,
                   source_proposal_id, created_by, created_at, reason
            FROM fraud.rule_sets WHERE version = %s
            """,
            (version,),
        )
        row = cursor.fetchone()
        if row is None:
            return None
        return RuleSetRecord.model_validate_json(
            json.dumps(
                {
                    "version": row[0],
                    "parentVersion": row[1],
                    "operation": row[2],
                    "rules": row[3],
                    "sourceProposalId": str(row[4]) if row[4] else None,
                    "createdBy": row[5],
                    "createdAt": row[6].isoformat(),
                    "reason": row[7],
                }
            )
        )

    def _audit(
        self,
        cursor: Any,
        event_type: str,
        *,
        actor: str,
        reason: str,
        occurred_at: datetime,
        proposal_id: UUID | None = None,
        ruleset_version: int | None = None,
        details: dict[str, Any] | None = None,
    ) -> None:
        cursor.execute(
            """
            INSERT INTO fraud.rule_audit(
                audit_id, event_type, proposal_id, ruleset_version,
                actor, reason, details, occurred_at
            ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
            """,
            (
                uuid4(),
                event_type,
                proposal_id,
                ruleset_version,
                actor,
                reason,
                Jsonb(details or {}),
                occurred_at,
            ),
        )

    @staticmethod
    def _json(model: Any) -> Jsonb:
        return Jsonb(model.model_dump(mode="json", by_alias=True))
