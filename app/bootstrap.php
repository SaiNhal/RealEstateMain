<?php
declare(strict_types=1);

const NEXSTRUCT_MAX_BODY_BYTES = 10_000;
const NEXSTRUCT_SESSION_NAME = 'NEXSTRUCT_ADMIN_SESSION';
const NEXSTRUCT_ENQUIRY_RATE_LIMIT = 5;
const NEXSTRUCT_ENQUIRY_RATE_WINDOW = 900;
const NEXSTRUCT_LOGIN_RATE_LIMIT = 10;
const NEXSTRUCT_LOGIN_RATE_WINDOW = 900;

function nexstruct_config(): array
{
    $configFile = __DIR__ . '/config.php';
    $fileConfig = is_file($configFile) ? require $configFile : [];
    if (!is_array($fileConfig)) {
        throw new RuntimeException('app/config.php must return an array.');
    }

    return [
        'db_host' => $fileConfig['db_host'] ?? (getenv('NEXSTRUCT_DB_HOST') ?: 'localhost'),
        'db_port' => (int) ($fileConfig['db_port'] ?? (getenv('NEXSTRUCT_DB_PORT') ?: 3306)),
        'db_name' => $fileConfig['db_name'] ?? (getenv('NEXSTRUCT_DB_NAME') ?: ''),
        'db_user' => $fileConfig['db_user'] ?? (getenv('NEXSTRUCT_DB_USER') ?: ''),
        'db_password' => $fileConfig['db_password'] ?? (getenv('NEXSTRUCT_DB_PASSWORD') ?: ''),
        'admin_username' => $fileConfig['admin_username'] ?? (getenv('NEXSTRUCT_ADMIN_USERNAME') ?: ''),
        'admin_password_hash' => $fileConfig['admin_password_hash'] ?? (getenv('NEXSTRUCT_ADMIN_PASSWORD_HASH') ?: ''),
        'whatsapp_business_number' => $fileConfig['whatsapp_business_number'] ?? (getenv('NEXSTRUCT_WHATSAPP_BUSINESS_NUMBER') ?: ''),
    ];
}

function nexstruct_pdo(): PDO
{
    static $pdo = null;
    if ($pdo instanceof PDO) {
        return $pdo;
    }

    $config = nexstruct_config();
    foreach (['db_name', 'db_user', 'db_password'] as $key) {
        if ($config[$key] === '') {
            throw new RuntimeException('Database configuration is incomplete.');
        }
    }

    $pdo = new PDO(
        sprintf('mysql:host=%s;port=%d;dbname=%s;charset=utf8mb4', $config['db_host'], $config['db_port'], $config['db_name']),
        $config['db_user'],
        $config['db_password'],
        [PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION, PDO::ATTR_EMULATE_PREPARES => false]
    );
    return $pdo;
}

function nexstruct_start_session(): void
{
    if (session_status() === PHP_SESSION_ACTIVE) {
        return;
    }
    session_name(NEXSTRUCT_SESSION_NAME);
    session_set_cookie_params([
        'lifetime' => 8 * 60 * 60,
        'path' => '/',
        'secure' => (!empty($_SERVER['HTTPS']) && $_SERVER['HTTPS'] !== 'off') || (($_SERVER['SERVER_PORT'] ?? '') === '443'),
        'httponly' => true,
        'samesite' => 'Lax',
    ]);
    session_start();
}

function nexstruct_json(int $status, array $body): never
{
    http_response_code($status);
    header('Content-Type: application/json; charset=utf-8');
    header('Cache-Control: no-store');
    header('X-Content-Type-Options: nosniff');
    echo json_encode($body, JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES | JSON_THROW_ON_ERROR);
    exit;
}

function nexstruct_clean(mixed $value, int $maximum): string
{
    $value = is_string($value) ? trim($value) : '';
    return function_exists('mb_substr') ? mb_substr($value, 0, $maximum, 'UTF-8') : substr($value, 0, $maximum);
}

function nexstruct_length(string $value): int
{
    return function_exists('mb_strlen') ? mb_strlen($value, 'UTF-8') : strlen($value);
}

function nexstruct_allow_rate_limited_action(string $action, int $limit, int $window): bool
{
    $ip = (string) ($_SERVER['REMOTE_ADDR'] ?? 'unknown');
    $file = rtrim(sys_get_temp_dir(), DIRECTORY_SEPARATOR) . DIRECTORY_SEPARATOR . 'nexstruct-' . $action . '-' . hash('sha256', $ip) . '.json';
    $handle = @fopen($file, 'c+');
    if ($handle === false || !flock($handle, LOCK_EX)) {
        if (is_resource($handle)) fclose($handle);
        return true;
    }

    $now = time();
    $stored = stream_get_contents($handle);
    $attempts = json_decode($stored ?: '[]', true);
    $attempts = is_array($attempts) ? array_values(array_filter($attempts, static fn ($attempt): bool => is_int($attempt) && $attempt > ($now - $window))) : [];
    if (count($attempts) >= $limit) {
        flock($handle, LOCK_UN);
        fclose($handle);
        return false;
    }

    $attempts[] = $now;
    rewind($handle);
    ftruncate($handle, 0);
    fwrite($handle, json_encode($attempts, JSON_THROW_ON_ERROR));
    fflush($handle);
    flock($handle, LOCK_UN);
    fclose($handle);
    return true;
}

function nexstruct_allow_enquiry_submission(): bool
{
    return nexstruct_allow_rate_limited_action('enquiry', NEXSTRUCT_ENQUIRY_RATE_LIMIT, NEXSTRUCT_ENQUIRY_RATE_WINDOW);
}

function nexstruct_allow_login_attempt(): bool
{
    return nexstruct_allow_rate_limited_action('login', NEXSTRUCT_LOGIN_RATE_LIMIT, NEXSTRUCT_LOGIN_RATE_WINDOW);
}

function nexstruct_csrf_token(): string
{
    nexstruct_start_session();
    if (empty($_SESSION['csrf_token'])) {
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
    }
    return $_SESSION['csrf_token'];
}

function nexstruct_require_admin(bool $csrf = false): void
{
    nexstruct_start_session();
    if (empty($_SESSION['admin_authenticated'])) {
        nexstruct_json(401, ['message' => 'Please sign in to continue.']);
    }
    if ($csrf && !hash_equals($_SESSION['csrf_token'] ?? '', $_SERVER['HTTP_X_CSRF_TOKEN'] ?? '')) {
        nexstruct_json(403, ['message' => 'Your session verification failed. Refresh the page and try again.']);
    }
}

function nexstruct_id(): string
{
    return rtrim(strtr(base64_encode(random_bytes(12)), '+/', '-_'), '=');
}

function nexstruct_iso_datetime(string $value): string
{
    return (new DateTimeImmutable($value, new DateTimeZone('UTC')))->format('Y-m-d\\TH:i:s.v\\Z');
}

function nexstruct_enquiry(array $row): array
{
    return [
        'id' => $row['id'], 'createdAt' => nexstruct_iso_datetime($row['created_at']), 'updatedAt' => nexstruct_iso_datetime($row['updated_at']),
        'name' => $row['name'], 'phone' => $row['phone'], 'email' => $row['email'], 'location' => $row['location'],
        'projectType' => $row['project_type'], 'budget' => $row['budget'], 'timeline' => $row['timeline'],
        'description' => $row['description'], 'status' => $row['status'], 'notes' => $row['notes'],
    ];
}
