create table idempotency_responses (
    client_key_id varchar(128) not null,
    idempotency_key varchar(128) not null,
    request_hash varchar(64) not null,
    response_status integer not null,
    response_body text not null,
    created_at timestamp with time zone not null,
    primary key (client_key_id, idempotency_key)
);

create table usage_records (
    id varchar(36) primary key,
    request_id varchar(128) not null,
    client_key_id varchar(128) not null,
    tenant_id varchar(128) not null,
    provider varchar(128) not null,
    requested_model varchar(128) not null,
    upstream_model varchar(128) not null,
    client_protocol varchar(32) not null,
    response_status integer not null,
    latency_ms bigint not null,
    input_tokens integer not null,
    output_tokens integer not null,
    idempotent_replay boolean not null,
    created_at timestamp with time zone not null
);

create index idx_usage_records_created_at on usage_records(created_at);
create index idx_usage_records_tenant on usage_records(tenant_id, created_at);

create table budget_accounts (
    tenant_id varchar(128) not null,
    client_key_id varchar(128) not null,
    period_key varchar(7) not null,
    token_limit bigint not null,
    reserved_tokens bigint not null default 0,
    consumed_tokens bigint not null default 0,
    updated_at timestamp with time zone not null,
    primary key (tenant_id, client_key_id, period_key),
    constraint ck_budget_nonnegative check (
        token_limit >= 0 and reserved_tokens >= 0 and consumed_tokens >= 0
    )
);

create table budget_reservations (
    reservation_id varchar(36) primary key,
    request_id varchar(128) not null,
    tenant_id varchar(128) not null,
    client_key_id varchar(128) not null,
    period_key varchar(7) not null,
    reserved_tokens bigint not null,
    actual_tokens bigint,
    reservation_status varchar(24) not null,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone not null,
    foreign key (tenant_id, client_key_id, period_key)
        references budget_accounts(tenant_id, client_key_id, period_key)
);

create index idx_budget_reservations_status on budget_reservations(reservation_status, updated_at);
