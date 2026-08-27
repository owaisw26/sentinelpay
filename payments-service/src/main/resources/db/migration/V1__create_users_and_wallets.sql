create table users (
    user_id UUID primary key,
    name varchar not null,
    role varchar(20) not null,
    created_at timestamp not null,

    constraint chk_role 
        check (role in ('CUSTOMER', 'ANALYST'))
);

create table wallets (
    id UUID primary key,
    user_id UUID not null,
    currency varchar(3) not null,
    balance Numeric(19, 2) not null default 0.0,
    created_at timestamp not null,

    constraint fk_walletToUser
        foreign key (user_id) 
        references users(user_id),

    constraint unique_wallet_user_currency
        unique(user_id, currency),
    
    constraint balance_greater_than_zero
        check (balance >= 0)
);