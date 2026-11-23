# Explicit normalized document workspace

After explicit company/year Apply, a third [Manual voucher](normalized-manual-voucher.md) tab
supports new cent-balanced vouchers using active accounts and explicit company-scoped projects
and result units, including required-dimension and project closure checks. Its previews,
posting, stale checks and lifecycle share the owner and busy/uncertainty boundary; it never
changes the selected document context or supplies dimension defaults. Dimension catalogue
editing, general correction editing and full voucher-frame parity remain outside this restricted host.

A fourth [Voucher register](normalized-voucher-register.md) tab now reads stored journals in
explicit 100-header pages, then opens a selected SQL identity. It shows raw rows and separate
current catalogue labels without writes, inferred totals or automatic selection/refresh after
posting. Reads share the lease/gate and host busy/uncertainty boundary.

A separate fifth [Voucher reversal](normalized-voucher-reversal.md) tab adds exact standalone
reversals with explicit source SQL identity/date/description, preview and atomic reciprocal
correction links. Original rows stay unchanged; linked/ambiguous or already-corrected journals
are refused. This is not document unbooking or general correction editing.

A sixth [Financial reports](normalized-financial-reports.md) tab adds read-only trial balance
and account ledger with explicit inclusive dates and account selection. It combines stored
opening balances with prior/period entries, including both original and reversal journals,
without ambient row resolution, SQL writes, inferred balancing rows or PDF export. Generate
again explicitly after changes; pending reads share the same host/gate/uncertainty interlocks.

The selected company/year also exposes guarded [empty accounting-year creation](normalized-year-creation.md): explicit dates/template, preview and confirmation, with no automatic carry-forward or selection change. An existing company with zero years can use [first-year creation](normalized-first-year-creation.md) directly from its company choice, independently of any active document context. A separate [guarded empty-company command](normalized-company-creation.md) now provides explicit identity/currency/VAT/rounding preview and confirmation, followed by the separate first-year flow. It creates no accounting defaults or automatic document selection; full company-settings parity remains separate.

`org.fribok.bookkeeping.gui.application.NormalizedDocumentWorkspace` hosts the restricted
customer and supplier views behind the production format-3 guard. This is an opt-in,
dedicated-window integration surface, **not normal `Bookkeeping` startup or full GUI parity**.
New databases still use format 2; production startup still rejects format 3.

## Ownership and explicit selection

Construct the panel on the EDT with a dedicated caller-owned `JFrame` and an exclusively
adopted `NormalizedApplicationOwner`. The caller owns frame sizing/layout and disposal;
the workspace owns the application owner and its view/menu lifecycle. Do not retrofit this
panel into an already active legacy application window or share its application owner with
another selection controller. For an already explicitly migrated database, an opt-in caller can
create the host as follows (the factory must return fresh owned connections):

```java
SwingUtilities.invokeLater(() -> {
    var frame = new JFrame("Normalized document workspace");
    var application = new NormalizedApplicationOwner(connections, clock); // lazy; no JDBC here
    frame.setContentPane(new NormalizedDocumentWorkspace(frame, application));
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
    frame.setSize(1450, 900);
    frame.setVisible(true);
});
```

This example is not installed in normal application startup and performs no migration.

Catalogue loading and company/year validation run in background workers. No JDBC is opened
on the EDT. Both selectors are noneditable, carry typed identities rather than parsed labels,
and begin with explicit empty choices. Choosing a company resets the year to empty even if
only one year exists. No first entry is silently selected, and no persisted/legacy global
company/year selection is changed. Company names are displayed literally with quoted and
escaped controls, not interpreted as Swing HTML.

Only an explicit Apply starts selection. The old context remains visible while validation or
an accepted original-context write is pending. A failed choice keeps the old lease and views;
reload discards selector drafts, not the active context. Successful validation installs or
replaces **both** document view sessions from one owner lease. Their existing owner-scoped
menu bindings, local committed refresh callbacks and dialog fencing are reused, not replaced
with legacy cache operations. Stable document SQL IDs remain the action targets.

The panel exposes its immutable `selection()` and EDT-only `isBusy()` for explicit host
coordination. This does not introduce an application-wide cache/event bus or claim command
ownership for other domains.

