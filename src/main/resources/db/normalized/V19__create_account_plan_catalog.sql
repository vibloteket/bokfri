-- Global reusable templates, distinct from each accounting year's copied account plan.
CREATE TABLE account_plan_catalog (
  legacy_id INTEGER PRIMARY KEY,
  name VARCHAR(255),
  base_name VARCHAR(255),
  assessment_year VARCHAR(32),
  plan_type VARCHAR(255)
);

CREATE TABLE account_plan_catalog_account (
  plan_legacy_id INTEGER NOT NULL REFERENCES account_plan_catalog(legacy_id),
  row_number INTEGER NOT NULL CHECK (row_number >= 0),
  number INTEGER NOT NULL CHECK (number > 0),
  description VARCHAR(1024),
  sru_code VARCHAR(64),
  vat_code VARCHAR(64),
  report_code VARCHAR(64),
  active BOOLEAN NOT NULL,
  project_required BOOLEAN NOT NULL,
  result_unit_required BOOLEAN NOT NULL,
  PRIMARY KEY (plan_legacy_id, row_number),
  UNIQUE (plan_legacy_id, number)
);
