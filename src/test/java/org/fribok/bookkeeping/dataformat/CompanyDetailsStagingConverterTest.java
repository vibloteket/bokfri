package org.fribok.bookkeeping.dataformat;

import org.fribok.bookkeeping.data.util.ConnectionSecurity;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAddress;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSStandardText;
import se.swedsoft.bookkeeping.data.common.SSDefaultAccount;
import se.swedsoft.bookkeeping.data.common.SSDeliveryTerm;
import se.swedsoft.bookkeeping.data.common.SSDeliveryWay;
import se.swedsoft.bookkeeping.data.common.SSPaymentTerm;
import se.swedsoft.bookkeeping.data.common.SSUnit;
import se.swedsoft.bookkeeping.data.util.SSMailServer;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CompanyDetailsStagingConverterTest {

    @Test
    void roundTripsCompanyFieldsChildrenMailAndCounters() throws Exception {
        try (Connection legacy=connection("company_legacy");Connection target=connection("company_target")) {
            createLegacySchema(legacy);
            new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
                    .migrate(target,NormalizedSchemaMigrations.load());
            SSNewCompany company=company();
            try(var s=legacy.prepareStatement("INSERT INTO tbl_company (id,company) VALUES (?,?)")){
                s.setInt(1,7);s.setObject(2,company);s.executeUpdate();legacy.commit();
            }
            // The core converter creates company and required lookup parents first.
            new AccountingCoreStagingConverter().convert(legacy,target);
            var result=new CompanyDetailsStagingConverter().convert(legacy,target);
            assertThat(result.sourceFingerprint()).isEqualTo(result.destinationFingerprint());
            assertThat(result.companies()).isEqualTo(1);assertThat(result.addresses()).isEqualTo(2);
            assertThat(result.standardTexts()).isEqualTo(2);assertThat(result.defaultAccounts()).isEqualTo(2);
            assertThat(result.counters()).isEqualTo(2);
            assertThat(count(target,"company_address")).isEqualTo(2);
            assertThat(count(target,"company_auto_increment")).isEqualTo(2);
            try(var s=target.createStatement();var r=s.executeQuery("SELECT mail_host,mail_port,mail_security,tax_rate_1 FROM company WHERE legacy_id=7")){
                assertThat(r.next()).isTrue();assertThat(r.getString(1)).isEqualTo("smtp.example.se");
                assertThat(r.getInt(2)).isEqualTo(587);assertThat(r.getString(3)).isEqualTo("STARTTLS");
                assertThat(r.getBigDecimal(4)).isEqualByComparingTo("25.000000000000000000000000000000");
            }
        }
    }

    private static SSNewCompany company() throws Exception {
        SSNewCompany c=new SSNewCompany();c.setId(7);c.setName("Exempel AB");
        c.setPhone("1");c.setPhone2("2");c.setTelefax("3");c.setResidence("Stockholm");
        c.setHomepage("https://example.se");c.setSMTP("legacy-smtp");c.setEMail("info@example.se");
        c.setContactPerson("Åsa");c.setTaxRegistered(true);c.setLogotype("/tmp/logo.png");c.setBank("Bank");
        c.setBankGiroNumber("123-4");c.setPlusGiroNumber("55-6");c.setIBAN("SE00");c.setBIC("ABC");
        c.setDelayInterest(new BigDecimal("12.50"));c.setReminderfee(new BigDecimal("60.00"));
        c.setEstimatedDelivery("2 dagar");c.setTaxrate1(new BigDecimal("25.00"));
        c.setTaxrate2(new BigDecimal("12.0"));c.setTaxrate3(new BigDecimal("6"));
        c.setWeightUnit("kg");c.setVolumeUnit("m3");c.setStandardUnit(new SSUnit("st","styck"));
        c.setPaymentTerm(new SSPaymentTerm("30","30 dagar"));c.setDeliveryTerm(new SSDeliveryTerm("FK","Fritt"));
        c.setDeliveryWay(new SSDeliveryWay("P","Post"));c.setRoundingOff(true);c.setVatPeriod(3);
        c.setAddress(new SSAddress("Bolaget","Gatan 1","C/O X","11122","Stockholm","SE"));
        c.setDeliveryAddress(new SSAddress("Lagret","Vägen 2","","22233","Uppsala","SE"));
        c.getStandardTexts().put(SSStandardText.Email,"Hej");c.getStandardTexts().put(SSStandardText.Reminder,"");
        c.getDefaultAccounts().put(SSDefaultAccount.Cash,1910);c.getDefaultAccounts().put(SSDefaultAccount.Sales,null);
        c.getAutoIncrement().setNumber("invoice",42);c.getAutoIncrement().setNumber("order",0);
        c.setMailServer(new SSMailServer("primary",new URI(null,null,"smtp.example.se",587,null,null,null),
                "audit@example.se",true,ConnectionSecurity.STARTTLS,"user","secret"));return c;
    }

    private static Connection connection(String name)throws Exception{Class.forName("org.hsqldb.jdbcDriver");Connection c=DriverManager.getConnection("jdbc:hsqldb:mem:"+name+"_"+UUID.randomUUID(),"sa","");c.setAutoCommit(false);return c;}
    private static void createLegacySchema(Connection c)throws Exception{try(InputStream in=CompanyDetailsStagingConverterTest.class.getResourceAsStream("/sql/create_tables.sql")){assertThat(in).isNotNull();String sql=new String(in.readAllBytes(), StandardCharsets.UTF_8);try(var s=c.createStatement()){for(String part:sql.split(";"))if(!part.isBlank())s.execute(part.trim());}c.commit();}}
    private static long count(Connection c,String table)throws Exception{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
}
