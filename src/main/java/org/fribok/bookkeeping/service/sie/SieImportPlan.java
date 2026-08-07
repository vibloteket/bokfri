package org.fribok.bookkeeping.service.sie;

import java.nio.file.Path;

/** Preflight summary for a SIE import. */
public record SieImportPlan(Path file, String type, boolean alreadyImported, int accounts,
                            int vouchers, int transactions, boolean vouchersOnly) {}
