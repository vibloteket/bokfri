# Workflow routing

Component filters keep forum/website changes separate from Java. Both forum workflows intentionally use
`forum/**`, including theme and documentation: fewer special cases, and new forum files are covered automatically.
Java/GUI input lists still need updating when introducing a new application build input.

| Change | Checks |
| --- | --- |
| `src/**`, Maven/wrapper, build/test scripts, packaging, design, data/db inputs, Checkstyle | Java CI on Linux/Windows/macOS |
| Invoice sources/tests, shared GUI utilities/top-level GUI classes, look-and-feel bootstrap/helpers, GUI resources | Invoice GUI regression |
| Popup/component sources/tests, shared GUI utilities/top-level GUI classes, look-and-feel bootstrap/helpers, GUI resources | Popup GUI regression |
| `forum/**`, including `forum/theme/**` and forum documentation | Forum container + Forum theme (Node tests and pinned Lightning CSS parser) |
| `website/**` | Shared website/forum navigation tests and CSS parsing; deploy Pages on `main` |
| Root `README.md` | Shared website/forum navigation/contact tests and CSS parsing; no heavy build |
| Only changelog/other docs outside `forum/` | No heavy build |
| A workflow's own YAML | That workflow |

Java CI keeps `workflow_dispatch` and release tags `v*`. GitHub does not apply push path filters to tag pushes,
so releases always run the existing full packaging/test/publish chain. Manual workflows also bypass path filters.
Ordinary relevant `main` pushes still produce the existing Java Dev packages. No release/package verification steps
were removed. Pages deployment behavior is unchanged. The Forum theme workflow also validates shared
navigation/contact text and both stylesheets for website and root README changes.

Forum workflows run on relevant PRs and `main` pushes, not pushes to the temporary forum feature branch.
This avoids building the same PR head twice. For Java/forum/GUI workflows, concurrency is grouped by workflow
and PR number (or ref for non-PR events). Only superseded PR runs are canceled: `main`, manual and tag/release
runs are not canceled by this policy. Pages retains its existing deployment concurrency policy.

## Pull-request scope and limits

PR path filters compare the **whole PR** against its base, not just the latest commit. Forum-only PRs run
both forum checks, even for just theme/docs changes, but do not compile Java. This small amount of extra work
is intentional to keep routing easy to maintain. Mixed application/forum changes run both areas. GitHub path filtering has a 300-file
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
- Forum and website CSS are parsed by `lightningcss-cli@1.33.0`; this does not replace browser layout testing.
- Container CI retains installation, logo/WebP upload, HTTP, restart and backup/restore smoke tests.
