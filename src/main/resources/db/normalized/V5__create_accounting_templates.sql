-- Company-scoped voucher templates and automatic distributions with ordered rows.
CREATE TABLE voucher_template (
  company_id BIGINT NOT NULL,
  name VARCHAR(255) NOT NULL,
  description VARCHAR(4096),
  modified_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (company_id, name),
  CONSTRAINT fk_template_company FOREIGN KEY (company_id) REFERENCES company(id)
);

CREATE TABLE voucher_template_row (
  company_id BIGINT NOT NULL,
  template_name VARCHAR(255) NOT NULL,
  row_number INTEGER NOT NULL,
  account_number INTEGER,
  debit BOOLEAN,
  PRIMARY KEY (company_id, template_name, row_number),
  CONSTRAINT fk_template_row_parent FOREIGN KEY (company_id, template_name)
    REFERENCES voucher_template(company_id, name),
  CONSTRAINT ck_template_row_number CHECK (row_number >= 0)
);

CREATE TABLE auto_distribution (
  company_id BIGINT NOT NULL,
  number INTEGER NOT NULL,
  description VARCHAR(4096),
  amount DECIMAL(100, 30),
  PRIMARY KEY (company_id, number),
  CONSTRAINT fk_auto_distribution_company FOREIGN KEY (company_id) REFERENCES company(id)
);

CREATE TABLE auto_distribution_row (
  company_id BIGINT NOT NULL,
  distribution_number INTEGER NOT NULL,
  row_number INTEGER NOT NULL,
  account_number INTEGER,
  description VARCHAR(4096),
  percentage DECIMAL(100, 30),
  debit DECIMAL(100, 30),
  credit DECIMAL(100, 30),
  project_number VARCHAR(255),
  result_unit_number VARCHAR(255),
  PRIMARY KEY (company_id, distribution_number, row_number),
  CONSTRAINT fk_distribution_row_parent FOREIGN KEY (company_id, distribution_number)
    REFERENCES auto_distribution(company_id, number),
  CONSTRAINT fk_distribution_project FOREIGN KEY (company_id, project_number)
    REFERENCES project(company_id, number),
  CONSTRAINT fk_distribution_result_unit FOREIGN KEY (company_id, result_unit_number)
    REFERENCES result_unit(company_id, number),
  CONSTRAINT ck_distribution_row_number CHECK (row_number >= 0)
);