## Uncertainty, replacement and close

A 100 ms Swing timer reads only the owner's in-memory uncertainty flag; it performs no JDBC,
DDL or migration. A failed commit from either family retires both view/menu families, clears
and disables their controls, disables both selectors and Apply/Reload, and presents a permanent
external-reconciliation warning. Retired installed menu routes remain consumed rather than
falling back to legacy storage. A pending load/selection failure cannot overwrite that stronger
warning. Neither reload nor reselect clears the application-level block.

The views and existing menu bindings already qualify committed callbacks by their active
selection; replacement retires old leases and dialogs. An accepted write keeps its original
company/year and can finish before a pending selection replacement. No write is retargeted
by changing selector drafts. A host-installation exception closes the owner rather than
leaving a partially active workspace.

`close()` must run on the EDT. It invalidates late background callbacks, stops the state timer,
retires both view/menu families and closes the adopted application owner. It does not dispose
the caller's frame, shut down HSQLDB or close accepted write connections. Disposing the frame
also closes the workspace via its window listener. The caller must close a panel removed
without disposing its window.

## Validation and scope

`NormalizedApplicationWorkspaceGuiTest` covers both real views, explicit/no-default choices,
literal names, scoped replacement, stale-year refusal with last-good context, reload-discarded
selector drafts, both families and both durability outcomes of unknown commit, consumed menu
routing and unbound-owner isolation, close during initial loading, accepted old-context writes,
late-failure warning precedence and legacy-format refusal without mutation. These are display
tests: headless skips are not evidence of GUI validation.

The three-platform CI display selector now includes `NormalizedApplication*Test`, covering the
shared owner tests, paired-view tests and workspace tests on the existing Linux Xvfb and
Windows/macOS display jobs. This closes the previous selector gap; actual platform run results
must still be checked rather than inferred from local display tests.

Still separate: production application bootstrap/menus for normal opening, company/year
creation and editing, register/voucher and remaining document-family workflows, broader cache
notifications, complete GUI backup/restore and end-to-end parity, default cutover and legacy
serialization retirement. G1–G4 and #75's technical definition of done remain unfinished.


## Guarded initial opening balances (2026-11-23)

The seventh opt-in workspace tab supports explicit initial balances for an empty selected
year only: active balance accounts, signed cent-exact amounts, balanced preview and separate
atomic confirmation. Any existing balance or journal blocks insertion; there is no
replacement, carry-forward, rounding, year-setting change or inferred balancing account.
Owner-bound single-use previews, stale-input checks, rollback and shared uncertain-commit
blocking apply. See [normalized-initial-opening-balances.md](normalized-initial-opening-balances.md)
for the workflow and boundaries. Format-3 normal startup stays guarded and format-2
creation remains unchanged. This does not complete G2/G3 or issue #75.


## Guarded balance-sheet and income-statement views (2026-11-23)

The existing financial-report tab also offers two explicit, read-only report kinds:

- **Balance sheet:** enter the inclusive **To** date; **From** is disabled and unused.
  Rows show signed debit-minus-credit balances from stored year opening and eligible
  entries through that date. Totals expose assets, liabilities/equity, current-year
  result and difference. Current result excludes opening amounts; the visible difference
  is assets minus liabilities/equity minus current result, not an accounting certification.
  No rounding or adjustment is applied to force a zero difference.
- **Income statement:** enter explicit inclusive **From/To** dates. Only period result-account
  movement is included, credit minus debit. Opening amounts and earlier entries are excluded.
  The period result is shown; there is no inferred comparison period or balancing adjustment.

Zero rows are omitted by the existing shared calculator. Current catalogue labels, inactive
accounts and eligible historical rows follow the same explicit-account resolution as trial
balance/ledger. Generate is explicit; changing inputs clears results without automatically
reading storage. Both kinds use the same owner lease, serializable read/rollback transaction,
background worker, stale-callback fencing, host interlock and uncertain-commit block.

No new tab, PDF/export UI, write command, legacy-frame integration, schema migration,
dimension filtering, backup/restore or default cutover is introduced. Format-3 normal
startup remains guarded and format-2 creation is unchanged. These views do not complete
G2/G3 or the technical definition of done for issue #75.
