DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fraud_app') THEN
        CREATE ROLE fraud_app NOLOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'fraud_runtime') THEN
        CREATE ROLE fraud_runtime LOGIN PASSWORD 'fraud_local_only';
    END IF;
END
$$;

GRANT fraud_app TO fraud_runtime;
