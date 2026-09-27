package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSProduct;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSUnit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** SSDB-shaped register reads/writes over normalized customer, supplier, and product tables. */
public final class NormalizedRegisterStore {
    private final Connection connection;

    public NormalizedRegisterStore(Connection connection) {
        this.connection = Objects.requireNonNull(connection, "connection");
    }

    public List<SSCustomer> getCustomers(int companyLegacyId) throws SQLException {
        List<SSCustomer> customers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT number,name,email,eu_sale_commodity,eu_sale_third_party_commodity,"
                        + "vat_free_sale,hide_unit_price FROM customer WHERE company_id="
                        + "(SELECT id FROM company WHERE legacy_id=?) ORDER BY id")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSCustomer customer = new SSCustomer();
                    customer.setNumber(result.getString(1));
                    customer.setName(result.getString(2));
                    customer.setEMail(result.getString(3));
                    customer.setEuSaleCommodity(result.getBoolean(4));
                    customer.setEuSaleYhirdPartCommodity(result.getBoolean(5));
                    customer.setTaxFree(result.getBoolean(6));
                    customer.setHideUnitprice(result.getBoolean(7));
                    customers.add(customer);
                }
            }
        }
        return customers;
    }

    public void addCustomer(int companyLegacyId, SSCustomer customer) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO customer (legacy_id,company_id,number,name,email,"
                        + "eu_sale_commodity,eu_sale_third_party_commodity,vat_free_sale,"
                        + "hide_unit_price) SELECT ?,id,?,?,?,?,?,?,? "
                        + "FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) {
            int legacyId = nextLegacyId(connection, "customer");
            statement.setInt(1, legacyId);
            statement.setString(2, customer.getNumber());
            statement.setString(3, customer.getName());
            statement.setString(4, customer.getEMail());
            statement.setBoolean(5, customer.getEuSaleCommodity());
            statement.setBoolean(6, customer.getEuSaleYhirdPartCommodity());
            statement.setBoolean(7, customer.getTaxFree());
            statement.setBoolean(8, customer.getHideUnitprice());
            statement.setInt(9, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No company with legacy id " + companyLegacyId);
            }
        }
    }

    public void deleteCustomer(int companyLegacyId, String number) throws SQLException {
        deleteByNumber("customer", companyLegacyId, number);
    }

    public List<SSSupplier> getSuppliers(int companyLegacyId) throws SQLException {
        List<SSSupplier> suppliers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT number,name,email FROM supplier WHERE company_id="
                        + "(SELECT id FROM company WHERE legacy_id=?) ORDER BY id")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSSupplier supplier = new SSSupplier();
                    supplier.setNumber(result.getString(1));
                    supplier.setName(result.getString(2));
                    supplier.setEMail(result.getString(3));
                    suppliers.add(supplier);
                }
            }
        }
        return suppliers;
    }

    public void addSupplier(int companyLegacyId, SSSupplier supplier) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO supplier (legacy_id,company_id,number,name,email) "
                        + "SELECT ?,id,?,?,? FROM company WHERE legacy_id=?",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, nextLegacyId(connection, "supplier"));
            statement.setString(2, supplier.getNumber());
            statement.setString(3, supplier.getName());
            statement.setString(4, supplier.getEMail());
            statement.setInt(5, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No company with legacy id " + companyLegacyId);
            }
        }
    }

    public void deleteSupplier(int companyLegacyId, String number) throws SQLException {
        deleteByNumber("supplier", companyLegacyId, number);
    }

    public List<SSProduct> getProducts(int companyLegacyId) throws SQLException {
        List<SSProduct> products = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT number,description,selling_price,expired,stock_goods FROM product "
                        + "WHERE company_id=(SELECT id FROM company WHERE legacy_id=?) ORDER BY id")) {
            statement.setInt(1, companyLegacyId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    SSProduct product = new SSProduct();
                    product.setNumber(result.getString(1));
                    product.setDescription(result.getString(2));
                    product.setSellingPrice(result.getBigDecimal(3));
                    product.setExpired(result.getBoolean(4));
                    product.setStockProduct(result.getBoolean(5));
                    products.add(product);
                }
            }
        }
        return products;
    }

    public void addProduct(int companyLegacyId, SSProduct product) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO product (legacy_id,company_id,number,description,selling_price,"
                        + "expired,stock_goods) SELECT ?,id,?,?,?,?,? "
                        + "FROM company WHERE legacy_id=?", Statement.RETURN_GENERATED_KEYS)) {
            statement.setInt(1, nextLegacyId(connection, "product"));
            statement.setString(2, product.getNumber());
            statement.setString(3, product.getDescription());
            statement.setBigDecimal(4, product.getStoredSellingPrice());
            statement.setBoolean(5, product.isExpired());
            statement.setBoolean(6, product.isStockProduct());
            statement.setInt(7, companyLegacyId);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("No company with legacy id " + companyLegacyId);
            }
        }
    }

    public void deleteProduct(int companyLegacyId, String number) throws SQLException {
        deleteByNumber("product", companyLegacyId, number);
    }

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

    private static int nextLegacyId(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT COALESCE(MAX(legacy_id),0)+1 FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
