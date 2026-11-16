-- Cross-project cursor API filters. Without these, a filter on action or resource name with few matches walks
-- idx_global (ts, id) across the whole table. Like the other indexes they end in (ts, id), so a page stays one
-- index range scan with no sort.
ALTER TABLE audit_entry
  ADD KEY idx_global_action (action, ts, id),
  ADD KEY idx_global_secret (resource_name, ts, id);
