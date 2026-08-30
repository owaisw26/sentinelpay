create table ledger_transactions (
    id UUID primary key,
    reference varchar(256) not null,
    type varchar(256) not null,
    created_at timestamp not null
);

create table ledger_entries (
    id UUID primary key,
    ledger_transaction_id UUID not null,
    wallet_id UUID not null,
    amount numeric(19, 2) not null,
    created_at timestamp not null,

    constraint fk_ledger_transaction
        foreign key (ledger_transaction_id) references ledger_transactions(id),
    
    constraint fk_wallet_id
        foreign key (wallet_id) references wallets(id),

    constraint amount_cannot_be_zero
        check (amount != 0)
);