# Forum theme — source of truth

These files version the manually configured Flarum theme. They are **not applied automatically**
and are not copied into the runtime image. Building/restarting the container never overwrites admin settings.
Flarum stores the applied values in its database; keep backing up the complete `/data` directory.

## Apply manually

In **Administration → Appearance**, replace the entire content of each field with the corresponding file:

| Flarum field | File |
| --- | --- |
| Custom Header | `header.html` |
| Custom Footer | `footer.html` |
| Custom CSS / LESS | `custom.css` |

Save the settings and refresh the forum. Replace, do not append: these files supersede the previous return-link
strip and standalone theme. Do not add another GoatCounter script alongside the footer.

Make future changes here first, review the diff, then paste the updated field(s) into administration.
To roll back, restore the files from a known-good Git commit and paste those versions into the same fields.
This is a manual configuration source, not an export of all current database settings.

## Branding and navigation

- Header reuses the website logo and Hem / Ladda ner / Hjälp links, with absolute URLs.
- The logo is loaded from `https://bokfri.viblo.se/assets/logo.svg`; changes to that website asset affect this header.
- Footer reuses © Bokfri / GPLv3. The CSS supplies the cream/teal/sage theme and centered 1200px frame.
- Native forum navigation remains below the website header. Mobile offsets preserve drawer access.
- Nothing here edits the GitHub Pages website or adds its pending Forum navigation link.
- Flarum's uploaded logo/favicon are separate assets persisted under `/data/assets`, not embedded in these files.

Other agreed settings, entered separately under **Administration → Basics**:

- Forum title: `Bokfri forum`
- Welcome title: `Välkommen till Bokfri forum`
- Welcome message: `Här kan du fråga om Bokfri, diskutera bokföring och lämna förslag.`

These are desired defaults, not instructions to overwrite any newer welcome text or user content.
No accounts, posts, permissions, SMTP passwords or other secrets belong in this directory.

## GoatCounter

`footer.html` includes tracking for `https://bokfri.goatcounter.com/count`, sharing the website's analytics
but prefixing paths with `/forum`. It handles SPA History API navigation and back/forward, deduplicates
unchanged routes, and groups discussion slugs/post numbers under `/forum/d/<numeric-id>`.
Titles are deliberately generic (`Bokfri forum`).

Only explicitly allowed public route shapes are counted: index/all, tags, following, individual tags and discussions.
Account, profile, administration, confirmation/reset and unknown routes are excluded. This is a route allowlist,
not a Flarum permission check: don't extend it to private categories without reviewing what analytics should collect.
Query strings/hashes are not sent. GoatCounter also includes `location.search` in a separate `q` payload field:
the footer sanitizes its `get_data` result to clear that field and reduce referring URLs to their origin.
Keep this sanitization when upgrading the integration. Standard GoatCounter visitor exclusions still apply.
The optional external script is loaded asynchronously; a blocked counter must not prevent forum use.

## Automated checks

From the repository root:

```sh
node --test forum/theme/*.test.mjs
```

Tests exercise the footer in an isolated browser model: no network calls, credentials or production analytics hits.
They cover History API/SPA navigation, grouping/deduplication, private-route exclusions and payload sanitization.
The `Forum theme` workflow also parses the CSS with a pinned Lightning CSS CLI. Any `forum/**` change runs
both forum checks (theme and container), but not Java; see [workflow routing](../../.github/WORKFLOWS.md). These checks do not replace the
browser/layout checks below.

## Verify after applying

- Desktop: centered 1200px frame and visible footer; narrow viewports: no horizontal overflow/clipped links.
- Test header website links, native drawer/search/login, scrolling and composing a discussion.
- Check light/dark modes and normal plus long discussions; dialogs must remain usable.
- In browser developer tools, check analytics requests: `/forum/...` paths, no query strings/tokens,
  one count per logical route, no extra visit for scrolling to a different post in one discussion.
- Test back/forward and excluded account pages. Blocking GoatCounter should not break navigation.

Browser checks covered desktop/tablet/320–390px, login/drawer, scrolling, and the real GoatCounter
script with requests intercepted (no test hits sent to production). Discussion-list pane regression checks
navigate from the index into a real discussion (direct navigation does not create the pane): closed, hover,
pin/unpin and scroll at 768/1024/1280/1366/1440/1920px, plus mobile drawer/login. The theme explicitly hides
the closed pane so it cannot leak into the centered frame's outer gutter; affixed panels are aligned to the
frame below both headers. Authenticated composer behavior still needs checking on the deployed forum. Do not use private account/token URLs as analytics test values
against the real endpoint; intercept the requests when testing sanitization.
