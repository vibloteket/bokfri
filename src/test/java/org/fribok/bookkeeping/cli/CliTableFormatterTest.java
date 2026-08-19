package org.fribok.bookkeeping.cli;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.fribok.bookkeeping.cli.CliTableFormatter.Column.left;
import static org.fribok.bookkeeping.cli.CliTableFormatter.Column.right;

class CliTableFormatterTest {
    @Test
    void alignsTextAndNumericColumnsUsingContentWidths() {
        String result = CliTableFormatter.format(List.of(
                        Map.of("number", 1, "description", "Short", "amount", "9.00"),
                        Map.of("number", 120, "description", "A longer description", "amount", "1250.00")),
                "No rows", right("Number", "number"), left("Description", "description"),
                right("Amount", "amount"));

        assertThat(result).isEqualTo("""
                Number  Description            Amount
                     1  Short                    9.00
                   120  A longer description  1250.00""");
    }

    @Test
    void replacesLineBreaksAndHandlesNullCells() {
        String result = CliTableFormatter.format(
                List.of(new java.util.LinkedHashMap<>(Map.of("number", 1, "description", "Two\nlines"))),
                "No rows", right("Number", "number"), left("Description", "description"),
                left("Email", "email"));

        assertThat(result).isEqualTo("""
                Number  Description  Email
                     1  Two lines""");
    }

    @Test
    void alignsVoucherDebitCreditAndTotalWithLongDescriptions() {
        String result = BokfriCli.voucherRowsText(List.of(
                Map.of("account", "1510", "description", "Kundfordringar",
                        "debit", "24375.00", "credit", ""),
                Map.of("account", "2611",
                        "description", "Utgående moms på försäljning inom Sverige, 25 %",
                        "debit", "", "credit", "4875.00"),
                Map.of("account", "3001", "description", "Försäljning inom Sverige, 25 % moms",
                        "debit", "", "credit", "19500.00"),
                Map.of("account", "Total", "description", "",
                        "debit", "24375.00", "credit", "24375.00")));

        assertThat(result.lines().map(String::stripTrailing).toList()).containsExactly(
                "Account  Description                                         Debit    Credit",
                "1510     Kundfordringar                                   24375.00",
                "2611     Utgående moms på försäljning inom Sverige, 25 %             4875.00",
                "3001     Försäljning inom Sverige, 25 % moms                        19500.00",
                "Total                                                     24375.00  24375.00");
        String[] lines = result.split("\\R");
        assertThat(lines[1].indexOf("24375.00")).isEqualTo(lines[4].indexOf("24375.00"));
        assertThat(lines[2].indexOf("4875.00") + "4875.00".length())
                .isEqualTo(lines[0].indexOf("Credit") + "Credit".length());
        assertThat(lines[3].indexOf("19500.00") + "19500.00".length())
                .isEqualTo(lines[4].lastIndexOf("24375.00") + "24375.00".length());
    }

    @Test
    void returnsEmptyMessageWithoutHeaders() {
        assertThat(CliTableFormatter.format(List.of(), "No rows", left("Name", "name")))
                .isEqualTo("No rows");
    }
}
