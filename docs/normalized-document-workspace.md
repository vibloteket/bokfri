# Explicit normalized document workspace

The selected company/year now also exposes guarded [empty accounting-year creation](normalized-year-creation.md): explicit dates/template, preview and confirmation, with no automatic carry-forward or selection change. Creating a first company/year remains outside this opt-in host.

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
