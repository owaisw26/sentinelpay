from datetime import datetime, timezone

import psycopg
import pytest
from testcontainers.community.postgres import PostgresContainer

from app.proposal_store import PostgresRuleProposalStore
from app.proposals import (
    ProposalConflictError,
    ProposalStatus,
    RuleProposalService,
    StubRuleProposalClient,
)
from app.store import apply_migrations
from app.synthetic import generate_synthetic_dataset


NOW = datetime(2026, 9, 9, 12, 0, tzinfo=timezone.utc)


@pytest.fixture(scope="module")
def database():
    with PostgresContainer("postgres:17-alpine") as postgres:
        dsn = postgres.get_connection_url().replace(
            "postgresql+psycopg2", "postgresql"
        )
        connection = psycopg.connect(dsn, autocommit=True)
        apply_migrations(connection)
        yield connection
        connection.close()


@pytest.mark.postgres
def test_postgres_approval_deactivation_and_rollback_are_versioned(database):
    store = PostgresRuleProposalStore(database)
    service = RuleProposalService(
        store,
        StubRuleProposalClient(),
        dataset=generate_synthetic_dataset(
            training_customers=40,
            validation_customers=15,
            transactions_per_customer=10,
        ),
        clock=lambda: NOW,
    )
    proposal = service.generate("analyst-postgres")

    assert proposal.status is ProposalStatus.DRAFT
    assert service.get(proposal.proposal_id) == proposal
    approved = service.approve(
        proposal.proposal_id,
        proposal.version,
        "analyst-postgres",
        "Approve after impact review",
    )
    assert approved.version == 1
    assert store.active_ruleset() == approved

    with pytest.raises(ProposalConflictError):
        service.approve(
            proposal.proposal_id,
            proposal.version,
            "analyst-postgres",
            "Stale approval",
        )

    deactivated = service.deactivate(
        approved.rules[0].rule_id,
        approved.version,
        "analyst-postgres",
        "Deactivate after false positives",
    )
    assert deactivated.version == 2
    assert deactivated.rules == ()

    restored = service.rollback(
        approved.version,
        deactivated.version,
        "analyst-postgres",
        "Restore last known good ruleset",
    )
    assert restored.version == 3
    assert restored.parent_version == 2
    assert restored.rules == approved.rules

    with database.cursor() as cursor:
        cursor.execute(
            "SELECT event_type FROM fraud.rule_audit "
            "WHERE proposal_id = %s ORDER BY occurred_at, event_type",
            (proposal.proposal_id,),
        )
        assert {row[0] for row in cursor.fetchall()} == {
            "GENERATED",
            "APPROVED",
        }


@pytest.mark.postgres
def test_rule_sets_and_rule_audit_are_append_only(database):
    with pytest.raises(psycopg.errors.RaiseException):
        with database.transaction():
            with database.cursor() as cursor:
                cursor.execute(
                    "UPDATE fraud.rule_sets SET reason = 'rewritten' "
                    "WHERE version = 0"
                )

    with pytest.raises(psycopg.errors.RaiseException):
        with database.transaction():
            with database.cursor() as cursor:
                cursor.execute("DELETE FROM fraud.rule_audit")
