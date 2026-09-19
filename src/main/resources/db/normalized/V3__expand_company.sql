-- Explicit company fields and child values formerly embedded in SSNewCompany.
ALTER TABLE company ADD COLUMN phone VARCHAR(255);
ALTER TABLE company ADD COLUMN phone_2 VARCHAR(255);
ALTER TABLE company ADD COLUMN telefax VARCHAR(255);
ALTER TABLE company ADD COLUMN residence VARCHAR(255);
ALTER TABLE company ADD COLUMN web_address VARCHAR(4096);
ALTER TABLE company ADD COLUMN smtp_address VARCHAR(4096);
ALTER TABLE company ADD COLUMN email VARCHAR(1024);
ALTER TABLE company ADD COLUMN contact_person VARCHAR(255);
ALTER TABLE company ADD COLUMN tax_registered BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE company ADD COLUMN logotype_path VARCHAR(4096);
ALTER TABLE company ADD COLUMN bank VARCHAR(255);
ALTER TABLE company ADD COLUMN bank_account_number VARCHAR(255);
ALTER TABLE company ADD COLUMN plus_account_number VARCHAR(255);
ALTER TABLE company ADD COLUMN iban VARCHAR(255);
ALTER TABLE company ADD COLUMN swift_bic VARCHAR(255);
ALTER TABLE company ADD COLUMN delay_interest DECIMAL(100, 30);
ALTER TABLE company ADD COLUMN reminder_fee DECIMAL(100, 30);
ALTER TABLE company ADD COLUMN estimated_delivery VARCHAR(255);
ALTER TABLE company ADD COLUMN tax_rate_1 DECIMAL(100, 30);
ALTER TABLE company ADD COLUMN tax_rate_2 DECIMAL(100, 30);
ALTER TABLE company ADD COLUMN tax_rate_3 DECIMAL(100, 30);
ALTER TABLE company ADD COLUMN weight_unit VARCHAR(255);
ALTER TABLE company ADD COLUMN volume_unit VARCHAR(255);
ALTER TABLE company ADD COLUMN standard_unit_name VARCHAR(255);
ALTER TABLE company ADD COLUMN payment_term_name VARCHAR(255);
ALTER TABLE company ADD COLUMN delivery_term_name VARCHAR(255);
ALTER TABLE company ADD COLUMN delivery_way_name VARCHAR(255);
ALTER TABLE company ADD COLUMN rounding_off BOOLEAN DEFAULT FALSE NOT NULL;
ALTER TABLE company ADD COLUMN vat_period INTEGER;
ALTER TABLE company ADD COLUMN mail_name VARCHAR(255);
ALTER TABLE company ADD COLUMN mail_host VARCHAR(1024);
ALTER TABLE company ADD COLUMN mail_port INTEGER;
ALTER TABLE company ADD COLUMN mail_bcc VARCHAR(4096);
ALTER TABLE company ADD COLUMN mail_auth BOOLEAN;
ALTER TABLE company ADD COLUMN mail_security VARCHAR(32);
ALTER TABLE company ADD COLUMN mail_username VARCHAR(1024);
ALTER TABLE company ADD COLUMN mail_password VARCHAR(4096);
ALTER TABLE company ADD CONSTRAINT fk_company_standard_unit
  FOREIGN KEY (standard_unit_name) REFERENCES unit_definition(name);
ALTER TABLE company ADD CONSTRAINT fk_company_payment_term
  FOREIGN KEY (payment_term_name) REFERENCES payment_term(name);
ALTER TABLE company ADD CONSTRAINT fk_company_delivery_term
  FOREIGN KEY (delivery_term_name) REFERENCES delivery_term(name);
ALTER TABLE company ADD CONSTRAINT fk_company_delivery_way
  FOREIGN KEY (delivery_way_name) REFERENCES delivery_way(name);
CREATE TABLE company_address (
  company_id BIGINT NOT NULL,
  address_type VARCHAR(16) NOT NULL,
  name VARCHAR(255),
  address_line_1 VARCHAR(1024),
  address_line_2 VARCHAR(1024),
  postal_code VARCHAR(64),
  city VARCHAR(255),
  country VARCHAR(255),
  PRIMARY KEY (company_id, address_type),
  CONSTRAINT fk_address_company FOREIGN KEY (company_id) REFERENCES company(id),
  CONSTRAINT ck_address_type CHECK (address_type IN ('postal', 'delivery'))
);

CREATE TABLE company_standard_text (
  company_id BIGINT NOT NULL,
  text_type VARCHAR(64) NOT NULL,
  text_value VARCHAR(16384),
  PRIMARY KEY (company_id, text_type),
  CONSTRAINT fk_standard_text_company FOREIGN KEY (company_id) REFERENCES company(id)
);

CREATE TABLE company_default_account (
  company_id BIGINT NOT NULL,
  account_type VARCHAR(64) NOT NULL,
  account_number INTEGER,
  PRIMARY KEY (company_id, account_type),
  CONSTRAINT fk_default_account_company FOREIGN KEY (company_id) REFERENCES company(id)
);

CREATE TABLE company_auto_increment (
  company_id BIGINT NOT NULL,
  counter_name VARCHAR(255) NOT NULL,
  counter_value INTEGER NOT NULL,
  PRIMARY KEY (company_id, counter_name),
  CONSTRAINT fk_auto_increment_company FOREIGN KEY (company_id) REFERENCES company(id)
);
