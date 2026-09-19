package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAutoDist;
import se.swedsoft.bookkeeping.data.SSAutoDistRow;
import se.swedsoft.bookkeeping.data.SSNewCompany;
import se.swedsoft.bookkeeping.data.SSNewProject;
import se.swedsoft.bookkeeping.data.SSNewResultUnit;
import se.swedsoft.bookkeeping.data.SSVoucherTemplate;
import se.swedsoft.bookkeeping.data.SSVoucherTemplate.SSVoucherTemplateRow;

import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountingTemplatesStagingConverterTest {

    @Test
    void roundTripsOrderedTemplateAndDistributionRows() throws Exception {
        try(Connection legacy=connection("templates_legacy");Connection target=connection("templates_target")){
            createLegacySchema(legacy);new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).migrate(target,NormalizedSchemaMigrations.load());
            SSNewCompany company=new SSNewCompany();company.setId(1);company.setName("Example");insert(legacy,"INSERT INTO tbl_company (id,company) VALUES (?,?)",1,company);
            new AccountingCoreStagingConverter().convert(legacy,target);
            SSNewProject project=new SSNewProject("P1","Project",null);insertScoped(legacy,"tbl_project","project",project.getNumber(),project,1);
            SSNewResultUnit unit=new SSNewResultUnit("R1","Unit");insertScoped(legacy,"tbl_resultunit","resultunit",unit.getNumber(),unit,1);
            new AccountingDimensionsStagingConverter().convert(legacy,target);

            SSVoucherTemplate template=new SSVoucherTemplate();template.setDescription("Periodisering");template.setLocalDateTime(LocalDateTime.of(2026,3,29,2,30));
            SSVoucherTemplateRow debit=new SSVoucherTemplateRow();debit.setAccountNr(1930);debit.setDebet(BigDecimal.ZERO);
            SSVoucherTemplateRow credit=new SSVoucherTemplateRow();credit.setAccountNr(null);credit.setCredit(BigDecimal.ZERO);
            template.getRows().add(debit);template.getRows().add(credit);insertScoped(legacy,"tbl_vouchertemplate","vouchertemplate",template.getDescription(),template,1);

            SSAutoDist distribution=new SSAutoDist();distribution.setAccountNumber(4010);distribution.setDescrition("");distribution.setAmount(new BigDecimal("100.00"));
            SSAutoDistRow first=new SSAutoDistRow();first.setAccountNr(5010);first.setDescription("A");first.setPercentage(new BigDecimal("50.0"));first.setDebet(new BigDecimal("10.00"));first.setProjectNr("P1");first.setResultUnitNr("R1");
            SSAutoDistRow second=new SSAutoDistRow();second.setAccountNr(null);second.setCredit(new BigDecimal("10.0"));distribution.getRows().add(first);distribution.getRows().add(second);
            try(var s=legacy.prepareStatement("INSERT INTO tbl_autodist (id,number,autodist,companyid) VALUES (?,?,?,?)")){s.setInt(1,7);s.setInt(2,4010);s.setObject(3,distribution);s.setInt(4,1);s.executeUpdate();legacy.commit();}

            var result=new AccountingTemplatesStagingConverter().convert(legacy,target);
            assertThat(result.sourceFingerprint()).isEqualTo(result.destinationFingerprint());assertThat(result.templates()).isEqualTo(1);assertThat(result.templateRows()).isEqualTo(2);assertThat(result.distributions()).isEqualTo(1);assertThat(result.distributionRows()).isEqualTo(2);assertThat(result.daylightSavingGapAdjustments()).isEqualTo(1);
            assertThat(count(target,"voucher_template_row")).isEqualTo(2);assertThat(count(target,"auto_distribution_row")).isEqualTo(2);
        }
    }

    @Test
    void rejectsLegacyScalarAndObjectKeyMismatch() throws Exception {
        try(Connection legacy=connection("templates_bad_legacy");Connection target=connection("templates_bad_target")){
            createLegacySchema(legacy);new SchemaMigrationRunner(Clock.fixed(Instant.EPOCH,ZoneOffset.UTC)).migrate(target,NormalizedSchemaMigrations.load());SSNewCompany company=new SSNewCompany();company.setId(1);insert(legacy,"INSERT INTO tbl_company (id,company) VALUES (?,?)",1,company);new AccountingCoreStagingConverter().convert(legacy,target);
            SSVoucherTemplate template=new SSVoucherTemplate();template.setDescription("object-key");insertScoped(legacy,"tbl_vouchertemplate","vouchertemplate","table-key",template,1);
            assertThatThrownBy(()->new AccountingTemplatesStagingConverter().convert(legacy,target)).isInstanceOf(java.sql.SQLException.class).hasMessageContaining("key/object mismatch");
        }
    }

    private static Connection connection(String name)throws Exception{Class.forName("org.hsqldb.jdbcDriver");Connection c=DriverManager.getConnection("jdbc:hsqldb:mem:"+name+"_"+UUID.randomUUID(),"sa","");c.setAutoCommit(false);return c;}
    private static void createLegacySchema(Connection c)throws Exception{try(InputStream in=AccountingTemplatesStagingConverterTest.class.getResourceAsStream("/sql/create_tables.sql")){assertThat(in).isNotNull();String sql=new String(in.readAllBytes(),StandardCharsets.UTF_8);try(var s=c.createStatement()){for(String p:sql.split(";"))if(!p.isBlank())s.execute(p.trim());}c.commit();}}
    private static void insert(Connection c,String sql,int id,Object value)throws Exception{try(var s=c.prepareStatement(sql)){s.setInt(1,id);s.setObject(2,value);s.executeUpdate();c.commit();}}
    private static void insertScoped(Connection c,String table,String column,String key,Object value,int company)throws Exception{try(var s=c.prepareStatement("INSERT INTO "+table+" ("+(table.equals("tbl_vouchertemplate")?"name":"number")+","+column+",companyid) VALUES (?,?,?)")){s.setString(1,key);s.setObject(2,value);s.setInt(3,company);s.executeUpdate();c.commit();}}
    private static long count(Connection c,String table)throws Exception{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM "+table)){r.next();return r.getLong(1);}}
}
