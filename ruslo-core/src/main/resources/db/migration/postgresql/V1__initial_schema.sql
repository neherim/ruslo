create table xs_application_instance (
    id uuid not null primary key,
    expires_at timestamp with time zone not null
);

create table xs_process_instance (
    id uuid not null primary key,
    created_at timestamp with time zone not null,
    updated_at timestamp with time zone,
    business_key varchar,
    state varchar not null,
    worker_id varchar,
    status varchar not null default 'ACTIVE',
    type varchar not null,
    variables text,
    messages text,
    correlation_id varchar,
    attempt int not null default 0,
    last_error varchar(1024),
    next_run_at timestamp with time zone,
    every_due_at timestamp with time zone,
    after_due_at timestamp with time zone,
    at_fired_due_at timestamp with time zone,
    lease_owner uuid,
    row_version bigint not null default 0
);

create index i_process_instance$lease_owner
    on xs_process_instance (lease_owner)
    where lease_owner is not null;

create index i_process_instance$business_key
    on xs_process_instance (business_key);

create table xs_process_state_hist (
    id uuid not null primary key,
    link_process_instance uuid not null,
    state varchar not null,
    changed_at timestamp with time zone not null,
    constraint fk_process_state_hist2process
        foreign key (link_process_instance) references xs_process_instance (id) on delete cascade
);

create index i_process_state_hist$link_process_instance
    on xs_process_state_hist (link_process_instance);

create index i_process_instance$worker_id$next_run_at$id
    on xs_process_instance (worker_id, next_run_at, id)
    where status = 'ACTIVE' and lease_owner is null and next_run_at is not null;

create index i_process_instance$status
    on xs_process_instance (status) where status <> 'COMPLETED';
