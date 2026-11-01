<?php
// Local CLI installer only: never served by Apache's public document root.
declare(strict_types=1);

if (PHP_SAPI !== 'cli') {
    exit(1);
}
chdir(__DIR__);
if (is_file('config.php')) {
    fwrite(STDERR, "Already installed; refusing to overwrite configuration.\n");
    exit(1);
}
$url = getenv('FLARUM_BASE_URL') ?: '';
if (!filter_var($url, FILTER_VALIDATE_URL) || parse_url($url, PHP_URL_SCHEME) !== 'https') {
    fwrite(STDERR, "Set FLARUM_BASE_URL to your public HTTPS URL.\n");
    exit(1);
}
function prompt(string $label, bool $secret = false): string
{
    fwrite(STDOUT, $label . ': ');
    $hide = $secret && stream_isatty(STDIN);
    if ($hide) {
        system('stty -echo');
    }
    try {
        $value = fgets(STDIN);
    } finally {
        if ($hide) {
            system('stty echo');
            fwrite(STDOUT, "\n");
        }
    }
    if ($value === false) {
        throw new RuntimeException('Input ended unexpectedly.');
    }
    return rtrim($value, "\r\n");
}
$username = prompt('Admin username');
$email = prompt('Admin email');
$password = prompt('Admin password (at least 12 characters)', true);
if (strlen($password) < 12 || $password !== prompt('Repeat password', true)) {
    fwrite(STDERR, "Passwords must match and contain at least 12 characters.\n");
    exit(1);
}
$dataDirectory = getenv('FLARUM_DATA_DIR') ?: '/data';
$database = $dataDirectory . '/forum.sqlite';
if (!is_file($database) || !is_writable($database)) {
    fwrite(STDERR, "SQLite file must already exist and be writable: " . $database . "\n");
    exit(1);
}
$config = [
    'debug' => false,
    'baseUrl' => rtrim($url, '/'),
    'databaseConfiguration' => ['driver' => 'sqlite', 'database' => $database, 'prefix' => ''],
    'adminUser' => compact('username', 'email', 'password'),
    'settings' => ['forum_title' => 'Bokfri forum', 'default_locale' => 'sv'],
    'extensions' => implode(',', [
        'flarum-lang-english', 'flarum-lang-swedish', 'flarum-approval', 'flarum-bbcode',
        'flarum-emoji', 'flarum-flags', 'flarum-likes', 'flarum-lock', 'flarum-markdown',
        'flarum-mentions', 'flarum-sticky', 'flarum-subscriptions', 'flarum-suspend', 'flarum-tags',
    ]),
];
$file = tempnam(sys_get_temp_dir(), 'flarum-install-');
if ($file === false) {
    throw new RuntimeException('Cannot create temporary installer input.');
}
try {
    chmod($file, 0600);
    file_put_contents($file, json_encode($config, JSON_THROW_ON_ERROR));
    passthru(escapeshellarg(PHP_BINARY) . ' flarum install --no-interaction --file ' . escapeshellarg($file), $status);
} finally {
    unlink($file);
}
exit($status);
