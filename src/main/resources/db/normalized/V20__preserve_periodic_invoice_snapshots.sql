-- No backfill: older periodic projections cannot prove missing snapshot values.
-- A legacy migration must write an explicit marker for every template and generated instance.
CREATE TABLE periodic_invoice_snapshot (
  periodic_invoice_id BIGINT NOT NULL,
  invoice_index INTEGER NOT NULL,
  present BOOLEAN NOT NULL,
  PRIMARY KEY(periodic_invoice_id,invoice_index),
  CONSTRAINT fk_periodic_snapshot_parent FOREIGN KEY(periodic_invoice_id) REFERENCES periodic_invoice(id),
  CONSTRAINT ck_periodic_snapshot_index CHECK(invoice_index >= -1),
  CONSTRAINT ck_periodic_snapshot_presence CHECK(present OR invoice_index = -1)
);
CREATE TABLE periodic_invoice_address (
  periodic_invoice_id BIGINT NOT NULL, invoice_index INTEGER NOT NULL,
  address_type VARCHAR(16) NOT NULL,
  name VARCHAR(255), address_line_1 VARCHAR(1024), address_line_2 VARCHAR(1024),
  postal_code VARCHAR(64), city VARCHAR(255), country VARCHAR(255),
  PRIMARY KEY(periodic_invoice_id,invoice_index,address_type),
  CONSTRAINT fk_periodic_address_snapshot FOREIGN KEY(periodic_invoice_id,invoice_index)
    REFERENCES periodic_invoice_snapshot(periodic_invoice_id,invoice_index),
  CONSTRAINT ck_periodic_address_type CHECK(address_type IN ('invoice','delivery'))
);
CREATE TABLE periodic_invoice_default_account (
  periodic_invoice_id BIGINT NOT NULL, invoice_index INTEGER NOT NULL,
  account_type VARCHAR(64) NOT NULL, account_number INTEGER,
  PRIMARY KEY(periodic_invoice_id,invoice_index,account_type),
  CONSTRAINT fk_periodic_account_snapshot FOREIGN KEY(periodic_invoice_id,invoice_index)
    REFERENCES periodic_invoice_snapshot(periodic_invoice_id,invoice_index)
);
CREATE TABLE periodic_invoice_voucher (
  periodic_invoice_id BIGINT NOT NULL, invoice_index INTEGER NOT NULL,
  number INTEGER NOT NULL, voucher_date DATE, description VARCHAR(4096),
  PRIMARY KEY(periodic_invoice_id,invoice_index),
  CONSTRAINT fk_periodic_voucher_snapshot FOREIGN KEY(periodic_invoice_id,invoice_index)
    REFERENCES periodic_invoice_snapshot(periodic_invoice_id,invoice_index)
);
CREATE TABLE periodic_invoice_voucher_row (
  periodic_invoice_id BIGINT NOT NULL, invoice_index INTEGER NOT NULL, row_number INTEGER NOT NULL,
  account_number INTEGER, project_number VARCHAR(255), result_unit_number VARCHAR(255),
  debit DECIMAL(100,30), credit DECIMAL(100,30),
  edited_at TIMESTAMP(9) WITH TIME ZONE, edited_signature VARCHAR(255),
  crossed BOOLEAN NOT NULL, added BOOLEAN NOT NULL,
  PRIMARY KEY(periodic_invoice_id,invoice_index,row_number),
  CONSTRAINT fk_periodic_voucher_row_snapshot FOREIGN KEY(periodic_invoice_id,invoice_index)
    REFERENCES periodic_invoice_voucher(periodic_invoice_id,invoice_index)
);
CREATE TABLE periodic_invoice_added_state (
  periodic_invoice_id BIGINT NOT NULL, row_number INTEGER NOT NULL,
  invoice_number INTEGER, added BOOLEAN,
  PRIMARY KEY(periodic_invoice_id,row_number),
  CONSTRAINT fk_periodic_added_parent FOREIGN KEY(periodic_invoice_id) REFERENCES periodic_invoice(id)
);
