# Bokfri forum

Independent Flarum 2 service; does not change the Java application or GitHub Pages website.
Flarum core **2.0.2**, SQLite, PHP **8.4**, Swedish language pack **2.0.4**.
Composer dependencies are committed in `app/composer.lock`; rebuilds use `composer install`, not update.
The PHP base image follows the supported 8.4/Bookworm patch stream; rebuild periodically for security fixes.
The upstream application skeleton is Flarum 2.0.0; its MIT license is retained in `app/LICENSE`.

## Requirements

- Docker Engine and Compose v2 on Linux (amd64 or arm64; native builds).
- Existing Caddy service in the **same Compose project/network**.
- Local filesystem for SQLite. A locally attached USB disk is fine; do not use NFS/SMB.
- DNS for `forum.bokfri.viblo.se` pointing to the server.
- SMTP service for verification/password-reset emails. Credentials are entered in Flarum administration, not Git.

No MariaDB, Redis, public container ports, or separate queue service is required. The initial installation
uses synchronous jobs and a small selection of bundled extensions; realtime/chat/GDPR extensions are not enabled.

## Deploy

1. Check out the branch containing this directory on your server. Copy the service from
   `compose.example.yml` into your existing Compose file. Set `build.context` to the **absolute path**
   to this directory, e.g. `/home/vb/bokfri/forum`.

2. Your Caddyfile needs this explicit site (port **80**, not 8000):

   ```caddyfile
   forum.bokfri.viblo.se {
       encode zstd gzip
       reverse_proxy flarum:80
   }
   ```

   Do not put Basic Auth in front of it. Flarum manages its own accounts. Do not publish a host port
   for Flarum: proxy headers are trusted from private-network peers. Other containers on that network
   must be trusted too. If using a custom Docker subnet, adapt `RemoteIPInternalProxy` in `apache.conf`.

3. From the directory containing your server's Compose file:

   ```sh
   docker compose build flarum
   docker compose run --rm flarum php install-forum.php
   ```

   The installer asks for your admin username, email and password (twice). The password is hidden in an
   interactive terminal and never passed as a command argument. Use at least 12 characters. It sets the
   public HTTPS URL, SQLite database, forum title and Swedish default locale, and enables Swedish and English.
   Installation runs as Apache's `www-data` user. There are **no default admin credentials**.

   An uninstalled service deliberately refuses to start Apache; it never exposes the public web installer.
   Installation refuses to overwrite existing configuration. If installation fails, inspect the error;
   do not delete data to retry unless this is a confirmed disposable, empty installation.

4. Start it:

   ```sh
   docker compose up -d flarum
   docker compose logs --tail=100 flarum
   ```

   Validate/reload Caddy using your usual procedure if its configuration has changed. If Caddy previously
   failed to reload because the `flarum` hostname did not exist, reload after the service has started.

5. Open `https://forum.bokfri.viblo.se` and sign in. In administration:

   - Configure SMTP and sender address; test delivery to a different email provider.
   - Verify a new user's registration email and password-reset flow before announcing registration.
   - Keep email confirmation required before posting; verify this with the test account.
   - Adjust posting/upload permissions and remove/relabel the default tags as desired.
   - Enable notifications on your account for new discussions using the available subscription options;
     verify delivery rather than assuming all new posts are emailed to the administrator.
   - Add a pinned Swedish welcome discussion and a reminder not to post sensitive accounting/customer data.

   No link is added to the GitHub Pages website automatically; add one once the forum works.

## Persistent data and permissions

All mutable state is under `/mnt/external/appdata/flarum` on the host, mounted as `/data`:

```text
config.php      generated configuration (contains the public URL)
forum.sqlite    database, including accounts, posts, settings and SMTP configuration
assets/         compiled assets and avatar/upload files
storage/        logs, caches, sessions and temporary files
```

Startup seeds missing runtime directories and assigns ownership to `www-data` (UID/GID 33 in this image).
Use a dedicated directory; do not mount your repository or unrelated data here. Keep this directory out of
Git, website roots and public shares. `FLARUM_BASE_URL` is an installer input, not a runtime URL override.

## Backup and restore

For this low-traffic forum, use a short **offline backup**. Stop the only writer before copying SQLite;
this also keeps uploads and database references consistent. Never blindly copy a live database file.
Run from the server's Compose directory (commands below may require sudo for host filesystem access):

```sh
mkdir -p /mnt/external/backups/bokfri-forum
chmod 700 /mnt/external/backups/bokfri-forum
docker compose stop flarum
# If tar fails, still restart Flarum and fix the backup error.
(umask 077; tar -C /mnt/external/appdata -czf "/mnt/external/backups/bokfri-forum/forum-$(date +%Y%m%d-%H%M%S).tar.gz" flarum)
docker compose start flarum
```

Copy successful backups **off this USB disk/server**. They contain private posts, credentials and personal data;
restrict access and encrypt offsite copies. Automate this once the initial deployment is verified.

To restore, stop Flarum, preserve the current data directory elsewhere, then extract a known-good backup into
`/mnt/external/appdata`. Restore the **entire** directory, including config and uploads. Start with the matching
image/source version, then verify login, posts and avatars. Startup repairs ownership. Do not extract over a running
or nonempty data directory; a rollback after migrations needs the matching pre-upgrade backup too.

## Updates

Do not install Composer extensions inside a running container: they disappear on rebuild. Update
`app/composer.json` and `app/composer.lock` together, check Flarum 2 compatibility, then test a rebuilt image.
Do not run `composer update` during production startup.

1. Take an offline backup and retain the current image/source revision for rollback.
2. Stop the service and build the updated image (`docker compose build --pull flarum`).
3. Run explicit maintenance while Apache is stopped:

   ```sh
   docker compose run --rm flarum php flarum migrate
   docker compose run --rm flarum php flarum assets:publish
   docker compose run --rm flarum php flarum cache:clear
   docker compose up -d flarum
   ```

4. Verify homepage, login, posting, uploads and email. If migration fails, leave Apache stopped and investigate.
   For rollback restore both the old image **and** its database/assets backup.

## Tests

```sh
docker build -t bokfri-forum:test forum
sh forum/test-image.sh bokfri-forum:test
```

Run these from the repository root. The smoke test creates disposable, uniquely named Docker resources;
it never uses the production data path. It checks the closed installer, real SQLite installation, Swedish homepage,
generated stylesheet, inaccessible private files, restart persistence, SQLite integrity, and offline backup/restore.
The `Forum container` GitHub Actions workflow runs these checks for forum changes. SMTP delivery and real Caddy/DNS
integration still require testing on the server. No server deployment is performed by CI.
