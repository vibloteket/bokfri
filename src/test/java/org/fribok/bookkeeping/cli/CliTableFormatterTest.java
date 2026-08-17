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
    void returnsEmptyMessageWithoutHeaders() {
        assertThat(CliTableFormatter.format(List.of(), "No rows", left("Name", "name")))
                .isEqualTo("No rows");
    }
}
