-- Shared lookup data referenced by companies and later register/document domains.
CREATE TABLE currency (
  code VARCHAR(255) PRIMARY KEY,
  description VARCHAR(1024),
  exchange_rate DECIMAL(100, 30)
);

CREATE TABLE unit_definition (
  name VARCHAR(255) PRIMARY KEY,
  description VARCHAR(1024)
);

CREATE TABLE payment_term (
  name VARCHAR(255) PRIMARY KEY,
  description VARCHAR(1024)
);

CREATE TABLE delivery_term (
  name VARCHAR(255) PRIMARY KEY,
  description VARCHAR(1024)
);

CREATE TABLE delivery_way (
  name VARCHAR(255) PRIMARY KEY,
  description VARCHAR(1024)
);

ALTER TABLE company ADD CONSTRAINT fk_company_currency
  FOREIGN KEY (currency_code) REFERENCES currency(code);
