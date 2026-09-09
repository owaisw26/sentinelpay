ALTER TABLE fraud.risk_decisions
    ADD COLUMN IF NOT EXISTS deterministic_score INTEGER,
    ADD COLUMN IF NOT EXISTS anomaly_score DOUBLE PRECISION,
    ADD COLUMN IF NOT EXISTS anomaly_contribution INTEGER;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_fraud_decision_deterministic_score'
          AND conrelid = 'fraud.risk_decisions'::regclass
    ) THEN
        ALTER TABLE fraud.risk_decisions
            ADD CONSTRAINT chk_fraud_decision_deterministic_score
            CHECK (deterministic_score IS NULL OR deterministic_score >= 0);
    END IF;
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_fraud_decision_anomaly_contribution'
          AND conrelid = 'fraud.risk_decisions'::regclass
    ) THEN
        ALTER TABLE fraud.risk_decisions
            ADD CONSTRAINT chk_fraud_decision_anomaly_contribution
            CHECK (
                anomaly_contribution IS NULL
                OR anomaly_contribution BETWEEN 0 AND 30
            );
    END IF;
END
$$;
