ALTER TABLE subscription ADD COLUMN valid_from TIMESTAMPTZ;
ALTER TABLE subscription ADD COLUMN valid_until TIMESTAMPTZ;
ALTER TABLE subscription ADD COLUMN auto_renew BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE subscription ADD COLUMN provider TEXT;
ALTER TABLE subscription ADD COLUMN amount BIGINT;
ALTER TABLE subscription ADD COLUMN currency TEXT;
ALTER TABLE subscription ADD COLUMN period_days INTEGER;
ALTER TABLE subscription ADD CONSTRAINT subscription_period CHECK (valid_until IS NULL OR valid_until > valid_from);
CREATE TABLE billing_checkout (
    id TEXT PRIMARY KEY, student_id TEXT NOT NULL REFERENCES student(id),
    subscription_id TEXT NOT NULL, request_id TEXT, data_epoch BIGINT NOT NULL,
    provider TEXT NOT NULL, amount BIGINT NOT NULL CHECK(amount>0), currency TEXT NOT NULL,
    period_days INTEGER NOT NULL CHECK(period_days BETWEEN 1 AND 366),
    status TEXT NOT NULL CHECK(status IN ('pending','succeeded','failed')),
    created_at TIMESTAMPTZ NOT NULL, expires_at TIMESTAMPTZ NOT NULL,
    delivery_status TEXT NOT NULL DEFAULT 'pending',
    FOREIGN KEY(student_id,subscription_id) REFERENCES subscription(student_id,id)
);
CREATE INDEX billing_checkout_student ON billing_checkout(student_id,created_at);
CREATE TABLE billing_event (
    provider TEXT NOT NULL, event_id TEXT NOT NULL, charge_id TEXT NOT NULL,
    student_id TEXT NOT NULL REFERENCES student(id), checkout_id TEXT NOT NULL REFERENCES billing_checkout(id),
    input_digest TEXT NOT NULL, outcome TEXT NOT NULL, received_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY(provider,event_id), UNIQUE(provider,charge_id)
);
ALTER TABLE lesson ADD COLUMN admission_entitlement_id TEXT;
