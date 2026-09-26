package org.fribok.bookkeeping.dataformat;

import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.common.SSTaxCode;
import se.swedsoft.bookkeeping.data.util.SSMailServer;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Flattens complete company settings and child maps into normalized staging tables. */
public final class CompanyDetailsStagingConverter {

    public ConversionResult convert(Connection legacy, Connection normalized) throws SQLException {
        Objects.requireNonNull(legacy, "legacy");
        Objects.requireNonNull(normalized, "normalized");
        Snapshot source = readLegacy(legacy);
        SemanticFingerprint sourceFingerprint = fingerprint(source);
        try {
            writeNormalized(normalized, source);
            Snapshot destination = readNormalized(normalized);
            SemanticFingerprint destinationFingerprint = fingerprint(destination);
            List<String> differences = sourceFingerprint.differences(destinationFingerprint);
            if (!differences.isEmpty()) {
                normalized.rollback();
                throw new SQLException("Company-details staging fingerprint mismatch: "
                        + String.join("; ", differences));
            }
            normalized.commit();
            return new ConversionResult(sourceFingerprint, destinationFingerprint,
                    source.companies.size(), source.addresses.size(), source.standardTexts.size(),
                    source.defaultAccounts.size(), source.counters.size());
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
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT id,company FROM tbl_company ORDER BY id")) {
            while (result.next()) {
                int id = result.getInt(1);
                SSNewCompany company = (SSNewCompany) result.getObject(2);
                SSMailServer mail = company.getMailServer();
                snapshot.companies.add(new CompanyDetails(id, company.getPhone(), company.getPhone2(),
                        company.getTelefax(), company.getResidence(), company.getHomepage(), company.getSMTP(),
                        company.getEMail(), company.getContactPerson(), company.getTaxRegistered(),
                        company.getLogotype(), company.getBank(), company.getBankGiroNumber(),
                        company.getPlusGiroNumber(), company.getIBAN(), company.getBIC(),
                        company.getDelayInterest(), company.getReminderfee(), company.getEstimatedDelivery(),
                        company.getTaxRate(SSTaxCode.TAXRATE_1).orElse(null),
                        company.getTaxRate(SSTaxCode.TAXRATE_2).orElse(null),
                        company.getTaxRate(SSTaxCode.TAXRATE_3).orElse(null), company.getWeightUnit(),
                        company.getVolumeUnit(), name(company.getStandardUnit()), name(company.getPaymentTerm()),
                        name(company.getDeliveryTerm()), name(company.getDeliveryWay()), company.isRoundingOff(),
                        company.getVatPeriod(), mail == null ? null : mail.getName(),
                        mail == null ? null : mail.getURI().getHost(),
                        mail == null ? null : mail.getURI().getPort(),
                        mail == null ? null : mail.getBccAddresses(), mail == null ? null : mail.isAuth(),
                        mail == null ? null : mail.getConnectionSecurity().name(),
                        mail == null ? null : mail.getUsername(), mail == null ? null : mail.getPassword()));
                addAddress(snapshot, id, "postal", company.getAddress());
                addAddress(snapshot, id, "delivery", company.getDeliveryAddress());
                company.getStandardTexts().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name)))
                        .forEach(entry -> snapshot.standardTexts.add(
                                new NamedValue(id, entry.getKey().name(), entry.getValue())));
                company.getDefaultAccounts().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey(Comparator.comparing(Enum::name)))
                        .forEach(entry -> snapshot.defaultAccounts.add(
                                new NamedNumber(id, entry.getKey().name(), entry.getValue())));
                company.getAutoIncrement().getNumbers().entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .forEach(entry -> snapshot.counters.add(
                                new NamedNumber(id, entry.getKey(), entry.getValue())));
            }
        }
        snapshot.sort();
        return snapshot;
    }

    private static void writeNormalized(Connection connection, Snapshot snapshot) throws SQLException {
        for (CompanyDetails row : snapshot.companies) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    UPDATE company SET phone=?,phone_2=?,telefax=?,residence=?,web_address=?,smtp_address=?,
                    email=?,contact_person=?,tax_registered=?,logotype_path=?,bank=?,bank_account_number=?,
                    plus_account_number=?,iban=?,swift_bic=?,delay_interest=?,reminder_fee=?,estimated_delivery=?,
                    tax_rate_1=?,tax_rate_2=?,tax_rate_3=?,weight_unit=?,volume_unit=?,standard_unit_name=?,
                    payment_term_name=?,delivery_term_name=?,delivery_way_name=?,rounding_off=?,vat_period=?,
                    mail_name=?,mail_host=?,mail_port=?,mail_bcc=?,mail_auth=?,mail_security=?,mail_username=?,
                    mail_password=? WHERE legacy_id=?
                    """)) {
                int i = 1;
                for (String value : row.leadingStrings()) statement.setString(i++, value);
                statement.setBoolean(i++, row.taxRegistered);
                for (String value : row.middleStrings()) statement.setString(i++, value);
                statement.setBigDecimal(i++, row.delayInterest); statement.setBigDecimal(i++, row.reminderFee);
                statement.setString(i++, row.estimatedDelivery); statement.setBigDecimal(i++, row.taxRate1);
                statement.setBigDecimal(i++, row.taxRate2); statement.setBigDecimal(i++, row.taxRate3);
                for (String value : row.lookupStrings()) statement.setString(i++, value);
                statement.setBoolean(i++, row.roundingOff); setInteger(statement, i++, row.vatPeriod);
                statement.setString(i++, row.mailName); statement.setString(i++, row.mailHost);
                setInteger(statement, i++, row.mailPort); statement.setString(i++, row.mailBcc);
                if (row.mailAuth == null) statement.setNull(i++, java.sql.Types.BOOLEAN);
                else statement.setBoolean(i++, row.mailAuth);
                statement.setString(i++, row.mailSecurity); statement.setString(i++, row.mailUsername);
                statement.setString(i++, row.mailPassword); statement.setInt(i, row.legacyId);
                if (statement.executeUpdate() != 1) throw new SQLException("Missing company " + row.legacyId);
            }
        }
        for (AddressRow row : snapshot.addresses) insert(connection, "company_address",
                row.companyLegacyId, row.type, row.values());
        for (NamedValue row : snapshot.standardTexts) insert(connection, "company_standard_text",
                row.companyLegacyId, row.name, java.util.Collections.singletonList(row.value));
        for (NamedNumber row : snapshot.defaultAccounts) insertNumber(connection,
                "company_default_account", row);
        for (NamedNumber row : snapshot.counters) insertNumber(connection,
                "company_auto_increment", row);
    }

    private static Snapshot readNormalized(Connection connection) throws SQLException {
        Snapshot snapshot = new Snapshot();
        query(connection, """
                SELECT legacy_id,phone,phone_2,telefax,residence,web_address,smtp_address,email,
                contact_person,tax_registered,logotype_path,bank,bank_account_number,plus_account_number,
                iban,swift_bic,delay_interest,reminder_fee,estimated_delivery,tax_rate_1,tax_rate_2,tax_rate_3,
                weight_unit,volume_unit,standard_unit_name,payment_term_name,delivery_term_name,
                delivery_way_name,rounding_off,vat_period,mail_name,mail_host,mail_port,mail_bcc,mail_auth,
                mail_security,mail_username,mail_password FROM company ORDER BY legacy_id
                """, r -> snapshot.companies.add(readCompany(r)));
        query(connection, "SELECT c.legacy_id,a.address_type,a.name,a.address_line_1,a.address_line_2,"
                + "a.postal_code,a.city,a.country FROM company_address a JOIN company c ON c.id=a.company_id "
                + "ORDER BY c.legacy_id,a.address_type", r -> snapshot.addresses.add(new AddressRow(
                        r.getInt(1), r.getString(2), r.getString(3), r.getString(4), r.getString(5),
                        r.getString(6), r.getString(7), r.getString(8))));
        queryNamedValues(connection, snapshot, "company_standard_text", true);
        queryNamedNumbers(connection, snapshot, "company_default_account", true);
        queryNamedNumbers(connection, snapshot, "company_auto_increment", false);
        snapshot.sort();
        return snapshot;
    }

    private static CompanyDetails readCompany(ResultSet r) throws SQLException {
        int i = 1;
        int id = r.getInt(i++); String phone=r.getString(i++), phone2=r.getString(i++), fax=r.getString(i++),
                residence=r.getString(i++), web=r.getString(i++), smtp=r.getString(i++), email=r.getString(i++),
                contact=r.getString(i++); boolean tax=r.getBoolean(i++); String logo=r.getString(i++), bank=r.getString(i++),
                bankgiro=r.getString(i++), plusgiro=r.getString(i++), iban=r.getString(i++), bic=r.getString(i++);
        BigDecimal interest=r.getBigDecimal(i++), fee=r.getBigDecimal(i++); String estimated=r.getString(i++);
        BigDecimal tax1=r.getBigDecimal(i++), tax2=r.getBigDecimal(i++), tax3=r.getBigDecimal(i++);
        String weight=r.getString(i++), volume=r.getString(i++), unit=r.getString(i++), payment=r.getString(i++),
                deliveryTerm=r.getString(i++), deliveryWay=r.getString(i++); boolean rounding=r.getBoolean(i++);
        Integer vat=nullableInteger(r,i++); String mailName=r.getString(i++), host=r.getString(i++);
        Integer port=nullableInteger(r,i++); String bcc=r.getString(i++); Boolean auth=nullableBoolean(r,i++);
        return new CompanyDetails(id,phone,phone2,fax,residence,web,smtp,email,contact,tax,logo,bank,bankgiro,
                plusgiro,iban,bic,interest,fee,estimated,tax1,tax2,tax3,weight,volume,unit,payment,deliveryTerm,
                deliveryWay,rounding,vat,mailName,host,port,bcc,auth,r.getString(i++),r.getString(i++),r.getString(i));
    }

    private static SemanticFingerprint fingerprint(Snapshot s) {
        SemanticFingerprintBuilder b = new SemanticFingerprintBuilder();
        var companies=b.domain("company-details");
        for(CompanyDetails r:s.companies){var w=companies.startRecord(Integer.toString(r.legacyId));
            for(String v:r.leadingStrings())w.writeString(v);w.writeBoolean(r.taxRegistered);
            for(String v:r.middleStrings())w.writeString(v);w.writeDecimal(canonical(r.delayInterest)).writeDecimal(canonical(r.reminderFee)).writeString(r.estimatedDelivery)
                    .writeDecimal(canonical(r.taxRate1)).writeDecimal(canonical(r.taxRate2)).writeDecimal(canonical(r.taxRate3));
            for(String v:r.lookupStrings())w.writeString(v);w.writeBoolean(r.roundingOff).writeInteger(r.vatPeriod)
                    .writeString(r.mailName).writeString(r.mailHost).writeInteger(r.mailPort).writeString(r.mailBcc)
                    .writeBoolean(r.mailAuth).writeString(r.mailSecurity).writeString(r.mailUsername).writeString(r.mailPassword);}
        companies.finish(); fingerprintRows(b,"company-addresses",s.addresses); fingerprintRows(b,"company-standard-texts",s.standardTexts);
        fingerprintRows(b,"company-default-accounts",s.defaultAccounts); fingerprintRows(b,"company-auto-increments",s.counters); return b.finish();
    }

    private static void fingerprintRows(SemanticFingerprintBuilder b,String name,List<? extends FingerprintRow> rows){var d=b.domain(name);for(FingerprintRow r:rows)r.write(d);d.finish();}
    private static void addAddress(Snapshot s,int id,String type,SSAddress a){if(a!=null)s.addresses.add(new AddressRow(id,type,a.getName(),a.getAddress1(),a.getAddress2(),a.getZipCode(),a.getCity(),a.getCountry()));}
    private static String name(Object v){if(v instanceof se.swedsoft.bookkeeping.data.common.SSUnit x)return x.getName();if(v instanceof se.swedsoft.bookkeeping.data.common.SSPaymentTerm x)return x.getName();if(v instanceof se.swedsoft.bookkeeping.data.common.SSDeliveryTerm x)return x.getName();if(v instanceof se.swedsoft.bookkeeping.data.common.SSDeliveryWay x)return x.getName();return null;}
    private static BigDecimal canonical(BigDecimal v){return v==null?null:v.signum()==0?BigDecimal.ZERO:v.stripTrailingZeros();}
    private static void query(Connection c,String sql,SqlRow f)throws SQLException{try(Statement s=c.createStatement();ResultSet r=s.executeQuery(sql)){while(r.next())f.accept(r);}}
    private static void setInteger(PreparedStatement s,int i,Integer v)throws SQLException{if(v==null)s.setNull(i,java.sql.Types.INTEGER);else s.setInt(i,v);}
    private static Integer nullableInteger(ResultSet r,int i)throws SQLException{int v=r.getInt(i);return r.wasNull()?null:v;}
    private static Boolean nullableBoolean(ResultSet r,int i)throws SQLException{boolean v=r.getBoolean(i);return r.wasNull()?null:v;}
    private static void insert(Connection c,String table,int company,String key,List<String> values)throws SQLException{String q="INSERT INTO "+table+" SELECT id,?"+",?".repeat(values.size())+" FROM company WHERE legacy_id=?";try(PreparedStatement s=c.prepareStatement(q)){int i=1;s.setString(i++,key);for(String v:values)s.setString(i++,v);s.setInt(i,company);if(s.executeUpdate()!=1)throw new SQLException("Missing company "+company);}}
    private static void insertNumber(Connection c,String table,NamedNumber row)throws SQLException{String column=table.equals("company_default_account")?"account_type":"counter_name";String value=table.equals("company_default_account")?"account_number":"counter_value";try(PreparedStatement s=c.prepareStatement("INSERT INTO "+table+" (company_id,"+column+","+value+") SELECT id,?,? FROM company WHERE legacy_id=?")){s.setString(1,row.name);setInteger(s,2,row.value);s.setInt(3,row.companyLegacyId);if(s.executeUpdate()!=1)throw new SQLException("Missing company "+row.companyLegacyId);}}
    private static void queryNamedValues(Connection c,Snapshot s,String table,boolean standard)throws SQLException{query(c,"SELECT c.legacy_id,x.text_type,x.text_value FROM "+table+" x JOIN company c ON c.id=x.company_id ORDER BY c.legacy_id,x.text_type",r->s.standardTexts.add(new NamedValue(r.getInt(1),r.getString(2),r.getString(3))));}
    private static void queryNamedNumbers(Connection c,Snapshot s,String table,boolean defaults)throws SQLException{String n=defaults?"account_type":"counter_name",v=defaults?"account_number":"counter_value";query(c,"SELECT c.legacy_id,x."+n+",x."+v+" FROM "+table+" x JOIN company c ON c.id=x.company_id ORDER BY c.legacy_id,x."+n,r->{NamedNumber row=new NamedNumber(r.getInt(1),r.getString(2),nullableInteger(r,3));(defaults?s.defaultAccounts:s.counters).add(row);});}

    public record ConversionResult(SemanticFingerprint sourceFingerprint,SemanticFingerprint destinationFingerprint,long companies,long addresses,long standardTexts,long defaultAccounts,long counters){}
    @FunctionalInterface private interface SqlRow{void accept(ResultSet r)throws SQLException;}
    private interface FingerprintRow{void write(SemanticFingerprintBuilder.DomainWriter d);}
    private record CompanyDetails(int legacyId,String phone,String phone2,String telefax,String residence,String web,String smtp,String email,String contact,boolean taxRegistered,String logo,String bank,String bankgiro,String plusgiro,String iban,String bic,BigDecimal delayInterest,BigDecimal reminderFee,String estimatedDelivery,BigDecimal taxRate1,BigDecimal taxRate2,BigDecimal taxRate3,String weightUnit,String volumeUnit,String standardUnit,String paymentTerm,String deliveryTerm,String deliveryWay,boolean roundingOff,Integer vatPeriod,String mailName,String mailHost,Integer mailPort,String mailBcc,Boolean mailAuth,String mailSecurity,String mailUsername,String mailPassword){List<String> leadingStrings(){return java.util.Arrays.asList(phone,phone2,telefax,residence,web,smtp,email,contact);}List<String> middleStrings(){return java.util.Arrays.asList(logo,bank,bankgiro,plusgiro,iban,bic);}List<String> lookupStrings(){return java.util.Arrays.asList(weightUnit,volumeUnit,standardUnit,paymentTerm,deliveryTerm,deliveryWay);}}
    private record AddressRow(int companyLegacyId,String type,String name,String line1,String line2,String postal,String city,String country)implements FingerprintRow{List<String> values(){return java.util.Arrays.asList(name,line1,line2,postal,city,country);}public void write(SemanticFingerprintBuilder.DomainWriter d){var w=d.startRecord(companyLegacyId+"/"+type).writeInteger(companyLegacyId).writeString(type);for(String v:values())w.writeString(v);}}
    private record NamedValue(int companyLegacyId,String name,String value)implements FingerprintRow{public void write(SemanticFingerprintBuilder.DomainWriter d){d.startRecord(companyLegacyId+"/"+name).writeInteger(companyLegacyId).writeString(name).writeString(value);}}
    private record NamedNumber(int companyLegacyId,String name,Integer value)implements FingerprintRow{public void write(SemanticFingerprintBuilder.DomainWriter d){d.startRecord(companyLegacyId+"/"+name).writeInteger(companyLegacyId).writeString(name).writeInteger(value);}}
    private static final class Snapshot{final List<CompanyDetails>companies=new ArrayList<>();final List<AddressRow>addresses=new ArrayList<>();final List<NamedValue>standardTexts=new ArrayList<>();final List<NamedNumber>defaultAccounts=new ArrayList<>(),counters=new ArrayList<>();void sort(){companies.sort(Comparator.comparingInt(CompanyDetails::legacyId));addresses.sort(Comparator.comparingInt(AddressRow::companyLegacyId).thenComparing(AddressRow::type));standardTexts.sort(Comparator.comparingInt(NamedValue::companyLegacyId).thenComparing(NamedValue::name));Comparator<NamedNumber> n=Comparator.comparingInt(NamedNumber::companyLegacyId).thenComparing(NamedNumber::name);defaultAccounts.sort(n);counters.sort(n);}}
}
