package org.fribok.bookkeeping.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Formats human-readable CLI tables with content-sized, aligned columns. */
final class CliTableFormatter {
    enum Alignment { LEFT, RIGHT }

    record Column(String heading, String key, Alignment alignment) {
        static Column left(String heading, String key) {
            return new Column(heading, key, Alignment.LEFT);
        }

        static Column right(String heading, String key) {
            return new Column(heading, key, Alignment.RIGHT);
        }
    }

    private CliTableFormatter() {}

    static String format(List<? extends Map<String, ?>> rows, String emptyMessage,
            Column... columns) {
        if (rows.isEmpty()) {
            return emptyMessage;
        }
        int[] widths = new int[columns.length];
        List<List<String>> values = new ArrayList<>(rows.size());
        for (int index = 0; index < columns.length; index++) {
            widths[index] = columns[index].heading().length();
        }
        for (Map<String, ?> row : rows) {
            List<String> formattedRow = new ArrayList<>(columns.length);
            for (int index = 0; index < columns.length; index++) {
                String value = cell(row.get(columns[index].key()));
                formattedRow.add(value);
                widths[index] = Math.max(widths[index], value.length());
            }
            values.add(formattedRow);
        }

        StringBuilder result = new StringBuilder();
        appendRow(result, java.util.Arrays.stream(columns).map(Column::heading).toList(),
                widths, columns);
        for (List<String> row : values) {
            result.append('\n');
            appendRow(result, row, widths, columns);
        }
        return result.toString().stripTrailing();
    }

    private static void appendRow(StringBuilder result, List<String> values, int[] widths,
            Column[] columns) {
        for (int index = 0; index < columns.length; index++) {
            if (index > 0) {
                result.append("  ");
            }
            String value = values.get(index);
            int padding = widths[index] - value.length();
            if (columns[index].alignment() == Alignment.RIGHT) {
                result.append(" ".repeat(padding));
            }
            result.append(value);
            if (columns[index].alignment() == Alignment.LEFT && index < columns.length - 1) {
                result.append(" ".repeat(padding));
            }
        }
    }

    private static String cell(Object value) {
        return Objects.toString(value, "").replace('\r', ' ').replace('\n', ' ');
    }
}
