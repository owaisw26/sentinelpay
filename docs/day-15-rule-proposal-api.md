# Day 15 analyst risk-rule API

All routes require an authenticated `ANALYST`. The Java service forwards the
analyst subject to the private fraud service over an authenticated internal
channel.

- `POST /analyst/risk/rule-proposals` generates and persists a draft using the
  configured contract stub or OpenAI provider.
- `GET /analyst/risk/rule-proposals/{proposalId}` returns provenance, validation
  failures, the candidate DSL, and its held-out impact report.
- `POST /analyst/risk/rule-proposals/{proposalId}/approve` accepts
  `expectedVersion` and `reason`; it creates and atomically activates a new
  immutable ruleset version.
- `POST /analyst/risk/rule-proposals/{proposalId}/reject` accepts
  `expectedVersion` and `reason`.
- `POST /analyst/risk/rules/{ruleId}/deactivate` accepts
  `expectedRulesetVersion` and `reason`; it creates a new ruleset without the
  rule.
- `POST /analyst/risk/rulesets/{targetVersion}/rollback` accepts
  `expectedRulesetVersion` and `reason`; it copies the target contents into a
  new active version.
- `GET /analyst/risk/rulesets/active` returns the current active dynamic
  ruleset.

Version mismatches return RFC 7807 `409 RULE_VERSION_CONFLICT`. Draft and failed
proposals are never loaded by the risk evaluator.

## Local providers

Normal development and CI use `RULE_PROPOSAL_PROVIDER=stub`. To exercise the
live acceptance path, configure `OPENAI_API_KEY`, set
`RULE_PROPOSAL_PROVIDER=openai`, and retain the pinned default
`OPENAI_RULE_MODEL=gpt-5.4-mini-2026-03-17`. Never place a real key in a tracked
file.

The opt-in billable contract test is:

```bash
cd fraud-service
OPENAI_API_KEY=... .venv/bin/pytest -q \
  tests/test_rule_proposals.py::test_live_openai_call_produces_a_valid_draft
```
