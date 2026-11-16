-- One row per audit event. Rows are only inserted.
-- id is assigned by the application (derived from event_id), which makes inserts idempotent.
-- Every index ends in (ts, id) so a page of the cursor API is one index range scan, with no sort.
-- utf8mb4_bin keeps matching exact and case-sensitive, like MongoDB.
CREATE TABLE audit_entry (
  id               BINARY(16)   NOT NULL,
  ts               DATETIME(6)  NOT NULL,
  event_id         VARCHAR(255) NOT NULL,
  project_id       VARCHAR(255) NOT NULL,
  resource_type    VARCHAR(100) NOT NULL,
  resource_name    VARCHAR(255) NOT NULL,
  resource_version INT          NULL,
  action           VARCHAR(64)  NOT NULL,
  user_id          VARCHAR(255) NOT NULL,
  user_type        VARCHAR(16)  NOT NULL,
  actor_id         VARCHAR(255) NULL,
  ips              JSON         NULL,
  metadata         JSON         NULL,
  created_at       DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id, ts),
  KEY idx_project   (project_id, ts, id),
  KEY idx_secret    (project_id, resource_name, ts, id),
  KEY idx_action    (project_id, action, ts, id),
  KEY idx_user_type (project_id, user_type, ts, id),
  KEY idx_global    (ts, id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_bin;
