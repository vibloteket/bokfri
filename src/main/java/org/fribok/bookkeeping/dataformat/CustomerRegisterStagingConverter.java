package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCustomer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Converts the complete company-scoped customer register into normalized staging tables. */
public final class CustomerRegisterStagingConverter {

    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Snapshot source = readLegacy(Objects.requireNonNull(legacy, "legacy"));
        SemanticFingerprint sourceFingerprint = fingerprint(source);
        try {
            writeNormalized(Objects.requireNonNull(normalized, "normalized"), source);
            Snapshot destination = readNormalized(normalized);
            SemanticFingerprint destinationFingerprint = fingerprint(destination);
            List<String> differences = sourceFingerprint.differences(destinationFingerprint);
            if (!differences.isEmpty()) {
                normalized.rollback();
                throw new SQLException("Customer-register staging fingerprint mismatch: "
                        + String.join("; ", differences));
            }
            normalized.commit();
            return new ConversionResult(sourceFingerprint, destinationFingerprint,
                    source.customers.size(), source.addresses.size());
        } catch (SQLException | RuntimeException exception) {
            normalized.rollback();
            throw exception;
        }
    }

    public SemanticFingerprint fingerprintNormalized(Connection connection) throws SQLException {
        return fingerprint(readNormalized(connection));
    }

    private static Snapshot readLegacy(Connection connection) throws SQLException {
        Snapshot snapshot = new Snapshot();
        query(connection, "SELECT id,companyid,number,customer FROM tbl_customer "
                + "ORDER BY companyid,id", result -> {
            int id = result.getInt(1); int company = result.getInt(2);
            String tableNumber = result.getString(3);
            SSCustomer customer = (SSCustomer) result.getObject(4);
            if (!Objects.equals(tableNumber, customer.getNumber())) {
                throw new SQLException("Customer key/object mismatch for legacy id " + id
                        + ": table=" + tableNumber + ", object=" + customer.getNumber());
            }
            snapshot.customers.add(new Customer(id, company, tableNumber, customer.getName(),
                    customer.getEMail(), customer.getPhone1(), customer.getPhone2(),
                    customer.getTelefax(), customer.getRegistrationNumber(),
                    customer.getOurContactPerson(), customer.getYourContactPerson(),
                    customer.getVATNumber(), customer.getBankgiro(), customer.getPlusgiro(),
                    customer.getAccountNumber(), customer.getClearingNumber(),
                    customer.getEuSaleCommodity(), customer.getEuSaleYhirdPartCommodity(),
                    customer.getTaxFree(), customer.getHideUnitprice(),
                    customer.getStoredInvoiceCurrency() == null ? null
                            : customer.getStoredInvoiceCurrency().getName(),
                    name(customer.getPaymentTerm()), name(customer.getDeliveryTerm()),
                    name(customer.getDeliveryWay()), customer.getCreditLimit(),
                    customer.getDiscount(), customer.getComment()));
            addAddress(snapshot, id, company, "invoice", customer.getInvoiceAddress());
            addAddress(snapshot, id, company, "delivery", customer.getDeliveryAddress());
        });
        snapshot.sort();
        return snapshot;
    }

    private static void writeNormalized(Connection connection, Snapshot snapshot) throws SQLException {
        for (Customer r : snapshot.customers) {
            try (PreparedStatement s = connection.prepareStatement("""
                    INSERT INTO customer (legacy_id,company_id,number,name,email,phone,phone_2,telefax,
                      registration_number,our_contact_person,customer_contact_person,vat_number,bankgiro,
                      plusgiro,account_number,clearing_number,eu_sale_commodity,
                      eu_sale_third_party_commodity,vat_free_sale,hide_unit_price,invoice_currency_code,
                      payment_term_name,delivery_term_name,delivery_way_name,credit_limit,discount,comment)
                    SELECT ?,id,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?
                    FROM company WHERE legacy_id=?
                    """, Statement.RETURN_GENERATED_KEYS)) {
                int i=1;s.setInt(i++,r.legacyId);for(String v:r.leadingStrings())s.setString(i++,v);
                s.setBoolean(i++,r.euCommodity);s.setBoolean(i++,r.euThirdParty);s.setBoolean(i++,r.vatFree);
                s.setBoolean(i++,r.hideUnitPrice);for(String v:r.lookupStrings())s.setString(i++,v);
                s.setBigDecimal(i++,r.creditLimit);s.setBigDecimal(i++,r.discount);s.setString(i++,r.comment);
                s.setInt(i,r.companyLegacyId);if(s.executeUpdate()!=1)throw new SQLException("Missing company "+r.companyLegacyId+" for customer "+r.legacyId);
                r.normalizedId=generatedKey(s);
            }
        }
        java.util.Map<Integer,Long> ids=new java.util.HashMap<>();for(Customer r:snapshot.customers)ids.put(r.legacyId,r.normalizedId);
        for(Address r:snapshot.addresses){Long id=ids.get(r.customerLegacyId);if(id==null)throw new SQLException("Missing customer mapping "+r.customerLegacyId);try(PreparedStatement s=connection.prepareStatement("INSERT INTO customer_address VALUES (?,?,?,?,?,?,?,?,?)")){s.setLong(1,id);s.setLong(2,companyId(connection,r.companyLegacyId));s.setString(3,r.type);int i=4;for(String v:r.values())s.setString(i++,v);s.executeUpdate();}}
    }

    private static Snapshot readNormalized(Connection connection) throws SQLException {
        Snapshot snapshot=new Snapshot();
        query(connection,"SELECT u.id,u.legacy_id,c.legacy_id,u.number,u.name,u.email,u.phone,u.phone_2,u.telefax,u.registration_number,u.our_contact_person,u.customer_contact_person,u.vat_number,u.bankgiro,u.plusgiro,u.account_number,u.clearing_number,u.eu_sale_commodity,u.eu_sale_third_party_commodity,u.vat_free_sale,u.hide_unit_price,u.invoice_currency_code,u.payment_term_name,u.delivery_term_name,u.delivery_way_name,u.credit_limit,u.discount,u.comment FROM customer u JOIN company c ON c.id=u.company_id ORDER BY c.legacy_id,u.id",r->{Customer x=new Customer(r.getInt(2),r.getInt(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9),r.getString(10),r.getString(11),r.getString(12),r.getString(13),r.getString(14),r.getString(15),r.getString(16),r.getString(17),r.getBoolean(18),r.getBoolean(19),r.getBoolean(20),r.getBoolean(21),r.getString(22),r.getString(23),r.getString(24),r.getString(25),r.getBigDecimal(26),r.getBigDecimal(27),r.getString(28));x.normalizedId=r.getLong(1);snapshot.customers.add(x);});
        query(connection,"SELECT u.legacy_id,c.legacy_id,a.address_type,a.name,a.address_line_1,a.address_line_2,a.postal_code,a.city,a.country FROM customer_address a JOIN customer u ON u.id=a.customer_id JOIN company c ON c.id=a.company_id ORDER BY c.legacy_id,u.legacy_id,a.address_type",r->snapshot.addresses.add(new Address(r.getInt(1),r.getInt(2),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getString(7),r.getString(8),r.getString(9))));snapshot.sort();return snapshot;
    }

    private static SemanticFingerprint fingerprint(Snapshot s){SemanticFingerprintBuilder b=new SemanticFingerprintBuilder();var customers=b.domain("customers");for(Customer r:s.customers){var w=customers.startRecord(r.companyLegacyId+"/"+r.legacyId).writeInteger(r.legacyId).writeInteger(r.companyLegacyId);for(String v:r.leadingStrings())w.writeString(v);w.writeBoolean(r.euCommodity).writeBoolean(r.euThirdParty).writeBoolean(r.vatFree).writeBoolean(r.hideUnitPrice);for(String v:r.lookupStrings())w.writeString(v);w.writeDecimal(canonical(r.creditLimit)).writeDecimal(canonical(r.discount)).writeString(r.comment);}customers.finish();var addresses=b.domain("customer-addresses");for(Address r:s.addresses){var w=addresses.startRecord(r.companyLegacyId+"/"+r.customerLegacyId+"/"+r.type).writeInteger(r.customerLegacyId).writeInteger(r.companyLegacyId).writeString(r.type);for(String v:r.values())w.writeString(v);}addresses.finish();return b.finish();}
    private static void addAddress(Snapshot s,int customer,int company,String type,SSAddress a){if(a!=null)s.addresses.add(new Address(customer,company,type,a.getName(),a.getAddress1(),a.getAddress2(),a.getZipCode(),a.getCity(),a.getCountry()));}
    private static String name(Object v){if(v instanceof se.swedsoft.bookkeeping.data.common.SSPaymentTerm x)return x.getName();if(v instanceof se.swedsoft.bookkeeping.data.common.SSDeliveryTerm x)return x.getName();if(v instanceof se.swedsoft.bookkeeping.data.common.SSDeliveryWay x)return x.getName();return null;}
    private static BigDecimal canonical(BigDecimal v){return v==null?null:v.signum()==0?BigDecimal.ZERO:v.stripTrailingZeros();}
    private static long generatedKey(PreparedStatement s)throws SQLException{try(ResultSet r=s.getGeneratedKeys()){if(!r.next())throw new SQLException("Missing generated customer key");return r.getLong(1);}}
    private static long companyId(Connection c,int legacy)throws SQLException{try(PreparedStatement s=c.prepareStatement("SELECT id FROM company WHERE legacy_id=?")){s.setInt(1,legacy);try(ResultSet r=s.executeQuery()){if(!r.next())throw new SQLException("Missing company "+legacy);return r.getLong(1);}}}
    private static void query(Connection c,String sql,SqlRow f)throws SQLException{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){while(r.next())f.accept(r);}}

    public record ConversionResult(SemanticFingerprint sourceFingerprint,SemanticFingerprint destinationFingerprint,long customers,long addresses){}
    @FunctionalInterface private interface SqlRow{void accept(ResultSet r)throws SQLException;}
    private static final class Customer{final int legacyId,companyLegacyId;final String number,name,email,phone,phone2,telefax,registration,ourContact,customerContact,vatNumber,bankgiro,plusgiro,accountNumber,clearingNumber;final boolean euCommodity,euThirdParty,vatFree,hideUnitPrice;final String currency,paymentTerm,deliveryTerm,deliveryWay;final BigDecimal creditLimit,discount;final String comment;long normalizedId;Customer(int legacyId,int companyLegacyId,String number,String name,String email,String phone,String phone2,String telefax,String registration,String ourContact,String customerContact,String vatNumber,String bankgiro,String plusgiro,String accountNumber,String clearingNumber,boolean euCommodity,boolean euThirdParty,boolean vatFree,boolean hideUnitPrice,String currency,String paymentTerm,String deliveryTerm,String deliveryWay,BigDecimal creditLimit,BigDecimal discount,String comment){this.legacyId=legacyId;this.companyLegacyId=companyLegacyId;this.number=number;this.name=name;this.email=email;this.phone=phone;this.phone2=phone2;this.telefax=telefax;this.registration=registration;this.ourContact=ourContact;this.customerContact=customerContact;this.vatNumber=vatNumber;this.bankgiro=bankgiro;this.plusgiro=plusgiro;this.accountNumber=accountNumber;this.clearingNumber=clearingNumber;this.euCommodity=euCommodity;this.euThirdParty=euThirdParty;this.vatFree=vatFree;this.hideUnitPrice=hideUnitPrice;this.currency=currency;this.paymentTerm=paymentTerm;this.deliveryTerm=deliveryTerm;this.deliveryWay=deliveryWay;this.creditLimit=creditLimit;this.discount=discount;this.comment=comment;}List<String>leadingStrings(){return java.util.Arrays.asList(number,name,email,phone,phone2,telefax,registration,ourContact,customerContact,vatNumber,bankgiro,plusgiro,accountNumber,clearingNumber);}List<String>lookupStrings(){return java.util.Arrays.asList(currency,paymentTerm,deliveryTerm,deliveryWay);}}
    private record Address(int customerLegacyId,int companyLegacyId,String type,String name,String line1,String line2,String postal,String city,String country){List<String>values(){return java.util.Arrays.asList(name,line1,line2,postal,city,country);}}
    private static final class Snapshot{final List<Customer>customers=new ArrayList<>();final List<Address>addresses=new ArrayList<>();void sort(){customers.sort(Comparator.comparingInt((Customer r)->r.companyLegacyId).thenComparingInt(r->r.legacyId));addresses.sort(Comparator.comparingInt(Address::companyLegacyId).thenComparingInt(Address::customerLegacyId).thenComparing(Address::type));}}
}
