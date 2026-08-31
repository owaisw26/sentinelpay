create table payments(
    id UUID primary key,
    sender_wallet_id UUID not null,
    receiver_wallet_id UUID not null, 
    amount numeric(19,2) not null,
    currency varchar(3) not null,
    reference varchar not null, 
    status varchar not null,
    idempotency_key UUID unique not null,
    request_hash varchar not null, 
    version int not null,
    created_at timestamp not null,
    updated_at timestamp not null
)