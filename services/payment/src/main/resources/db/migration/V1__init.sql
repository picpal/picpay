-- ========================
-- payment 스키마
-- ========================
CREATE SCHEMA IF NOT EXISTS payment;

CREATE TABLE payment.payments (
    id          BIGSERIAL PRIMARY KEY,
    tid         VARCHAR(64)  NOT NULL UNIQUE,
    merchant_id VARCHAR(64)  NOT NULL,
    order_id    VARCHAR(64)  NOT NULL,
    token_id    VARCHAR(64)  NOT NULL,
    amount      BIGINT       NOT NULL,
    method      VARCHAR(20)  NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'READY',
    pg_tid      VARCHAR(64),
    idempotency_key VARCHAR(64) UNIQUE,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE payment.partial_cancellations (
    id               BIGSERIAL PRIMARY KEY,
    payment_id       BIGINT       NOT NULL REFERENCES payment.payments(id),
    cancel_tid       VARCHAR(64)  NOT NULL UNIQUE,
    cancel_amount    BIGINT       NOT NULL,
    remaining_amount BIGINT       NOT NULL,
    reason           VARCHAR(500),
    pg_cancel_tid    VARCHAR(64),
    status           VARCHAR(20)  NOT NULL,
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_partial_cancel_payment ON payment.partial_cancellations(payment_id);

CREATE TABLE payment.outbox_events (
    id             BIGSERIAL PRIMARY KEY,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(64)  NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    topic          VARCHAR(100) NOT NULL,
    payload        JSONB        NOT NULL,
    status         VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    retry_count    INT          NOT NULL DEFAULT 0,
    max_retry      INT          NOT NULL DEFAULT 5,
    last_error     VARCHAR(500),
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    published_at   TIMESTAMP
);
CREATE INDEX idx_outbox_pending ON payment.outbox_events(status, created_at)
    WHERE status IN ('PENDING', 'FAILED');

-- ========================
-- token 스키마
-- ========================
CREATE SCHEMA IF NOT EXISTS token;

CREATE TABLE token.card_tokens (
    id                      BIGSERIAL PRIMARY KEY,
    token_id                VARCHAR(64)  NOT NULL UNIQUE,
    merchant_id             VARCHAR(64)  NOT NULL,
    card_number_enc         TEXT         NOT NULL,
    card_expiry_enc         TEXT         NOT NULL,
    card_last_four          VARCHAR(4)   NOT NULL,
    card_number_deleted_at  TIMESTAMP,
    status                  VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at              TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE token.easy_pay_methods (
    id          BIGSERIAL PRIMARY KEY,
    method_id   VARCHAR(64)  NOT NULL UNIQUE,
    user_id     VARCHAR(64)  NOT NULL,
    token_id    VARCHAR(64)  NOT NULL,
    method_name VARCHAR(100),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- ========================
-- billing 스키마
-- ========================
CREATE SCHEMA IF NOT EXISTS billing;

CREATE TABLE billing.billing_plans (
    id              BIGSERIAL PRIMARY KEY,
    plan_id         VARCHAR(64)  NOT NULL UNIQUE,
    merchant_id     VARCHAR(64)  NOT NULL,
    token_id        VARCHAR(64)  NOT NULL,
    amount          BIGINT       NOT NULL,
    cycle           VARCHAR(20)  NOT NULL,
    next_billing_at TIMESTAMP    NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    retry_count     INT          NOT NULL DEFAULT 0,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE TABLE billing.billing_histories (
    id             BIGSERIAL PRIMARY KEY,
    plan_id        VARCHAR(64)  NOT NULL,
    tid            VARCHAR(64),
    amount         BIGINT       NOT NULL,
    status         VARCHAR(20)  NOT NULL,
    failure_reason VARCHAR(500),
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_billing_history_plan ON billing.billing_histories(plan_id);

CREATE TABLE billing.billing_retry_jobs (
    id            BIGSERIAL PRIMARY KEY,
    plan_id       VARCHAR(64)  NOT NULL,
    retry_count   INT          NOT NULL DEFAULT 0,
    max_retry     INT          NOT NULL DEFAULT 3,
    next_retry_at TIMESTAMP    NOT NULL,
    last_error    VARCHAR(500),
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_retry_pending ON billing.billing_retry_jobs(status, next_retry_at)
    WHERE status = 'PENDING';

-- ========================
-- merchant 스키마
-- ========================
CREATE SCHEMA IF NOT EXISTS merchant;

CREATE TABLE merchant.merchants (
    id            BIGSERIAL PRIMARY KEY,
    merchant_id   VARCHAR(64)  NOT NULL UNIQUE,
    merchant_name VARCHAR(200) NOT NULL,
    api_key       VARCHAR(128) NOT NULL UNIQUE,
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP    NOT NULL DEFAULT NOW()
);

INSERT INTO merchant.merchants (merchant_id, merchant_name, api_key)
VALUES ('mer_001', 'Test Merchant', 'test-api-key-001');

-- ========================
-- notification 스키마
-- ========================
CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE notification.processed_events (
    event_id     VARCHAR(64) PRIMARY KEY,
    topic        VARCHAR(100) NOT NULL,
    processed_at TIMESTAMP    NOT NULL DEFAULT NOW()
);
