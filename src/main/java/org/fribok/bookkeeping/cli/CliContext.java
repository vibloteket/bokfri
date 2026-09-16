package org.fribok.bookkeeping.cli;

import java.io.PrintWriter;

/** Output and help location shared with a selected command instance. */
record CliContext(PrintWriter out, PrintWriter err, CliCommandTree node) {
}
