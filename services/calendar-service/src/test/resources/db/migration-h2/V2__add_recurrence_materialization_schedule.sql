ALTER TABLE calendar_event
    ADD COLUMN recurrence_next_materialization_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX calendar_event_recurrence_due_idx
    ON calendar_event (status, recurrence_next_materialization_at, id);
