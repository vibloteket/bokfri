package org.fribok.bookkeeping.dataformat;
import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.*;
import java.io.InputStream;import java.nio.charset.StandardCharsets;import java.sql.*;import java.time.*;import java.util.*;
import static org.assertj.core.api.Assertions.*;
class StockDocumentStagingConverterTest{
 @Test void roundTripsAllThreeStockKinds()throws Exception{try(Connection legacy=conn("stock_legacy");Connection target=conn("stock_target")){schema(legacy);new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).migrate(target,NormalizedSchemaMigrations.load());SSNewCompany company=new SSNewCompany();company.setId(1);insertCompany(legacy,company);
   SSIndelivery in=new SSIndelivery();in.setNumber(1);in.setLocalDate(LocalDate.of(2026,8,1));in.setText("");SSIndeliveryRow ir=new SSIndeliveryRow();ir.setProductNr("A");ir.setChange(5);in.getRows().add(ir);
   try(var p=legacy.prepareStatement("INSERT INTO tbl_indelivery (id,number,indelivery,companyid) VALUES (?,?,?,?)")){p.setInt(1,1);p.setInt(2,1);p.setObject(3,in);p.setInt(4,1);p.executeUpdate();}
   SSOutdelivery out=new SSOutdelivery();out.setNumber(2);out.setLocalDate(null);out.setText(null);SSOutdeliveryRow or=new SSOutdeliveryRow();or.setProduct(null);or.setChange(-3);out.getRows().add(or);
   try(var p=legacy.prepareStatement("INSERT INTO tbl_outdelivery (id,number,outdelivery,companyid) VALUES (?,?,?,?)")){p.setInt(1,2);p.setInt(2,2);p.setObject(3,out);p.setInt(4,1);p.executeUpdate();}
   SSInventory inv=new SSInventory();inv.setNumber(3);inv.setLocalDate(LocalDate.of(2026,8,31));inv.setText("Inventering");SSInventoryRow vr=new SSInventoryRow();vr.setProductNr("B");vr.setStockQuantity(10);vr.setChange(null);inv.getRows().add(vr);
   try(var p=legacy.prepareStatement("INSERT INTO tbl_inventory (id,number,inventory,companyid) VALUES (?,?,?,?)")){p.setInt(1,3);p.setInt(2,3);p.setObject(3,inv);p.setInt(4,1);p.executeUpdate();legacy.commit();}
   new AccountingCoreStagingConverter().convert(legacy,target);
   var result=new StockDocumentStagingConverter().convert(legacy,target);
   assertThat(result.sourceFingerprint()).isEqualTo(result.destinationFingerprint());assertThat(result.documents()).isEqualTo(3);assertThat(result.rows()).isEqualTo(3);
   assertThat(count(target,"indelivery")).isEqualTo(1);assertThat(count(target,"outdelivery")).isEqualTo(1);assertThat(count(target,"inventory")).isEqualTo(1);assertThat(count(target,"inventory_row")).isEqualTo(1);}}
 @Test void rejectsInventoryKeyMismatch()throws Exception{try(Connection legacy=conn("stock_bad_legacy");Connection target=conn("stock_bad_target")){schema(legacy);new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).migrate(target,NormalizedSchemaMigrations.load());SSNewCompany c=new SSNewCompany();c.setId(1);insertCompany(legacy,c);new AccountingCoreStagingConverter().convert(legacy,target);SSInventory x=new SSInventory();x.setNumber(2);try(var p=legacy.prepareStatement("INSERT INTO tbl_inventory (id,number,inventory,companyid) VALUES (?,?,?,?)")){p.setInt(1,3);p.setInt(2,1);p.setObject(3,x);p.setInt(4,1);p.executeUpdate();legacy.commit();}assertThatThrownBy(()->new StockDocumentStagingConverter().convert(legacy,target)).isInstanceOf(SQLException.class).hasMessageContaining("key/object mismatch");}}
 private static Connection conn(String n)throws Exception{Class.forName("org.hsqldb.jdbcDriver");Connection c=DriverManager.getConnection("jdbc:hsqldb:mem:"+n+"_"+UUID.randomUUID(),"sa","");c.setAutoCommit(false);return c;}
 private static void schema(Connection c)throws Exception{try(InputStream in=StockDocumentStagingConverterTest.class.getResourceAsStream("/sql/create_tables.sql")){String sql=new String(Objects.requireNonNull(in).readAllBytes(),StandardCharsets.UTF_8);try(var p=c.createStatement()){for(String y:sql.split(";"))if(!y.isBlank())p.execute(y.trim());}c.commit();}}
 private static void insertCompany(Connection c,SSNewCompany x)throws Exception{try(var p=c.prepareStatement("INSERT INTO tbl_company (id,company) VALUES (?,?)")){p.setInt(1,x.getId());p.setObject(2,x);p.executeUpdate();c.commit();}}
 private static long count(Connection c,String t)throws Exception{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+t)){r.next();return r.getLong(1);}}
}
