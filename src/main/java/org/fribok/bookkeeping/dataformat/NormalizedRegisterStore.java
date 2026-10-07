package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSProductRow;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSDeliveryTerm;
import se.swedsoft.bookkeeping.data.common.SSDeliveryWay;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.common.SSUnit;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * SSDB-shaped register reads/writes over normalized customer, supplier, and product tables,
 * including their address, description, default-account, and component child rows.
 */
public final class NormalizedRegisterStore {
    private final Connection connection;

    public NormalizedRegisterStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    // ------------------------------------------------------------------ customers

    public List<SSCustomer> getCustomers(int companyLegacyId) throws SQLException {
        Map<String, SSCurrency> currencies = currenciesByCode();
        Map<String, SSPaymentTerm> paymentTerms = paymentTermsByName();
        Map<String, SSDeliveryTerm> deliveryTerms = deliveryTermsByName();
        Map<String, SSDeliveryWay> deliveryWays = deliveryWaysByName();
        List<SSCustomer> customers = new ArrayList<>();
        Map<Integer, SSCustomer> byLegacyId = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT legacy_id,number,name,email,phone,phone_2,telefax,registration_number,"
                        + "our_contact_person,customer_contact_person,vat_number,bankgiro,plusgiro,"
                        + "account_number,clearing_number,eu_sale_commodity,eu_sale_third_party_commodity,"
                        + "vat_free_sale,hide_unit_price,invoice_currency_code,payment_term_name,"
                        + "delivery_term_name,delivery_way_name,credit_limit,discount,comment "
                        + "FROM customer WHERE company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "ORDER BY id")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSCustomer customer = new SSCustomer();
                    int legacyId = result.getInt(1);
                    customer.setNumber(result.getString(2));
                    customer.setName(result.getString(3));
                    customer.setEMail(result.getString(4));
                    customer.setPhone1(result.getString(5));
                    customer.setPhone2(result.getString(6));
                    customer.setTelefax(result.getString(7));
                    customer.setRegistrationNumber(result.getString(8));
                    customer.setOurContactPerson(result.getString(9));
                    customer.setYourContactPerson(result.getString(10));
                    customer.setVATNumber(result.getString(11));
                    customer.setBankgiro(result.getString(12));
                    customer.setPlusgiro(result.getString(13));
                    customer.setAccountNumber(result.getString(14));
                    customer.setClearingNumber(result.getString(15));
                    customer.setEuSaleCommodity(result.getBoolean(16));
                    customer.setEuSaleYhirdPartCommodity(result.getBoolean(17));
                    customer.setTaxFree(result.getBoolean(18));
                    customer.setHideUnitprice(result.getBoolean(19));
                    customer.setInvoiceCurrency(currencies.get(result.getString(20)));
                    customer.setPaymentTerm(paymentTerms.get(result.getString(21)));
                    customer.setDeliveryTerm(deliveryTerms.get(result.getString(22)));
                    customer.setDeliveryWay(deliveryWays.get(result.getString(23)));
                    customer.setCreditLimit(result.getBigDecimal(24));
                    customer.setDiscount(result.getBigDecimal(25));
                    customer.setComment(result.getString(26));
                    customers.add(customer);
                    byLegacyId.put(legacyId, customer);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT u.legacy_id,a.address_type,a.name,a.address_line_1,a.address_line_2,"
                        + "a.postal_code,a.city,a.country FROM customer_address a "
                        + "JOIN customer u ON u.id=a.customer_id "
                        + "WHERE a.company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "ORDER BY a.customer_id,a.address_type")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSCustomer customer = byLegacyId.get(result.getInt(1));
                    if (customer == null) {
                        continue;
                    }
                    SSAddress address = toAddress(result, 3);
                    if ("invoice".equals(result.getString(2))) {
                        customer.setInvoiceAddress(address);
                    } else {
                        customer.setDeliveryAddress(address);
                    }
                }
            }
        }
        return customers;
    }

    public void addCustomer(int companyLegacyId, SSCustomer customer) throws SQLException {
        long id;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO customer (legacy_id,company_id,number,name,email,phone,phone_2,telefax,"
                        + "registration_number,our_contact_person,customer_contact_person,vat_number,"
                        + "bankgiro,plusgiro,account_number,clearing_number,eu_sale_commodity,"
                        + "eu_sale_third_party_commodity,vat_free_sale,hide_unit_price,"
                        + "invoice_currency_code,payment_term_name,delivery_term_name,delivery_way_name,"
                        + "credit_limit,discount,comment) "
                        + "SELECT ?,id,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? "
                        + "FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            statement.setInt(i++, nextLegacyId("customer"));
            statement.setString(i++, customer.getNumber());
            statement.setString(i++, customer.getName());
            statement.setString(i++, customer.getEMail());
            statement.setString(i++, customer.getPhone1());
            statement.setString(i++, customer.getPhone2());
            statement.setString(i++, customer.getTelefax());
            statement.setString(i++, customer.getRegistrationNumber());
            statement.setString(i++, customer.getOurContactPerson());
            statement.setString(i++, customer.getYourContactPerson());
            statement.setString(i++, customer.getVATNumber());
            statement.setString(i++, customer.getBankgiro());
            statement.setString(i++, customer.getPlusgiro());
            statement.setString(i++, customer.getAccountNumber());
            statement.setString(i++, customer.getClearingNumber());
            statement.setBoolean(i++, customer.getEuSaleCommodity());
            statement.setBoolean(i++, customer.getEuSaleYhirdPartCommodity());
            statement.setBoolean(i++, customer.getTaxFree());
            statement.setBoolean(i++, customer.getHideUnitprice());
            statement.setString(i++, customer.getStoredInvoiceCurrency() == null
                    ? null : customer.getStoredInvoiceCurrency().getName());
            statement.setString(i++, customer.getPaymentTerm() == null
                    ? null : customer.getPaymentTerm().getName());
            statement.setString(i++, customer.getDeliveryTerm() == null
                    ? null : customer.getDeliveryTerm().getName());
            statement.setString(i++, customer.getDeliveryWay() == null
                    ? null : customer.getDeliveryWay().getName());
            statement.setBigDecimal(i++, customer.getCreditLimit());
            statement.setBigDecimal(i++, customer.getDiscount());
            statement.setString(i++, customer.getComment());
            statement.setInt(i, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No company with legacy id " + companyLegacyId);
            }
            id = generatedId(statement);
        }
        insertCustomerAddress(id, companyLegacyId, "invoice", customer.getInvoiceAddress());
        insertCustomerAddress(id, companyLegacyId, "delivery", customer.getDeliveryAddress());
    }

    public void deleteCustomer(int companyLegacyId, String number) throws SQLException {
        deleteCustomerAddresses(companyLegacyId, number);
        deleteByNumber("customer", companyLegacyId, number);
    }

    // ------------------------------------------------------------------ suppliers

    public List<SSSupplier> getSuppliers(int companyLegacyId) throws SQLException {
        Map<String, SSCurrency> currencies = currenciesByCode();
        Map<String, SSPaymentTerm> paymentTerms = paymentTermsByName();
        Map<String, SSDeliveryTerm> deliveryTerms = deliveryTermsByName();
        Map<String, SSDeliveryWay> deliveryWays = deliveryWaysByName();
        List<SSSupplier> suppliers = new ArrayList<>();
        Map<Integer, SSSupplier> byLegacyId = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT legacy_id,number,name,phone,phone_2,telefax,email,homepage,"
                        + "registration_number,supplier_contact_person,our_contact_person,"
                        + "our_customer_number,bankgiro,plusgiro,outpayment_number,currency_code,"
                        + "payment_term_name,delivery_term_name,delivery_way_name,comment "
                        + "FROM supplier WHERE company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "ORDER BY id")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSSupplier supplier = new SSSupplier();
                    int legacyId = result.getInt(1);
                    supplier.setNumber(result.getString(2));
                    supplier.setName(result.getString(3));
                    supplier.setPhone1(result.getString(4));
                    supplier.setPhone2(result.getString(5));
                    supplier.setTelefax(result.getString(6));
                    supplier.setEMail(result.getString(7));
                    supplier.setHomepage(result.getString(8));
                    supplier.setRegistrationNumber(result.getString(9));
                    supplier.setYourContact(result.getString(10));
                    supplier.setOurContact(result.getString(11));
                    supplier.setOurCustomerNr(result.getString(12));
                    supplier.setBankGiro(result.getString(13));
                    supplier.setPlusGiro(result.getString(14));
                    int outpaymentNumber = result.getInt(15);
                    supplier.setOutpaymentNumber(result.wasNull() ? null : outpaymentNumber);
                    supplier.setCurrency(currencies.get(result.getString(16)));
                    supplier.setPaymentTerm(paymentTerms.get(result.getString(17)));
                    supplier.setDeliveryTerm(deliveryTerms.get(result.getString(18)));
                    supplier.setDeliveryWay(deliveryWays.get(result.getString(19)));
                    supplier.setComment(result.getString(20));
                    suppliers.add(supplier);
                    byLegacyId.put(legacyId, supplier);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT s.legacy_id,a.name,a.address_line_1,a.address_line_2,a.postal_code,"
                        + "a.city,a.country FROM supplier_address a "
                        + "JOIN supplier s ON s.id=a.supplier_id "
                        + "WHERE a.company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSSupplier supplier = byLegacyId.get(result.getInt(1));
                    if (supplier != null) {
                        supplier.setAddress(toAddress(result, 2));
                    }
                }
            }
        }
        return suppliers;
    }

    public void addSupplier(int companyLegacyId, SSSupplier supplier) throws SQLException {
        long id;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO supplier (legacy_id,company_id,number,name,phone,phone_2,telefax,email,"
                        + "homepage,registration_number,supplier_contact_person,our_contact_person,"
                        + "our_customer_number,bankgiro,plusgiro,outpayment_number,currency_code,"
                        + "payment_term_name,delivery_term_name,delivery_way_name,comment) "
                        + "SELECT ?,id,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? "
                        + "FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            statement.setInt(i++, nextLegacyId("supplier"));
            statement.setString(i++, supplier.getNumber());
            statement.setString(i++, supplier.getName());
            statement.setString(i++, supplier.getPhone1());
            statement.setString(i++, supplier.getPhone2());
            statement.setString(i++, supplier.getTelefax());
            statement.setString(i++, supplier.getEMail());
            statement.setString(i++, supplier.getHomepage());
            statement.setString(i++, supplier.getRegistrationNumber());
            statement.setString(i++, supplier.getYourContact());
            statement.setString(i++, supplier.getOurContact());
            statement.setString(i++, supplier.getOurCustomerNr());
            statement.setString(i++, supplier.getBankgiro());
            statement.setString(i++, supplier.getPlusgiro());
            if (supplier.getOutpaymentNumber() == null) {
                statement.setNull(i++, java.sql.Types.INTEGER);
            } else {
                statement.setInt(i++, supplier.getOutpaymentNumber());
            }
            statement.setString(i++, supplier.getStoredCurrency() == null
                    ? null : supplier.getStoredCurrency().getName());
            statement.setString(i++, supplier.getPaymentTerm() == null
                    ? null : supplier.getPaymentTerm().getName());
            statement.setString(i++, supplier.getDeliveryTerm() == null
                    ? null : supplier.getDeliveryTerm().getName());
            statement.setString(i++, supplier.getDeliveryWay() == null
                    ? null : supplier.getDeliveryWay().getName());
            statement.setString(i++, supplier.getComment());
            statement.setInt(i, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No company with legacy id " + companyLegacyId);
            }
            id = generatedId(statement);
        }
        insertSupplierAddress(id, companyLegacyId, supplier.getAddress());
    }

    public void deleteSupplier(int companyLegacyId, String number) throws SQLException {
        deleteSupplierAddress(companyLegacyId, number);
        deleteByNumber("supplier", companyLegacyId, number);
    }

    // ------------------------------------------------------------------ products

    public List<SSProduct> getProducts(int companyLegacyId) throws SQLException {
        Map<String, SSUnit> units = unitsByName();
        Map<Integer, SSProduct> byLegacyId = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT legacy_id,number,description,selling_price,tax_code,warehouse_location,"
                        + "order_point,order_count,purchase_price,stock_price,unit_freight,"
                        + "supplier_number,supplier_product_number,expired,stock_goods,unit_name,"
                        + "weight,volume,project_number,result_unit_number "
                        + "FROM product WHERE company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "ORDER BY id")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSProduct product = new SSProduct();
                    int legacyId = result.getInt(1);
                    product.setNumber(result.getString(2));
                    product.setDescription(result.getString(3));
                    product.setSellingPrice(result.getBigDecimal(4));
                    String taxCode = result.getString(5);
                    if (taxCode != null) {
                        product.setTaxCode(SSTaxCode.valueOf(taxCode));
                    }
                    product.setWarehouseLocation(result.getString(6));
                    product.setOrderpoint(nullableInt(result, 7));
                    product.setOrdercount(nullableInt(result, 8));
                    product.setPurchasePrice(result.getBigDecimal(9));
                    product.setStockPrice(result.getBigDecimal(10));
                    product.setUnitFreight(result.getBigDecimal(11));
                    product.setSupplierNr(result.getString(12));
                    product.setSupplierProductNr(result.getString(13));
                    product.setExpired(result.getBoolean(14));
                    product.setStockProduct(result.getBoolean(15));
                    product.setUnit(units.get(result.getString(16)));
                    product.setWeight(result.getBigDecimal(17));
                    product.setVolume(result.getBigDecimal(18));
                    String projectNumber = result.getString(19);
                    String resultUnitNumber = result.getString(20);
                    byLegacyId.put(legacyId, product);
                    if (projectNumber != null) {
                        product.setProjectNr(projectNumber);
                    }
                    if (resultUnitNumber != null) {
                        product.setResultUnitNr(resultUnitNumber);
                    }
                }
            }
        }
        readProductChildren(companyLegacyId, byLegacyId);
        // Resolve project/result unit object references from their numbers.
        Map<String, SSNewProject> projects = new HashMap<>();
        for (SSNewProject project : getProjects(companyLegacyId)) {
            projects.put(project.getNumber(), project);
        }
        Map<String, SSNewResultUnit> resultUnits = new HashMap<>();
        for (SSNewResultUnit unit : getResultUnits(companyLegacyId)) {
            resultUnits.put(unit.getNumber(), unit);
        }
        for (SSProduct product : byLegacyId.values()) {
            if (product.getProjectNr() != null && projects.containsKey(product.getProjectNr())) {
                product.setProject(projects.get(product.getProjectNr()));
            }
            if (product.getResultUnitNr() != null
                    && resultUnits.containsKey(product.getResultUnitNr())) {
                product.setResultUnit(resultUnits.get(product.getResultUnitNr()));
            }
        }
        return new ArrayList<>(byLegacyId.values());
    }

    public void addProduct(int companyLegacyId, SSProduct product) throws SQLException {
        long id;
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO product (legacy_id,company_id,number,description,selling_price,tax_code,"
                        + "warehouse_location,order_point,order_count,purchase_price,stock_price,"
                        + "unit_freight,supplier_number,supplier_product_number,expired,stock_goods,"
                        + "unit_name,weight,volume,project_number,result_unit_number) "
                        + "SELECT ?,id,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,? "
                        + "FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            statement.setInt(i++, nextLegacyId("product"));
            statement.setString(i++, product.getNumber());
            statement.setString(i++, product.getDescription());
            statement.setBigDecimal(i++, product.getStoredSellingPrice());
            statement.setString(i++, product.getTaxCode() == null
                    ? null : product.getTaxCode().name());
            statement.setString(i++, product.getWarehouseLocation());
            setNullableInt(statement, i++, product.getOrderpoint());
            setNullableInt(statement, i++, product.getOrdercount());
            statement.setBigDecimal(i++, product.getPurchasePrice());
            statement.setBigDecimal(i++, product.getStockPrice());
            statement.setBigDecimal(i++, product.getUnitFreight());
            statement.setString(i++, product.getSupplierNr());
            statement.setString(i++, product.getSupplierProductNr());
            statement.setBoolean(i++, product.isExpired());
            statement.setBoolean(i++, product.isStockProduct());
            statement.setString(i++, product.getUnit() == null ? null : product.getUnit().getName());
            statement.setBigDecimal(i++, product.getStoredWeight());
            statement.setBigDecimal(i++, product.getStoredVolume());
            statement.setString(i++, product.getProjectNr());
            statement.setString(i++, product.getResultUnitNr());
            statement.setInt(i, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No company with legacy id " + companyLegacyId);
            }
            id = generatedId(statement);
        }
        for (Map.Entry<Locale, String> description : product.getDescriptions().entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO product_description (product_id,locale_tag,description) "
                            + "VALUES (?,?,?)")) {
                statement.setLong(1, id);
                statement.setString(2, description.getKey().toLanguageTag());
                statement.setString(3, description.getValue());
                statement.executeUpdate();
            }
        }
        for (Map.Entry<SSDefaultAccount, Integer> account
                : product.getStoredDefaultAccounts().entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO product_default_account (product_id,account_type,account_number) "
                            + "VALUES (?,?,?)")) {
                statement.setLong(1, id);
                statement.setString(2, account.getKey().name());
                setNullableInt(statement, 3, account.getValue());
                statement.executeUpdate();
            }
        }
        int rowNumber = 0;
        for (SSProductRow component : product.getParcelRows()) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO product_component (product_id,row_number,"
                            + "component_product_number,description,quantity) VALUES (?,?,?,?,?)")) {
                statement.setLong(1, id);
                statement.setInt(2, rowNumber++);
                statement.setString(3, component.getProductNr());
                statement.setString(4, component.getDescription());
                setNullableInt(statement, 5, component.getQuantity());
                statement.executeUpdate();
            }
        }
    }

    public void deleteProduct(int companyLegacyId, String number) throws SQLException {
        String productId = "SELECT id FROM product WHERE company_id="
                + "(SELECT id FROM company WHERE legacy_id=?) AND number=?";
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM product_description WHERE product_id IN (" + productId + ")")) {
            statement.setInt(1, companyLegacyId);
            statement.setString(2, number);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM product_default_account WHERE product_id IN (" + productId + ")")) {
            statement.setInt(1, companyLegacyId);
            statement.setString(2, number);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM product_component WHERE product_id IN (" + productId + ")")) {
            statement.setInt(1, companyLegacyId);
            statement.setString(2, number);
            statement.executeUpdate();
        }
        deleteByNumber("product", companyLegacyId, number);
    }

    // ------------------------------------------------------------------ lookups

    /** Reads the shared currency lookup, ordered by code. */
    public List<SSCurrency> getCurrencies() throws SQLException {
        List<SSCurrency> currencies = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT code,description,exchange_rate FROM currency ORDER BY code");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                SSCurrency currency = new SSCurrency();
                currency.setName(result.getString(1));
                currency.setDescription(result.getString(2));
                currency.setExchangeRate(result.getBigDecimal(3));
                currencies.add(currency);
            }
        }
        return currencies;
    }

    /** Reads the shared unit lookup, ordered by name. */
    public List<SSUnit> getUnits() throws SQLException {
        List<SSUnit> units = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name,description FROM unit_definition ORDER BY name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                units.add(new SSUnit(result.getString(1), result.getString(2)));
            }
        }
        return units;
    }

    /** Reads the shared payment term lookup, ordered by name. */
    public List<SSPaymentTerm> getPaymentTerms() throws SQLException {
        List<SSPaymentTerm> terms = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name,description FROM payment_term ORDER BY name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                terms.add(new SSPaymentTerm(result.getString(1), result.getString(2)));
            }
        }
        return terms;
    }

    /** Reads the shared delivery term lookup, ordered by name. */
    public List<SSDeliveryTerm> getDeliveryTerms() throws SQLException {
        List<SSDeliveryTerm> terms = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name,description FROM delivery_term ORDER BY name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                terms.add(new SSDeliveryTerm(result.getString(1), result.getString(2)));
            }
        }
        return terms;
    }

    /** Reads the shared delivery way lookup, ordered by name. */
    public List<SSDeliveryWay> getDeliveryWays() throws SQLException {
        List<SSDeliveryWay> ways = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name,description FROM delivery_way ORDER BY name");
             ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                ways.add(new SSDeliveryWay(result.getString(1), result.getString(2)));
            }
        }
        return ways;
    }

    /** Reads the projects of a company, ordered by number. */
    public List<SSNewProject> getProjects(int companyLegacyId) throws SQLException {
        List<SSNewProject> projects = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT p.number,p.name,p.description,p.concluded,p.concluded_on FROM project p "
                        + "WHERE p.company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "ORDER BY p.number")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSNewProject project = new SSNewProject();
                    project.setNumber(result.getString(1));
                    project.setName(result.getString(2));
                    project.setDescription(result.getString(3));
                    project.setConcluded(result.getBoolean(4));
                    java.sql.Date concludedOn = result.getDate(5);
                    if (concludedOn != null) {
                        project.setLocalConcludedDate(concludedOn.toLocalDate());
                    }
                    projects.add(project);
                }
            }
        }
        return projects;
    }

    /** Reads the result units of a company, ordered by number. */
    public List<SSNewResultUnit> getResultUnits(int companyLegacyId) throws SQLException {
        List<SSNewResultUnit> units = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT r.number,r.name,r.description FROM result_unit r "
                        + "WHERE r.company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "ORDER BY r.number")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSNewResultUnit unit = new SSNewResultUnit();
                    unit.setNumber(result.getString(1));
                    unit.setName(result.getString(2));
                    unit.setDescription(result.getString(3));
                    units.add(unit);
                }
            }
        }
        return units;
    }

    // ------------------------------------------------------------------ counters

    /** Returns the current value of a company auto-increment counter, 0 when absent. */
    public int autoIncrementValue(int companyLegacyId, String counter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT counter_value FROM company_auto_increment WHERE counter_name=? "
                        + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setString(1, counter);
            statement.setInt(2, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getInt(1) : 0;
            }
        }
    }

    /** Increments a company auto-increment counter, creating it at 1 when absent. */
    public void bumpAutoIncrement(int companyLegacyId, String counter) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE company_auto_increment SET counter_value=counter_value+1 "
                        + "WHERE counter_name=? "
                        + "AND company_id=(SELECT id FROM company WHERE legacy_id=?)")) {
            statement.setString(1, counter);
            statement.setInt(2, companyLegacyId);
            if (statement.executeUpdate() == 1) {
                return;
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO company_auto_increment (company_id,counter_name,counter_value) "
                        + "SELECT id,?,1 FROM company WHERE legacy_id=?")) {
            statement.setString(1, counter);
            statement.setInt(2, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No company with legacy id " + companyLegacyId);
            }
        }
    }

    // ------------------------------------------------------------------ internals

    private void readProductChildren(int companyLegacyId, Map<Integer, SSProduct> byLegacyId)
            throws SQLException {
        String companyFilter = "JOIN product p ON p.id=d.product_id WHERE p.company_id="
                + "(SELECT id FROM company WHERE legacy_id=?)";
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT p.legacy_id,d.locale_tag,d.description FROM product_description d "
                        + companyFilter + " ORDER BY p.legacy_id,d.locale_tag")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                Map<Integer, Map<Locale, String>> descriptions = new HashMap<>();
                while (result.next()) {
                    descriptions.computeIfAbsent(result.getInt(1), key -> new HashMap<>())
                            .put(Locale.forLanguageTag(result.getString(2)), result.getString(3));
                }
                descriptions.forEach((legacyId, map) -> {
                    SSProduct product = byLegacyId.get(legacyId);
                    if (product != null) {
                        product.setDescriptions(map);
                    }
                });
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT p.legacy_id,d.account_type,d.account_number FROM product_default_account d "
                        + companyFilter + " ORDER BY p.legacy_id,d.account_type")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSProduct product = byLegacyId.get(result.getInt(1));
                    if (product != null) {
                        product.setDefaultAccount(SSDefaultAccount.valueOf(result.getString(2)),
                                nullableInt(result, 3));
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT p.legacy_id,d.component_product_number,d.description,d.quantity "
                        + "FROM product_component d " + companyFilter
                        + " ORDER BY p.legacy_id,d.row_number")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSProduct product = byLegacyId.get(result.getInt(1));
                    if (product != null) {
                        SSProductRow component = new SSProductRow();
                        component.setProduct(result.getString(2));
                        component.setDescription(result.getString(3));
                        component.setQuantity(nullableInt(result, 4));
                        product.getParcelRows().add(component);
                    }
                }
            }
        }
    }

    private void insertCustomerAddress(long customerId, int companyLegacyId, String type,
            SSAddress address) throws SQLException {
        if (address == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO customer_address (customer_id,company_id,address_type,name,"
                        + "address_line_1,address_line_2,postal_code,city,country) "
                        + "SELECT ?,id,?,?,?,?,?,?,? FROM company WHERE legacy_id=?")) {
            statement.setLong(1, customerId);
            statement.setString(2, type);
            int i = 3;
            statement.setString(i++, address.getName());
            statement.setString(i++, address.getAddress1());
            statement.setString(i++, address.getAddress2());
            statement.setString(i++, address.getZipCode());
            statement.setString(i++, address.getCity());
            statement.setString(i++, address.getCountry());
            statement.setInt(i, companyLegacyId);
            statement.executeUpdate();
        }
    }

    private void deleteCustomerAddresses(int companyLegacyId, String number) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM customer_address WHERE customer_id IN (SELECT id FROM customer "
                        + "WHERE company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "AND number=?)")) {
            statement.setInt(1, companyLegacyId);
            statement.setString(2, number);
            statement.executeUpdate();
        }
    }

    private void insertSupplierAddress(long supplierId, int companyLegacyId, SSAddress address)
            throws SQLException {
        if (address == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO supplier_address (supplier_id,company_id,name,address_line_1,"
                        + "address_line_2,postal_code,city,country) "
                        + "SELECT ?,id,?,?,?,?,?,? FROM company WHERE legacy_id=?")) {
            statement.setLong(1, supplierId);
            int i = 2;
            statement.setString(i++, address.getName());
            statement.setString(i++, address.getAddress1());
            statement.setString(i++, address.getAddress2());
            statement.setString(i++, address.getZipCode());
            statement.setString(i++, address.getCity());
            statement.setString(i++, address.getCountry());
            statement.setInt(i, companyLegacyId);
            statement.executeUpdate();
        }
    }

    private void deleteSupplierAddress(int companyLegacyId, String number) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM supplier_address WHERE supplier_id IN (SELECT id FROM supplier "
                        + "WHERE company_id=(SELECT id FROM company WHERE legacy_id=?) "
                        + "AND number=?)")) {
            statement.setInt(1, companyLegacyId);
            statement.setString(2, number);
            statement.executeUpdate();
        }
    }

    private static SSAddress toAddress(ResultSet result, int offset) throws SQLException {
        SSAddress address = new SSAddress();
        address.setName(result.getString(offset));
        address.setAddress1(result.getString(offset + 1));
        address.setAddress2(result.getString(offset + 2));
        address.setZipCode(result.getString(offset + 3));
        address.setCity(result.getString(offset + 4));
        address.setCountry(result.getString(offset + 5));
        return address;
    }

    private Map<String, SSCurrency> currenciesByCode() throws SQLException {
        Map<String, SSCurrency> currencies = new HashMap<>();
        for (SSCurrency currency : getCurrencies()) {
            currencies.put(currency.getName(), currency);
        }
        return currencies;
    }

    private Map<String, SSUnit> unitsByName() throws SQLException {
        Map<String, SSUnit> units = new HashMap<>();
        for (SSUnit unit : getUnits()) {
            units.put(unit.getName(), unit);
        }
        return units;
    }

    private Map<String, SSPaymentTerm> paymentTermsByName() throws SQLException {
        Map<String, SSPaymentTerm> terms = new HashMap<>();
        for (SSPaymentTerm term : getPaymentTerms()) {
            terms.put(term.getName(), term);
        }
        return terms;
    }

    private Map<String, SSDeliveryTerm> deliveryTermsByName() throws SQLException {
        Map<String, SSDeliveryTerm> terms = new HashMap<>();
        for (SSDeliveryTerm term : getDeliveryTerms()) {
            terms.put(term.getName(), term);
        }
        return terms;
    }

    private Map<String, SSDeliveryWay> deliveryWaysByName() throws SQLException {
        Map<String, SSDeliveryWay> ways = new HashMap<>();
        for (SSDeliveryWay way : getDeliveryWays()) {
            ways.put(way.getName(), way);
        }
        return ways;
    }

    private void deleteByNumber(String table, int companyLegacyId, String number)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE company_id="
                        + "(SELECT id FROM company WHERE legacy_id=?) AND number=?")) {
            statement.setInt(1, companyLegacyId);
            statement.setString(2, number);
            statement.executeUpdate();
        }
    }

    private int nextLegacyId(String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(MAX(legacy_id),0)+1 FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static long generatedId(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            if (!keys.next()) {
                throw new SQLException("No generated key returned");
            }
            return keys.getLong(1);
        }
    }

    private static Integer nullableInt(ResultSet result, int index) throws SQLException {
        int value = result.getInt(index);
        return result.wasNull() ? null : value;
    }

    private static void setNullableInt(PreparedStatement statement, int index, Integer value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, java.sql.Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }
}
