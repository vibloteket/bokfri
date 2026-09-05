package org.fribok.bookkeeping.service.spreadsheet;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import se.swedsoft.bookkeeping.data.SSAccount;
import se.swedsoft.bookkeeping.data.SSAccountPlan;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountPlanSpreadsheetServiceTest {
    @TempDir
    Path temporaryDirectory;

    private final AccountPlanSpreadsheetService service = new AccountPlanSpreadsheetService();

    @Test
    void readsExistingBokfriXlsFixture() throws Exception {
        Path fixture = Path.of("src/main/resources/account/default/BAS-2026---Aktiebolag.xls");

        SSAccountPlan plan = service.read(fixture);

        assertThat(plan.getName()).isEqualTo("BAS 2026 - Aktiebolag");
        assertThat(plan.getType().toString()).isEqualTo("EUBAS97");
        assertThat(plan.getAssessementYear()).isEqualTo("2026");
        assertThat(plan.getAccounts()).hasSizeGreaterThan(1_000);
        assertThat(plan.getAccounts()).anySatisfy(account -> {
            assertThat(account.getNumber()).isEqualTo(1010);
            assertThat(account.getDescription()).contains("Utvecklingsutgifter");
        });
    }

    @Test
    void xlsRoundTripPreservesAccountPlanData() throws Exception {
        SSAccountPlan source = new SSAccountPlan("ÅÄÖ Konsultplan");
        source.setType("EUBAS97");
        source.setAssessementYear("2026");
        SSAccount account = new SSAccount(3011);
        account.setDescription("Försäljning tjänster 25 %");
        account.setVATCode("05");
        account.setSRUCode("7410");
        account.setReportCode("R1");
        source.addAccount(account);
        Path file = temporaryDirectory.resolve("kontoplan.xls");

        service.write(source, file, false);
        SSAccountPlan imported = service.read(file);

        assertThat(Files.readAllBytes(file)).startsWith((byte) 0xd0, (byte) 0xcf, (byte) 0x11,
                (byte) 0xe0);
        assertThat(imported.getName()).isEqualTo(source.getName());
        assertThat(imported.getType().toString()).isEqualTo(source.getType().toString());
        assertThat(imported.getAssessementYear()).isEqualTo(source.getAssessementYear());
        assertThat(imported.getAccounts()).singleElement().satisfies(actual -> {
            assertThat(actual.getNumber()).isEqualTo(3011);
            assertThat(actual.getDescription()).isEqualTo(account.getDescription());
            assertThat(actual.getVATCode()).isEqualTo("05");
            assertThat(actual.getSRUCode()).isEqualTo("7410");
            assertThat(actual.getReportCode()).isEqualTo("R1");
        });
    }

    @Test
    void exportDoesNotOverwriteByDefault() throws Exception {
        Path file = temporaryDirectory.resolve("existing.xls");
        Files.writeString(file, "keep");

        assertThatThrownBy(() -> service.write(new SSAccountPlan("Test"), file, false))
                .isInstanceOf(java.nio.file.FileAlreadyExistsException.class);
        assertThat(Files.readString(file)).isEqualTo("keep");
    }
}
