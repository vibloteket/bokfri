package se.swedsoft.bookkeeping.gui.util.dialogs;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.util.List;

/** Presents structured validation problems consistently in Swing dialogs. */
public final class SSValidationDialog {
    private SSValidationDialog() {}

    /**
     * Shows all validation problems if the list is not empty.
     *
     * @param parent component that owns the message dialog
     * @param subject definite-form subject, for example {@code Fakturan}
     * @param messages validation messages
     * @return {@code true} when there are no validation problems
     */
    public static boolean showIfInvalid(Component parent, String subject, List<String> messages) {
        if (messages.isEmpty()) {
            return true;
        }
        JOptionPane.showMessageDialog(parent, formatMessages(messages),
                subject + " kan inte sparas", JOptionPane.WARNING_MESSAGE);
        return false;
    }

    /**
     * Adds row context to a validation message when available.
     *
     * @param row one-based row number, or {@code null} for a form-level problem
     * @param message validation message
     * @return message with optional row prefix
     */
    public static String formatIssue(Integer row, String message) {
        return row == null ? message : "Rad " + row + ": " + message;
    }

    /**
     * Formats validation problems for display.
     *
     * @param messages validation messages
     * @return one bullet-prefixed line per problem
     */
    public static String formatMessages(List<String> messages) {
        return messages.stream()
                .map(message -> "• " + message)
                .reduce((left, right) -> left + System.lineSeparator() + right)
                .orElse("");
    }
}
