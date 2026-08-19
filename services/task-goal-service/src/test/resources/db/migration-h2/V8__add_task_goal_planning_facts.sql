alter table task add column if not exists priority integer default 3 not null;
alter table task add column if not exists due_at timestamp with time zone;
alter table if exists task_command_idempotency add column if not exists result_priority integer;
alter table if exists task_command_idempotency add column if not exists result_due_at timestamp with time zone;
alter table task add constraint ck_task_priority check (priority between 0 and 4);
alter table goal add constraint ck_goal_priority check (priority between 0 and 4);
