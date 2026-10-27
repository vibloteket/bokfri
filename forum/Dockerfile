FROM php:8.4-apache-bookworm AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
        libfreetype6-dev libjpeg62-turbo-dev libpng-dev libonig-dev libsqlite3-dev libzip-dev \
    && docker-php-ext-configure gd --with-freetype --with-jpeg \
    && docker-php-ext-install -j"$(nproc)" gd mbstring pdo_sqlite zip opcache \
    && a2enmod rewrite headers expires remoteip \
    && rm -rf /var/lib/apt/lists/*

FROM runtime AS application
ARG FLARUM_VERSION=2.0.2
ARG FLARUM_ARCHIVE_SHA256=5de9af0920f09706eff008cce41a96568c78a9bb4b12c7f75b66d86debdb5fef
ARG SWEDISH_VERSION=2.0.4
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
COPY --from=composer:2.10.3 /usr/bin/composer /usr/local/bin/composer
WORKDIR /opt/flarum
# Official PHP 8.4 installation bundle, WITH public/, including its locked dependencies.
RUN curl --fail --show-error --location --retry 3 \
        "https://github.com/flarum/installation-packages/raw/main/packages/v2.x/v${FLARUM_VERSION}/flarum-v${FLARUM_VERSION}-php8.4.tar.gz" \
        --output /tmp/flarum.tar.gz \
    && echo "${FLARUM_ARCHIVE_SHA256}  /tmp/flarum.tar.gz" | sha256sum --check --strict - \
    && tar -xzf /tmp/flarum.tar.gz --no-same-owner -C /opt/flarum \
    && rm /tmp/flarum.tar.gz \
    && test -f public/index.php && test -f vendor/autoload.php && test -f composer.lock \
    && composer require "flarum-lang/swedish:${SWEDISH_VERSION}" --no-update --no-interaction --no-scripts \
    && composer update flarum-lang/swedish --no-dev --no-interaction --no-scripts --prefer-dist --optimize-autoloader

FROM runtime
WORKDIR /opt/flarum
COPY --from=application /opt/flarum ./
COPY apache.conf /etc/apache2/sites-available/000-default.conf
COPY php.ini /usr/local/etc/php/conf.d/forum.ini
COPY entrypoint.sh /usr/local/bin/forum-entrypoint
COPY install-forum.php /opt/flarum/install-forum.php
RUN mkdir -p /opt/flarum-seed \
    && cp -a storage /opt/flarum-seed/storage \
    && cp -a public/assets /opt/flarum-seed/assets \
    && rm -rf storage public/assets \
    && ln -s /data/storage storage \
    && ln -s /data/assets public/assets \
    && ln -s /data/config.php config.php \
    && chown www-data:www-data /opt/flarum \
    && chmod +x /usr/local/bin/forum-entrypoint
VOLUME ["/data"]
EXPOSE 80
ENTRYPOINT ["forum-entrypoint"]
CMD ["apache2-foreground"]
