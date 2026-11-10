--liquibase formatted sql

--changeset eventsourcing:5
CREATE TABLE event_sourcing.subscription_leases
(
    subscription_id VARCHAR(200)             NOT NULL PRIMARY KEY,
    owner_id        VARCHAR(100)             NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL
);

--rollback DROP TABLE IF EXISTS event_sourcing.subscription_leases;
