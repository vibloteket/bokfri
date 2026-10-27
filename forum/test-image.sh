#!/bin/sh
# Build first: docker build -t bokfri-forum:test forum
# Then: forum/test-image.sh bokfri-forum:test
set -eu
image=${1:-bokfri-forum:test}
name="bokfri-forum-test-$$"
volume="$name-data"
work=$(mktemp -d)
cleanup() {
    docker rm -f "$name" >/dev/null 2>&1 || true
    docker volume rm "$volume" >/dev/null 2>&1 || true
    rm -rf "$work"
}
trap cleanup EXIT HUP INT TERM
docker volume create "$volume" >/dev/null
# Public installation must fail closed, not expose the web installer.
if docker run --rm -v "$volume:/data" "$image" >"$work/uninstalled.log" 2>&1; then
    echo 'ERROR: uninstalled forum started' >&2
    exit 1
fi
grep -q 'Forum is not installed' "$work/uninstalled.log"
printf '%s\n' testadmin admin@example.com 'test-password-for-ci-only' 'test-password-for-ci-only' |
    docker run --rm -i -v "$volume:/data" \
        -e FLARUM_BASE_URL=https://forum.bokfri.viblo.se \
        "$image" php install-forum.php
# Installer must not overwrite an existing installation.
if docker run --rm -v "$volume:/data" "$image" php install-forum.php; then
    echo 'ERROR: installer accepted an existing installation' >&2
    exit 1
fi
docker run -d --name "$name" -p 127.0.0.1::80 -v "$volume:/data" "$image" >/dev/null
port=$(docker port "$name" 80/tcp | sed 's/.*://')
url="http://127.0.0.1:$port"
fetch() {
    curl --fail --silent --show-error \
        --header 'Host: forum.bokfri.viblo.se' --header 'X-Forwarded-Proto: https' "$@"
}
i=0
until fetch "$url/" >"$work/home.html"; do
    i=$((i + 1))
    if [ "$i" -ge 30 ]; then docker logs "$name"; exit 1; fi
    sleep 2
done
grep -q 'Bokfri forum' "$work/home.html"
grep -q 'lang="sv"' "$work/home.html"
asset=$(grep -oE 'https://forum\.bokfri\.viblo\.se/assets/[^" ]+\.css[^" ]*' "$work/home.html" | head -1)
test -n "$asset"
fetch "$url${asset#https://forum.bokfri.viblo.se}" >"$work/asset.css"
test -s "$work/asset.css"
# Database/configuration must never be reachable through Apache.
for path in /config.php /forum.sqlite /storage/logs/flarum.log /vendor/autoload.php /install-forum.php; do
    code=$(curl -s -o "$work/private" -w '%{http_code}' "$url$path")
    test "$code" = 404 || { echo "Unexpected status $code for $path" >&2; exit 1; }
done
docker exec "$name" php -r '
$c = require "/data/config.php";
if ($c["database"]["driver"] !== "sqlite" || $c["debug"]) exit(1);
$d = new PDO("sqlite:/data/forum.sqlite");
if ($d->query("SELECT COUNT(*) FROM users")->fetchColumn() != 1) exit(2);
if ($d->query("SELECT value FROM settings WHERE key = '\''default_locale'\''")->fetchColumn() !== "sv") exit(3);
$d->exec("INSERT INTO settings (key,value) VALUES ('\''smoke_persistence'\'','\''ok'\'')");
'
docker restart "$name" >/dev/null
sleep 3
fetch "$url/" > /dev/null
docker exec "$name" php -r '
$d = new PDO("sqlite:/data/forum.sqlite");
if ($d->query("SELECT value FROM settings WHERE key = '\''smoke_persistence'\''")->fetchColumn() !== "ok") exit(1);
if ($d->query("PRAGMA integrity_check")->fetchColumn() !== "ok") exit(2);
'
# Verify an offline snapshot can restore into a fresh volume/container.
docker stop "$name" >/dev/null
docker run --rm -v "$volume:/data" -v "$work:/backup" --entrypoint tar "$image" \
    -C /data -czf /backup/forum.tar.gz .
docker rm "$name" >/dev/null
docker volume rm "$volume" >/dev/null
docker volume create "$volume" >/dev/null
docker run --rm -v "$volume:/data" -v "$work:/backup" --entrypoint tar "$image" \
    -C /data -xzf /backup/forum.tar.gz
docker run -d --name "$name" -p "127.0.0.1:$port:80" -v "$volume:/data" "$image" >/dev/null
sleep 3
fetch "$url/" > /dev/null
docker exec "$name" php -r '
$d = new PDO("sqlite:/data/forum.sqlite");
if ($d->query("PRAGMA integrity_check")->fetchColumn() !== "ok") exit(1);
if ($d->query("SELECT value FROM settings WHERE key = '\''smoke_persistence'\''")->fetchColumn() !== "ok") exit(2);
'
echo 'PASS: closed installer, SQLite install, Swedish homepage, CSS, private paths, restart, offline backup/restore'
