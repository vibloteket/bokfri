FROM php:8.4-apache-bookworm AS runtime
RUN apt-get update && apt-get install -y --no-install-recommends \
        libfreetype6-dev libjpeg62-turbo-dev libpng-dev libonig-dev libsqlite3-dev libzip-dev \
    && docker-php-ext-configure gd --with-freetype --with-jpeg \
    && docker-php-ext-install -j"$(nproc)" gd mbstring pdo_sqlite zip opcache \
    && a2enmod rewrite headers expires remoteip \
    && rm -rf /var/lib/apt/lists/*

FROM runtime AS dependencies
COPY --from=composer:2.10.3 /usr/bin/composer /usr/local/bin/composer
WORKDIR /opt/flarum
COPY app/composer.json app/composer.lock ./
RUN composer install --no-dev --no-interaction --prefer-dist --no-scripts --optimize-autoloader

FROM runtime
WORKDIR /opt/flarum
COPY app/ ./
COPY --from=dependencies /opt/flarum/vendor ./vendor
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
