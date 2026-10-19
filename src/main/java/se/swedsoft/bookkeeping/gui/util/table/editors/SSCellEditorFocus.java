package se.swedsoft.bookkeeping.gui.util.table.editors;

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.JTextComponent;

/** Preserves the character that starts cell editing before native keyboard focus arrives. */
public final class SSCellEditorFocus {
    private static final Object GUARD_KEY = new Object();

    private SSCellEditorFocus() {
    }

    /**
     * Arms a field after its initial value/selection has been set for a new cell edit.
     * Aqua selects all text on focus gain, which otherwise selects an already forwarded
     * first character. Existing-value selection is left intact if no text was entered.
     * Call on the event dispatch thread.
     *
     * @param field the cell editor field
     */
    public static void prepare(JTextComponent field) {
        Guard guard = (Guard) field.getClientProperty(GUARD_KEY);
        if (guard == null) {
            guard = new Guard(field);
            field.putClientProperty(GUARD_KEY, guard);
        }
        guard.changed = false;
        guard.waiting = !field.isFocusOwner();
    }

    private static final class Guard extends FocusAdapter implements DocumentListener {
        private final JTextComponent field;
        private boolean waiting;
        private boolean changed;

        Guard(JTextComponent field) {
            this.field = field;
            field.addFocusListener(this);
            field.getDocument().addDocumentListener(this);
        }

        @Override
        public void focusGained(FocusEvent event) {
            if (waiting && changed) {
                // Runs after the Look & Feel caret's focus handler, not asynchronously:
                // no later keystroke or deliberate mouse selection can be overwritten.
                field.setCaretPosition(field.getDocument().getLength());
            }
            waiting = false;
        }

        @Override
        public void focusLost(FocusEvent event) {
            waiting = false;
        }

        @Override
        public void insertUpdate(DocumentEvent event) {
            changed |= waiting;
        }

        @Override
        public void removeUpdate(DocumentEvent event) {
            changed |= waiting;
        }

        @Override
        public void changedUpdate(DocumentEvent event) {
            // Attribute-only changes do not count as user input.
        }
    }
}
