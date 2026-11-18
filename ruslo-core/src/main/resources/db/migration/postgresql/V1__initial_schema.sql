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
    timers text,
    messages text,
    correlation_id varchar,
    attempt int not null default 0,
    last_error varchar(1024),
    next_run_at timestamp with time zone,
    every_due_at timestamp with time zone,
    after_due_at timestamp with time zone,
    lease_owner uuid,
    row_version bigint not null default 0
);

create index xs_process_instance_lease_owner_idx
    on xs_process_instance (lease_owner)
    where lease_owner is not null;

create index xs_process_instance_business_key_idx
    on xs_process_instance (business_key);

create table xs_process_state_hist (
    id uuid not null primary key,
    link_process_instance uuid not null,
    state varchar not null,
    changed_at timestamp with time zone not null,
    constraint fk_process_state_hist2process
        foreign key (link_process_instance) references xs_process_instance (id) on delete cascade
);

create index xs_process_state_hist_link_process_instance_idx
    on xs_process_state_hist (link_process_instance);

create index xs_process_instance_worker_id_next_run_at_id_idx
    on xs_process_instance (worker_id, next_run_at, id)
    where status = 'ACTIVE' and lease_owner is null and next_run_at is not null;

create index xs_process_instance_status_idx
    on xs_process_instance (status) where status <> 'COMPLETED';
