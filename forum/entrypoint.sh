#!/bin/sh
set -eu
umask 027
mkdir -p /data/storage /data/assets
# Preserve existing uploads and runtime state; seed missing framework directories.
cp -an /opt/flarum-seed/storage/. /data/storage/
cp -an /opt/flarum-seed/assets/. /data/assets/
# Pre-create the absolute SQLite path: Flarum otherwise resolves missing files relative to its base.
touch /data/forum.sqlite
chown -R www-data:www-data /data
if [ "$1" = apache2-foreground ] && [ ! -s /data/config.php ]; then
    echo >&2 'Forum is not installed. Run: docker compose run --rm flarum php install-forum.php'
    exit 1
fi
# Run CLI tasks with the same ownership as web requests.
if [ "$1" = php ]; then
    exec su -s /bin/sh www-data -c 'exec "$@"' -- sh "$@"
fi
exec docker-php-entrypoint "$@"
