package org.fribok.bookkeeping.service.sie;

import java.nio.file.Path;

/** Preflight summary for a SIE import. */
public record SieImportPlan(Path file, String type, boolean sourceMarkedImported,
                            boolean previouslyImported, String sha256, int accounts,
                            int vouchers, int transactions, boolean vouchersOnly) {}
