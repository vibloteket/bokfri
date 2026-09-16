package org.fribok.bookkeeping.dataformat;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Freezes the Java-serialized persistence surface while the normalized schema is designed.
 *
 * <p>This is intentionally an inventory test, not approval of the legacy schema. A changed
 * {@code OTHER} column must be reflected in the migration design before this expectation changes.
 */
class LegacyPersistenceInventoryTest {
    private static final Pattern TABLE = Pattern.compile(
            "(?is)CREATE\\s+CACHED\\s+TABLE\\s+IF\\s+NOT\\s+EXISTS\\s+"
                    + "([a-z_]+)\\s*\\((.*?)\\)\\s*;");
    private static final Pattern OTHER_COLUMN = Pattern.compile(
            "(?i)\\b([a-z_]+)\\s+OTHER\\b");

    private static final Map<String, String> EXPECTED_OTHER_COLUMNS = Map.ofEntries(
            Map.entry("tbl_accountingyear", "accountingyear"),
            Map.entry("tbl_accountplan", "accountplan"),
            Map.entry("tbl_autodist", "autodist"),
            Map.entry("tbl_company", "company"),
            Map.entry("tbl_creditinvoice", "creditinvoice"),
            Map.entry("tbl_currency", "currency"),
            Map.entry("tbl_customer", "customer"),
            Map.entry("tbl_deliveryterm", "deliveryterm"),
            Map.entry("tbl_deliveryway", "deliveryway"),
            Map.entry("tbl_indelivery", "indelivery"),
            Map.entry("tbl_inpayment", "inpayment"),
            Map.entry("tbl_inventory", "inventory"),
            Map.entry("tbl_invoice", "invoice"),
            Map.entry("tbl_order", "iorder"),
            Map.entry("tbl_outdelivery", "outdelivery"),
            Map.entry("tbl_outpayment", "outpayment"),
            Map.entry("tbl_ownreport", "ownreport"),
            Map.entry("tbl_paymentterm", "paymentterm"),
            Map.entry("tbl_periodicinvoice", "periodicinvoice"),
            Map.entry("tbl_product", "product"),
            Map.entry("tbl_project", "project"),
            Map.entry("tbl_purchaseorder", "purchaseorder"),
            Map.entry("tbl_resultunit", "resultunit"),
            Map.entry("tbl_supplier", "supplier"),
            Map.entry("tbl_suppliercreditinvoice", "suppliercreditinvoice"),
            Map.entry("tbl_supplierinvoice", "supplierinvoice"),
            Map.entry("tbl_tender", "tender"),
            Map.entry("tbl_unit", "unit"),
            Map.entry("tbl_voucher", "voucher"),
            Map.entry("tbl_vouchertemplate", "vouchertemplate"));

    @Test
    void legacySchemaHasTheDocumentedSerializedObjectSurface() throws IOException {
        String schema;
        try (var input = getClass().getResourceAsStream("/sql/create_tables.sql")) {
            assertThat(input).as("legacy schema resource").isNotNull();
            schema = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        Map<String, String> actualOtherColumns = new LinkedHashMap<>();
        int tableCount = 0;
        Matcher tables = TABLE.matcher(schema);
        while (tables.find()) {
            tableCount++;
            Matcher columns = OTHER_COLUMN.matcher(tables.group(2));
            while (columns.find()) {
                assertThat(actualOtherColumns.put(tables.group(1), columns.group(1)))
                        .as("only one OTHER column in %s", tables.group(1))
                        .isNull();
            }
        }

        assertThat(tableCount).isEqualTo(31);
        assertThat(actualOtherColumns).containsExactlyInAnyOrderEntriesOf(EXPECTED_OTHER_COLUMNS);
        assertThat(actualOtherColumns).hasSize(30);
    }
}
