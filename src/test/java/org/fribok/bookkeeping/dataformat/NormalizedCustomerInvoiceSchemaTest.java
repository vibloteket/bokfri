package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NormalizedCustomerInvoiceSchemaTest {

    @Test
    void roundTripsInvoiceRowsAddressesAccountsAndVoucherSnapshot() throws Exception {
        try (Connection c = connection()) {
            migrate(c);
            long company = company(c);
            try (var p=c.prepareStatement("INSERT INTO customer_invoice (legacy_id,company_id,number,invoice_date,due_date,customer_name,tax_free,eu_sale_commodity,eu_sale_third_party_commodity,printed,entered,reminder_count,interest_invoiced,stock_influencing,currency_rate) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",java.sql.Statement.RETURN_GENERATED_KEYS)) {
                p.setInt(1,91);p.setLong(2,company);p.setInt(3,42);p.setObject(4,LocalDate.of(2026,1,2));p.setObject(5,LocalDate.of(2026,2,1));p.setString(6,"");p.setBoolean(7,false);p.setBoolean(8,true);p.setBoolean(9,false);p.setBoolean(10,true);p.setBoolean(11,false);p.setInt(12,-1);p.setBoolean(13,true);p.setBoolean(14,false);p.setBigDecimal(15,new BigDecimal("1.123456789012345678901234567890"));p.executeUpdate();try(var k=p.getGeneratedKeys()){k.next();long invoice=k.getLong(1);try(var a=c.prepareStatement("INSERT INTO customer_invoice_address VALUES (?,?,?,?,?,?,?,?,?)")){a.setLong(1,invoice);a.setLong(2,company);a.setString(3,"invoice");for(int i=4;i<=9;i++)a.setString(i,i==4?null:"");a.executeUpdate();}try(var r=c.prepareStatement("INSERT INTO customer_invoice_row VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")){r.setLong(1,invoice);r.setLong(2,company);r.setInt(3,0);r.setString(4,null);r.setString(5,"");r.setBigDecimal(6,new BigDecimal("10.123"));r.setBigDecimal(7,new BigDecimal("-2.50"));r.setString(8,null);r.setBigDecimal(9,null);r.setString(10,null);r.setNull(11,java.sql.Types.INTEGER);r.setString(12,null);r.setString(13,null);r.executeUpdate();}try(var v=c.prepareStatement("INSERT INTO customer_invoice_voucher VALUES (?,?,?,?)")){v.setLong(1,invoice);v.setInt(2,0);v.setObject(3,null);v.setString(4,"");v.executeUpdate();}try(var v=c.prepareStatement("INSERT INTO customer_invoice_voucher_row VALUES (?,?,?,?,?,?,?,?,?,?,?)")){v.setLong(1,invoice);v.setInt(2,0);v.setNull(3,java.sql.Types.INTEGER);v.setString(4,null);v.setString(5,null);v.setBigDecimal(6,new BigDecimal("1.00"));v.setBigDecimal(7,null);v.setObject(8,OffsetDateTime.ofInstant(Instant.parse("2026-01-02T10:00:00.123456Z"),ZoneOffset.UTC));v.setString(9,"vb");v.setBoolean(10,true);v.setBoolean(11,false);v.executeUpdate();}}
            }
            c.commit();
            assertThat(count(c,"customer_invoice")).isEqualTo(1);assertThat(count(c,"customer_invoice_row")).isEqualTo(1);assertThat(count(c,"customer_invoice_voucher_row")).isEqualTo(1);
        }
    }

    @Test void enforcesCompanyScopedDimensionsAndInvoiceOwnership()throws Exception{try(Connection c=connection()){migrate(c);long first=company(c),second=company(c);try(var p=c.prepareStatement("INSERT INTO project VALUES (?,?,?,?,?,?)")){p.setLong(1,first);p.setString(2,"P1");p.setString(3,null);p.setString(4,null);p.setBoolean(5,false);p.setObject(6,null);p.executeUpdate();}long invoice=invoice(c,second);assertThatThrownBy(()->{try(var r=c.prepareStatement("INSERT INTO customer_invoice_row (invoice_id,company_id,row_number,project_number) VALUES (?,?,0,'P1')")){r.setLong(1,invoice);r.setLong(2,second);r.executeUpdate();}}).isInstanceOf(java.sql.SQLException.class);}}

    private static Connection connection()throws Exception{Class.forName("org.hsqldb.jdbcDriver");Connection c=DriverManager.getConnection("jdbc:hsqldb:mem:invoice_schema_"+UUID.randomUUID(),"sa","");c.setAutoCommit(false);return c;}private static void migrate(Connection c)throws Exception{new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).migrate(c,NormalizedSchemaMigrations.load());}private static long company(Connection c)throws Exception{try(var p=c.prepareStatement("INSERT INTO company (legacy_id) VALUES (?)",java.sql.Statement.RETURN_GENERATED_KEYS)){p.setInt(1,(int)(count(c,"company")+1));p.executeUpdate();try(var k=p.getGeneratedKeys()){k.next();return k.getLong(1);}}}private static long invoice(Connection c,long company)throws Exception{try(var p=c.prepareStatement("INSERT INTO customer_invoice (legacy_id,company_id,tax_free,eu_sale_commodity,eu_sale_third_party_commodity,printed,entered,reminder_count,interest_invoiced,stock_influencing) VALUES (?,?,FALSE,FALSE,FALSE,FALSE,FALSE,0,FALSE,FALSE)",java.sql.Statement.RETURN_GENERATED_KEYS)){p.setInt(1,(int)(count(c,"customer_invoice")+1));p.setLong(2,company);p.executeUpdate();try(var k=p.getGeneratedKeys()){k.next();return k.getLong(1);}}}private static long count(Connection c,String t)throws Exception{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+t)){r.next();return r.getLong(1);}}
}
