# Workflow routing

Heavy checks use positive file lists rather than treating every non-website change as a Java change.
Keep these lists updated when adding a new build input.

| Change | Checks |
| --- | --- |
| `src/**`, Maven/wrapper, build/test scripts, packaging, design, data/db inputs, Checkstyle | Java CI on Linux/Windows/macOS |
| Invoice sources/tests, shared GUI utilities/top-level GUI classes, look-and-feel bootstrap/helpers, GUI resources | Invoice GUI regression |
| Popup/component sources/tests, shared GUI utilities/top-level GUI classes, look-and-feel bootstrap/helpers, GUI resources | Popup GUI regression |
| Forum Dockerfile, Docker ignore rules, Apache/PHP configuration, entrypoint, installer, Compose example or container test | Forum container |
| Forum theme CSS/HTML/JS/test files | Forum theme: Node tests and pinned Lightning CSS parser |
| `website/**` on `main` | Deploy Pages |
| Only README/changelog/docs, including `forum/theme/README.md` | No heavy build |
| A workflow's own YAML | That workflow |

Java CI keeps `workflow_dispatch` and release tags `v*`. GitHub does not apply push path filters to tag pushes,
so releases always run the existing full packaging/test/publish chain. Manual workflows also bypass path filters.
Ordinary relevant `main` pushes still produce the existing Java Dev packages. No release/package verification steps
were removed. Pages deployment behavior is unchanged; there is no new website PR validator in this change.

Forum workflows run on relevant PRs and `main` pushes, not pushes to the temporary forum feature branch.
This avoids building the same PR head twice. For Java/forum/GUI workflows, concurrency is grouped by workflow
and PR number (or ref for non-PR events). Only superseded PR runs are canceled: `main`, manual and tag/release
runs are not canceled by this policy. Pages retains its existing deployment concurrency policy.

## Pull-request scope and limits

PR path filters compare the **whole PR** against its base, not just the latest commit. A PR containing Docker
and theme changes therefore legitimately runs both checks when updated. A subsequent theme-only PR avoids
Java and container builds. Mixed application/forum changes run both areas. GitHub path filtering has a 300-file
evaluation limit; very large mixed PRs should be split or the affected workflows explicitly run manually.

## Required checks

Workflow path filtering can leave an excluded workflow's required status pending. Repository branch-protection
settings could not be read with the available token and were **not changed**. Before enforcing new required
checks, verify this routing against the repository rules: do not require every path-filtered workflow on every PR.
If an always-present required check is desired, add a separate always-running change classifier/aggregate gate;
that is not implemented here. Existing job names are preserved.

## Validation

- Parse/lint all workflow YAML with actionlint.
- Exercise file-routing fixtures: theme/docs/forum/app/shared-GUI/resource/packaging/website changes, plus mixed PRs,
  main pushes, release tags and manual dispatch.
- Theme tests run without dependencies or network: `node --test forum/theme/*.test.mjs`.
- Theme CSS is parsed by `lightningcss-cli@1.33.0`; it does not replace browser layout testing.
- Container CI retains installation, logo/WebP upload, HTTP, restart and backup/restore smoke tests.
