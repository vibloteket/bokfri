# Explicit VAT settlement dimension preservation

The explicit field-total settlement planner reverses exact **account / raw project /
raw result-unit** groups, rather than collapsing an entire VAT account. Different
groups survive even when their account nets to zero. A single group that cancels
exactly needs no clearing row.

This is dimension preservation, not dimension allocation or posting approval.
Compatibility reports/plans and declaration field amounts are unchanged. Control
still equals signed declared box 49; rounding still reconciles exact clearing to
that control. Control and rounding rows remain dimensionless: no source key is
inferred for them. If their accounts require dimensions, diagnostics still report
that unmet requirement.

## Exact grouping

Only the existing eligible tax-field source rows are grouped: inclusive dated
period, selected known tax accounts, non-crossed rows, at least one nonzero side.
Tax-base-only accounts, unknown accounts, zero/empty rows, undated and outside-period
vouchers are excluded. No project/unit resolution, trimming, repair, SQL or ambient
legacy lookup is introduced. Historical inactive-account eligibility is unchanged.

The grouping key is a typed triple, not concatenated strings. Null, empty,
whitespace and delimiter-containing keys remain distinct. Each group accumulates
exact credit-minus-debit; its clearing reverses that amount with the same raw keys.
Order is selected-account order, then first eligible occurrence of each pair.

`VatSettlementGroups` shares eligibility and exact raw-key grouping between the
explicit planner and its read-only diagnostics. The latter compare expected group
reversals with actual proposed debit-minus-credit totals for the same full triple.
Missing or different amounts retain original source voucher/one-based row references
and non-null raw dimension keys. Equal account totals or matching project names
alone cannot hide a missing pair. An exactly cancelling group needs no row and
produces no loss finding. Required null/blank dimensions still fail required-field
observations even when faithfully carried.

## Frozen preview and PDF

`VatSettlementRow` copies raw project/result-unit strings along with account,
description and exact debit/credit. No mutable row or account escapes. The same
rollback-only owner-held read captures rows and diagnostics; no posting token,
assigned number, confirmation flag or writer is exposed.

The preview appends two literal raw-key columns. Quoting/escaping distinguishes
null, blank and whitespace without HTML interpretation. PDF export freezes exactly
those displayed columns and warnings; later postings do not recalculate the PDF
or open SQL. The proposal remains **NOT POSTED**, and no findings does **not** mean
ready to post.

## Proof and boundaries

Ten focused pure tests cover zero-net distinct groups, exact repeated-pair sums,
null/blank/whitespace/delimiter collisions, same-pair cancellation, dimensionless
refund control/rounding, altered keys, swapped amounts, cancellation isolation and
required flags. Existing strict-getter tests still reject ambient resolution;
deliberately stripped proposals still exercise source-loss observations. SQL tests
verify real stored zero-net pairs, isolation and rollback-only reads. GUI/PDF tests
verify both raw columns, the frozen old export after later posting and no SQL on
export. Existing no-dimension amounts and compatibility expectations remain.

Ownership, closure, source freshness, duplicate/overlap periods, locks, full
bookability, owner-controlled confirmation, numbering and atomic/uncertain-commit
handling remain unfinished posting gates. No posting UI/API, schema, CLI, default
cutover, startup-guard removal or issue #75 completion is delivered here.
