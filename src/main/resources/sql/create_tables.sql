CREATE CACHED TABLE IF NOT EXISTS tbl_company(
  id INTEGER IDENTITY,
  company OTHER
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_inpayment(
  id Integer IDENTITY,
  number INTEGER,
  inpayment OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_invoice(
  id INTEGER IDENTITY,
  number INTEGER,
  invoice OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_creditinvoice(
  id INTEGER IDENTITY,
  number INTEGER,
  creditinvoice OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_periodicinvoice(
  id INTEGER IDENTITY,
  number INTEGER,
  periodicinvoice OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_outpayment(
  id INTEGER IDENTITY,
  number INTEGER,
  outpayment OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_supplierinvoice(
  id INTEGER IDENTITY,
  number INTEGER,
  supplierinvoice OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_purchaseorder(
  id INTEGER IDENTITY,
  number INTEGER,
  purchaseorder OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_order(
  id INTEGER IDENTITY,
  number INTEGER,
  iorder OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_tender(
  id INTEGER IDENTITY,
  number INTEGER,
  tender OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_suppliercreditinvoice(
  id INTEGER IDENTITY,
  number INTEGER,
  suppliercreditinvoice OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_vouchertemplate(
  name VARCHAR(255),
  vouchertemplate OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id),
  PRIMARY KEY(name,companyid)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_project(
  number VARCHAR(255),
  project OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id),
  PRIMARY KEY(number,companyid)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_resultunit(
  number VARCHAR(255),
  resultunit OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id),
  PRIMARY KEY(number,companyid)
  );

CREATE CACHED TABLE IF NOT EXISTS tbl_product(
  id INTEGER IDENTITY,
  number VARCHAR(255),
  product OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_customer(
  id INTEGER IDENTITY,
  number VARCHAR(255),
  customer OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_supplier(
  id INTEGER IDENTITY,
  number VARCHAR(255),
  supplier OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_autodist(
  id INTEGER IDENTITY,
  number INTEGER,
  autodist OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_indelivery(
  id INTEGER IDENTITY,
  number INTEGER,
  indelivery OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_outdelivery(
  id INTEGER IDENTITY,
  number INTEGER,
  outdelivery OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_inventory(
  id INTEGER IDENTITY,
  number INTEGER,
  inventory OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_currency(
  code VARCHAR(255) PRIMARY KEY,
  currency OTHER
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_unit(
  name VARCHAR(255) PRIMARY KEY,
  unit OTHER
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_deliveryway(
  name VARCHAR(255) PRIMARY KEY,
  deliveryway OTHER
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_deliveryterm(
  name VARCHAR(255) PRIMARY KEY,
  deliveryterm OTHER
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_paymentterm(
  name VARCHAR(255) PRIMARY KEY,
  paymentterm OTHER
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_accountplan(
  id INTEGER IDENTITY,
  accountplan OTHER
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_accountingyear(
  id INTEGER IDENTITY,
  accountingyear OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_voucher(
  id INTEGER IDENTITY,
  number INTEGER,
  voucher OTHER,
  yearid INTEGER,
  FOREIGN KEY(yearid) REFERENCES tbl_accountingyear(id)
  ) ;

CREATE CACHED TABLE IF NOT EXISTS tbl_license(
  licensekey VARCHAR(255) PRIMARY KEY
  );

CREATE CACHED TABLE IF NOT EXISTS tbl_ownreport(
  id INTEGER IDENTITY,
  ownreport OTHER,
  companyid INTEGER,
  FOREIGN KEY(companyid) REFERENCES tbl_company(id)
  ) ;
