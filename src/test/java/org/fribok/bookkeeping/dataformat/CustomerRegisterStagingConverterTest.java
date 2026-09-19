package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSCustomer;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.common.SSCurrency;
import se.swedsoft.bookkeeping.data.common.SSDeliveryTerm;
import se.swedsoft.bookkeeping.data.common.SSDeliveryWay;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerRegisterStagingConverterTest {

    @Test
    void roundTripsAllCustomerFieldsAndAddresses() throws Exception {
        try(Connection legacy=connection("customer_legacy");Connection target=connection("customer_target")){
            createLegacySchema(legacy);new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).migrate(target,NormalizedSchemaMigrations.load());SSNewCompany company=new SSNewCompany();company.setId(1);company.setName("Company");insertCompany(legacy,company);
            SSCustomer customer=customer();try(var s=legacy.prepareStatement("INSERT INTO tbl_customer (id,number,customer,companyid) VALUES (?,?,?,?)")){s.setInt(1,9);s.setString(2,customer.getNumber());s.setObject(3,customer);s.setInt(4,1);s.executeUpdate();legacy.commit();}
            new AccountingCoreStagingConverter().convert(legacy,target);
            var result=new CustomerRegisterStagingConverter().convert(legacy,target);
            assertThat(result.sourceFingerprint()).isEqualTo(result.destinationFingerprint());assertThat(result.customers()).isEqualTo(1);assertThat(result.addresses()).isEqualTo(2);assertThat(count(target,"customer")).isEqualTo(1);assertThat(count(target,"customer_address")).isEqualTo(2);
        }
    }

    @Test
    void rejectsLegacyTableAndObjectNumberMismatch() throws Exception {
        try(Connection legacy=connection("customer_bad_legacy");Connection target=connection("customer_bad_target")){
            createLegacySchema(legacy);new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).migrate(target,NormalizedSchemaMigrations.load());SSNewCompany company=new SSNewCompany();company.setId(1);insertCompany(legacy,company);new AccountingCoreStagingConverter().convert(legacy,target);SSCustomer customer=new SSCustomer();customer.setNumber("object");try(var s=legacy.prepareStatement("INSERT INTO tbl_customer (id,number,customer,companyid) VALUES (?,?,?,?)")){s.setInt(1,9);s.setString(2,"table");s.setObject(3,customer);s.setInt(4,1);s.executeUpdate();legacy.commit();}
            assertThatThrownBy(()->new CustomerRegisterStagingConverter().convert(legacy,target)).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("Customer key/object mismatch");
        }
    }

    private static SSCustomer customer(){SSCustomer c=new SSCustomer();c.setNumber("K-Å");c.setName("Kund AB");c.setEMail("kund@example.se");c.setPhone1("1");c.setPhone2("2");c.setTelefax("3");c.setRegistrationNumber("556");c.setOurContactPerson("Vi");c.setYourContactPerson("Ni");c.setVATNumber("SE556");c.setBankgiro("BG");c.setPlusgiro("PG");c.setAccountNumber("A");c.setClearingNumber("C");c.setEuSaleCommodity(true);c.setEuSaleYhirdPartCommodity(true);c.setTaxFree(true);c.setHideUnitprice(true);SSCurrency currency=new SSCurrency("NOK","Norska");currency.setExchangeRate(new BigDecimal("0.95"));c.setInvoiceCurrency(currency);c.setPaymentTerm(new SSPaymentTerm("10","10 dagar"));c.setDeliveryTerm(new SSDeliveryTerm("FK","Fritt"));c.setDeliveryWay(new SSDeliveryWay("P","Post"));c.setCreditLimit(new BigDecimal("10000.00"));c.setDiscount(new BigDecimal("2.500"));c.setComment("");c.setInvoiceAddress(new SSAddress("Faktura","Gatan 1","","11122","Stockholm","SE"));c.setDeliveryAddress(new SSAddress("Leverans","Vägen 2","C/O","22233","Uppsala","SE"));return c;}
    private static Connection connection(String n)throws Exception{Class.forName("org.hsqldb.jdbcDriver");Connection c=DriverManager.getConnection("jdbc:hsqldb:mem:"+n+"_"+UUID.randomUUID(),"sa","");c.setAutoCommit(false);return c;}
    private static void createLegacySchema(Connection c)throws Exception{try(InputStream in=CustomerRegisterStagingConverterTest.class.getResourceAsStream("/sql/create_tables.sql")){assertThat(in).isNotNull();String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);try(var s=c.createStatement()){for(String p:sql.split(";"))if(!p.isBlank())s.execute(p.trim());}c.commit();}}
    private static void insertCompany(Connection c,SSNewCompany x)throws Exception{try(var s=c.prepareStatement("INSERT INTO tbl_company (id,company) VALUES (?,?)")){s.setInt(1,x.getId());s.setObject(2,x);s.executeUpdate();c.commit();}}
    private static long count(Connection c,String table)throws Exception{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
}
