-- Additions to the brief's schema (no brief column changes) that make balance updates idempotent and resumable.
-- A balance update is an interest run: deposits are processed in batches, each batch stamps its deposits with the
-- run id, and the run is completed once every deposit carries it. A run interrupted by a crash stays open and is
-- resumed by the next update instead of paying the month twice.

create table interest_runs
(
    id           integer generated always as identity primary key,
    started_at   timestamptz not null default now(),
    completed_at timestamptz
);

-- At most one open run at a time: concurrent updates join the open run instead of starting a second month.
create unique index interest_runs_one_open on interest_runs ((completed_at is null)) where completed_at is null;

alter table time_deposits
    add column last_interest_run_id integer references interest_runs (id);
