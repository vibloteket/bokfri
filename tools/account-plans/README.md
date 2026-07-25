# BAS account-plan generator

`generate_account_plans.py` is a standalone Python script with PEP 723 inline
dependency metadata. It downloads checksum-pinned BAS workbooks and generates
reviewable Bokfri `.xls` drafts plus Markdown/JSON reports.

## Run

```sh
uv run tools/account-plans/generate_account_plans.py
```

Use the local cache without network access:

```sh
uv run tools/account-plans/generate_account_plans.py --offline
```

Generated files are written to `tools/account-plans/generated/2026/`. Neither
the downloaded cache nor generated review drafts are committed.

## Install after review

```sh
uv run tools/account-plans/generate_account_plans.py --offline --install
```

`--install` replaces the packaged `.xls` defaults only when no generator errors
remain. Warnings still require human review, especially VAT and ambiguous SRU
mappings. After installation, update `SSDB.checkImportDefaultAccountPlans()` and
run the Java import/integration tests before committing.

## Annual update

1. Update `YEAR`, `SOURCES`, checksums, and plan names in the script.
2. Run the generator without `--install`.
3. Review `review.md`, `review.json`, and the generated `.xls` files.
4. Resolve mappings that cannot be represented automatically.
5. Install, test in Bokfri, and commit the resulting packaged defaults.

The free BAS account workbook supplies account numbers/names. Separate official
BAS SRU workbooks supply declaration mappings. VAT codes are conservatively
inherited from old Bokfri plans only when all old plans agree for that account;
this is intentionally not treated as authoritative.