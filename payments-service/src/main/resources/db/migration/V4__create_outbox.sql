create table outbox_events(
    id UUID primary key,
    aggregate_id UUID not null,
    event_type varchar not null,
    payload JSONB not null,
    correlation_id UUID not null,
    created_at timestamp not null default current_timestamp,
    published_at timestamp
);
