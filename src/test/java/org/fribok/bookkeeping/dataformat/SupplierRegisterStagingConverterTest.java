package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSSupplier;
import se.swedsoft.bookkeeping.data.common.*;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class SupplierRegisterStagingConverterTest {
 @Test void roundTripsAllSupplierFieldsAndAddress()throws Exception{try(Connection legacy=conn("supplier_legacy");Connection target=conn("supplier_target")){schema(legacy);new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).migrate(target,NormalizedSchemaMigrations.load());SSNewCompany company=new SSNewCompany();company.setId(1);insertCompany(legacy,company);SSSupplier supplier=supplier();try(var p=legacy.prepareStatement("INSERT INTO tbl_supplier (id,number,supplier,companyid) VALUES (?,?,?,?)")){p.setInt(1,8);p.setString(2,supplier.getNumber());p.setObject(3,supplier);p.setInt(4,1);p.executeUpdate();legacy.commit();}new AccountingCoreStagingConverter().convert(legacy,target);var result=new SupplierRegisterStagingConverter().convert(legacy,target);assertThat(result.sourceFingerprint()).isEqualTo(result.destinationFingerprint());assertThat(result.suppliers()).isEqualTo(1);assertThat(result.addresses()).isEqualTo(1);assertThat(count(target,"supplier")).isEqualTo(1);assertThat(count(target,"supplier_address")).isEqualTo(1);}}
 @Test void rejectsTableAndObjectNumberMismatch()throws Exception{try(Connection legacy=conn("supplier_bad_legacy");Connection target=conn("supplier_bad_target")){schema(legacy);new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).migrate(target,NormalizedSchemaMigrations.load());SSNewCompany company=new SSNewCompany();company.setId(1);insertCompany(legacy,company);new AccountingCoreStagingConverter().convert(legacy,target);SSSupplier supplier=new SSSupplier();supplier.setNumber("object");try(var p=legacy.prepareStatement("INSERT INTO tbl_supplier (id,number,supplier,companyid) VALUES (?,?,?,?)")){p.setInt(1,8);p.setString(2,"table");p.setObject(3,supplier);p.setInt(4,1);p.executeUpdate();legacy.commit();}assertThatThrownBy(()->new SupplierRegisterStagingConverter().convert(legacy,target)).isInstanceOf(SQLException.class).hasMessageContaining("Supplier key/object mismatch");}}
 private static SSSupplier supplier(){SSSupplier s=new SSSupplier();s.setNumber("L-Å");s.setName("Leverantör AB");s.setPhone1("1");s.setPhone2("2");s.setTelefax("3");s.setEMail("l@example.se");s.setHomepage("https://l.se");s.setRegistrationNumber("556");s.setYourContact("Er");s.setOurContact("Vår");s.setOurCustomerNr("K1");s.setBankGiro("BG");s.setPlusGiro("PG");s.setOutpaymentNumber(-7);SSCurrency currency=new SSCurrency("DKK","Danska");currency.setExchangeRate(new BigDecimal("1.500"));s.setCurrency(currency);s.setPaymentTerm(new SSPaymentTerm("20","20 dagar"));s.setDeliveryTerm(new SSDeliveryTerm("FVL","Fritt lager"));s.setDeliveryWay(new SSDeliveryWay("B","Bil"));s.setAddress(new SSAddress("Leverantör","Gatan","C/O","111","Malmö","SE"));s.setComment("");return s;}
 private static Connection conn(String n)throws Exception{Class.forName("org.hsqldb.jdbcDriver");Connection c=DriverManager.getConnection("jdbc:hsqldb:mem:"+n+"_"+UUID.randomUUID(),"sa","");c.setAutoCommit(false);return c;}
 private static void schema(Connection c)throws Exception{try(InputStream in=SupplierRegisterStagingConverterTest.class.getResourceAsStream("/sql/create_tables.sql")){assertThat(in).isNotNull();String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);try(var p=c.createStatement()){for(String x:sql.split(";"))if(!x.isBlank())p.execute(x.trim());}c.commit();}}
 private static void insertCompany(Connection c,SSNewCompany x)throws Exception{try(var p=c.prepareStatement("INSERT INTO tbl_company (id,company) VALUES (?,?)")){p.setInt(1,x.getId());p.setObject(2,x);p.executeUpdate();c.commit();}}
 private static long count(Connection c,String t)throws Exception{try(var p=c.createStatement();var r=p.executeQuery("SELECT COUNT(*) FROM "+t)){r.next();return r.getLong(1);}}
}
