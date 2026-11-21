# Guarded empty-company creation

The [opt-in normalized workspace](normalized-document-workspace.md) can create an empty company
without a current document lease. It then offers the existing explicit
[first-year workflow](normalized-first-year-creation.md). This is a restricted G2 onboarding
path, not production bootstrap, company-settings parity or completion of #75.

## Explicit workflow

1. Load the **stored** currency catalogue. There is no default currency, VAT period or
   rounding choice. An empty catalogue produces an actionable refusal; no fallback catalogue
   is seeded from legacy/static defaults.
2. Enter a name and optional corporate ID/VAT number; choose currency, VAT period (1, 3 or
   12 months) and rounding yes/no explicitly. Blank legal identifiers become SQL null.
3. Preview. The read-only summary shows the normalized name, legal identifiers and all
   chosen settings, plus empty-company/no-accounting-defaults/no-context-change semantics.
4. Confirm once. The owner rechecks the exact company identity catalogue (IDs/names) and
   selected currency code/description in a serializable transaction. Any changed identity
   catalogue or currency invalidates the preview; a new preview is required.
5. The new company appears in the refreshed company choices with **zero years**. Existing
   document context/views and saved selections stay unchanged. The user must enter first-year
   dates/template, preview and confirm separately, then explicitly Apply the new company/year.

Company creation and first-year creation are separate atomic operations. Failure of the
second leaves the successfully created empty company available for explicit retry; it does
not erase or silently recreate it.

## Storage and ownership

`NormalizedApplicationOwner.companyCurrencies()`, `previewCompany(CompanyDraft)` and
`createCompany(CompanyPreview)` share the existing document/year writer gate and uncertain-
commit boundary. Tokens are private-owner-bound and single-use once accepted, even after
an ordinary transaction failure. Changing document selection cannot redirect the draft.

Creation adds only one `company` row: explicit identity/settings and a global max-plus-one
legacy ID (zero for an empty catalogue, checked overflow). Names are trimmed and must be
nonblank/unique by exact case-sensitive name, matching the existing CLI policy. Schema
lengths are validated before preview. Currency identity must already exist; unused exchange
rates are neither read into the company settings nor changed and do not invalidate preview.

No years, accounts, balances, default-account mappings, registers, documents, address copies,
standard texts, payment terms, unit defaults, numbering rows or other company data are copied
or seeded. Unspecified fields, including tax rates, remain SQL null. The command uses a narrow
explicit insert instead of legacy company getters that silently supply 25/12/6 tax rates.
This deliberately empty company is **not** a claim of complete company configuration or
production-ready financial defaults; broader settings workflows remain separate.

Late insert failures roll back the whole operation. A thrown commit attempt, including
unchecked failure and either durability outcome, blocks all company/year/document commands
until external reconciliation and a new owner. A confirmed commit followed by a close failure
returns success with a warning, not a retryable result. Accepted work retains its draft after
closure; closed UI callbacks cannot install or select it.

All JDBC runs off the EDT. Host, company and year commands use state-only busy interlocks;
pending writes disable context reload/selection and other command input. Edits invalidate
confirmation. Closing remains possible and fences callbacks without cancelling accepted writes.

## Verification

- `NormalizedApplicationCompanyCreationTest`: read-only preview, every pre-existing SQL row
  preserved and only one new company row, null unset fields, exact chosen settings, ignored
  currency rates, unchanged document lease, empty-ID allocation and explicit first year,
  invalid/duplicate inputs, stale/concurrent creation, foreign/closed owners, late rollback/
  retry, known-commit close failure, uncertain commits and accepted completion after closure.
  Creation is exercised with legacy JDBC blocked.
- `NormalizedApplicationCompanyCreationGuiTest`: complete empty-catalogue company + first
  year + explicit Apply, unchanged existing views/context, edit invalidation, duplicate/stale
  refusal, pending-write interlock, uncertainty on both durability sides, closure fencing
  and missing-currency refusal. Both names match `NormalizedApplication*Test` in GUI CI.

Production format-3 startup remains rejected; new databases still use format 2. Full company
settings, other command families, carry-forward, backup/restore, parity gates and serialization
retirement remain in the [cutover checklist](normalized-cutover-checklist.md).
