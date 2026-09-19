-- Company-scoped accounting dimensions referenced by voucher and document rows.
CREATE TABLE project (
  company_id BIGINT NOT NULL,
  number VARCHAR(255) NOT NULL,
  name VARCHAR(255),
  description VARCHAR(4096),
  concluded BOOLEAN NOT NULL,
  concluded_on DATE,
  PRIMARY KEY (company_id, number),
  CONSTRAINT fk_project_company FOREIGN KEY (company_id) REFERENCES company(id)
);

CREATE TABLE result_unit (
  company_id BIGINT NOT NULL,
  number VARCHAR(255) NOT NULL,
  name VARCHAR(255),
  description VARCHAR(4096),
  PRIMARY KEY (company_id, number),
  CONSTRAINT fk_result_unit_company FOREIGN KEY (company_id) REFERENCES company(id)
);
