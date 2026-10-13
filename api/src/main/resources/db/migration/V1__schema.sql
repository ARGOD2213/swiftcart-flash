-- SwiftCart Flash schema.
--
-- During a drop, Redis is the source of truth for stock (atomic Lua decrements). Postgres is the order log:
-- every reservation that became an order is here, so Redis can be rebuilt from it at any time
-- (InventoryRebuilder) and every unit sold can be audited (AuditService).

create table customers (
    id             uuid primary key,
    email          text not null,
    password_hash  text not null,
    name           text not null,
    role           text not null default 'CUSTOMER' check (role in ('CUSTOMER', 'ADMIN')),
    created_at     timestamptz not null default now()
);
-- emails are unique regardless of case: Asha@x.com and asha@x.com are one account
create unique index customers_email_uk on customers (lower(email));

create table drops (
    id                       uuid primary key,
    slug                     text not null unique check (slug ~ '^[a-z0-9][a-z0-9-]{2,59}$'),
    name                     text not null,
    description              text not null default '',
    image_url                text,
    queue_opens_at           timestamptz not null,
    opens_at                 timestamptz not null,
    closes_at                timestamptz not null,
    per_customer_limit       integer not null check (per_customer_limit between 1 and 10),
    admit_per_second         integer not null check (admit_per_second between 1 and 100000),
    reservation_ttl_seconds  integer not null default 600 check (reservation_ttl_seconds between 30 and 3600),
    expected_peak_rps        integer not null default 500 check (expected_peak_rps > 0),
    -- DRAFT: editable. ARMED: stock loaded into Redis, prices frozen. SETTLED: closed and audited.
    status                   text not null default 'DRAFT' check (status in ('DRAFT', 'ARMED', 'SETTLED')),
    created_at               timestamptz not null default now(),
    constraint drop_times check (queue_opens_at <= opens_at and opens_at < closes_at)
);

create table skus (
    id             uuid primary key,
    drop_id        uuid not null references drops (id),
    code           text not null check (code ~ '^[A-Z0-9][A-Z0-9-]{0,39}$'),
    name           text not null,
    price_minor    bigint not null check (price_minor >= 100),
    currency       char(3) not null default 'INR',
    initial_stock  integer not null check (initial_stock >= 0),
    unique (drop_id, code)
);

create table orders (
    id                  uuid primary key,
    drop_id             uuid not null references drops (id),
    sku_id              uuid not null references skus (id),
    customer_id         uuid not null references customers (id),
    quantity            integer not null check (quantity > 0),
    unit_price_minor    bigint not null,
    amount_minor        bigint not null check (amount_minor > 0),
    currency            char(3) not null,
    phone               text not null,
    shipping_address    jsonb not null,
    -- PENDING_PAYMENT holds stock until reserved_until. PAID and FULFILLED hold it for good.
    -- REVIEW (amount mismatch) holds it until a human decides. EXPIRED and CANCELLED hold nothing.
    status              text not null check (status in ('PENDING_PAYMENT', 'PAID', 'FULFILLED', 'EXPIRED', 'CANCELLED', 'REVIEW')),
    reservation_id      uuid not null unique,
    reserved_until      timestamptz not null,
    idempotency_key     text not null,
    gateway             text not null,
    gateway_order_id    text unique,
    checkout_url        text,
    paid_payment_id     text unique,
    paid_at             timestamptz,
    last_payment_error  text,
    review_reason       text,
    warehouse_ref       text,
    fulfilled_at        timestamptz,
    -- when the reconciler last asked the gateway about this order (missed or late webhooks)
    reconciled_at       timestamptz,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now(),
    unique (customer_id, idempotency_key)
);
create index orders_expiring_idx on orders (reserved_until) where status = 'PENDING_PAYMENT';
create index orders_drop_status_idx on orders (drop_id, status);
create index orders_customer_idx on orders (customer_id, created_at desc);

-- Every payment the gateway reports, matched to an order or not. "Charged exactly once" is checkable here:
-- an order never has more than one CAPTURED payment that isn't being refunded.
create table payments (
    gateway_payment_id  text primary key,
    gateway             text not null,
    order_id            uuid references orders (id),
    gateway_order_id    text,
    amount_minor        bigint not null,
    currency            char(3) not null,
    status              text not null check (status in ('AUTHORIZED', 'CAPTURED', 'FAILED', 'REFUND_PENDING', 'REFUNDED')),
    refund_reason       text,
    refund_id           text,
    created_at          timestamptz not null default now(),
    updated_at          timestamptz not null default now()
);
create index payments_order_idx on payments (order_id);

-- Stripe-style idempotency: one row per (customer, key). The stored response is replayed for retries.
create table idempotency_keys (
    customer_id      uuid not null references customers (id),
    key              text not null,
    request_hash     text not null,
    status           text not null check (status in ('IN_PROGRESS', 'COMPLETED')),
    response_status  integer,
    response_body    text,
    created_at       timestamptz not null default now(),
    completed_at     timestamptz,
    primary key (customer_id, key)
);
create index idempotency_keys_created_idx on idempotency_keys (created_at);

-- Gateways retry and re-deliver webhooks; each event is handled once.
create table processed_webhooks (
    gateway      text not null,
    event_id     text not null,
    event_type   text not null,
    received_at  timestamptz not null default now(),
    primary key (gateway, event_id)
);

-- Transactional outbox: events are written in the same transaction as the order change,
-- then relayed to SQS FIFO (group = order id) by OutboxRelay.
create table outbox (
    id            bigserial primary key,
    event_id      uuid not null unique,
    -- FIFO message group: the order id, or the payment id for a payment with no order
    group_key     text not null,
    type          text not null,
    payload       text not null,
    created_at    timestamptz not null default now(),
    published_at  timestamptz,
    attempts      integer not null default 0,
    last_error    text,
    next_attempt_at timestamptz not null default now()
);
create index outbox_unpublished_idx on outbox (id) where published_at is null;

-- Consumer side of at-least-once delivery.
create table processed_messages (
    event_id      uuid primary key,
    processed_at  timestamptz not null default now()
);

-- Gapless invoice numbers per financial year: SC/2026-27/000001
create table invoice_counters (
    financial_year  text primary key,
    next_number     integer not null
);

create table invoices (
    id              uuid primary key,
    order_id        uuid not null unique references orders (id),
    number          text not null unique,
    amount_minor    bigint not null,
    currency        char(3) not null,
    issued_at       timestamptz not null default now()
);

create table notifications (
    order_id     uuid not null references orders (id),
    kind         text not null,
    recipient    text not null,
    provider_id  text,
    sent_at      timestamptz not null default now(),
    primary key (order_id, kind)
);
