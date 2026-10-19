-- Application tables for the recipes. Spring Boot runs this file on startup for the embedded database (H2).
-- Engine tables (xs_*) are created by the engine.

-- Recipe transactionalstart
create table if not exists purchase_order
(
    id     varchar(64) primary key,
    item   varchar(255) not null,
    status varchar(32)  not null
);
