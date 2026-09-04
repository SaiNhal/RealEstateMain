<?php
declare(strict_types=1);

/* Router for PHP's local development server. Apache on Hostinger uses api/.htaccess. */
$path = parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: '/';
if ($path === '/') {
    $path = '/index.html';
}
if (str_starts_with($path, '/api/')) {
    require __DIR__ . '/api/index.php';
    exit;
}

$mappedFile = null;
if ($path === '/index.html' || $path === '/admin.html' || $path === '/styles.css' || $path === '/script.js' || $path === '/admin.css' || $path === '/admin.js') {
    $mappedFile = __DIR__ . $path;
}
if ($mappedFile !== null && is_file($mappedFile)) {
    $mime = match (pathinfo($mappedFile, PATHINFO_EXTENSION)) {
        'css' => 'text/css; charset=utf-8',
        'js' => 'application/javascript; charset=utf-8',
        'html' => 'text/html; charset=utf-8',
        'json' => 'application/json; charset=utf-8',
        default => mime_content_type($mappedFile) ?: 'application/octet-stream',
    };
    header('Content-Type: ' . $mime);
    readfile($mappedFile);
    exit;
}

$file = realpath(__DIR__ . $path);
if ($file !== false && str_starts_with($file, __DIR__) && is_file($file)) {
    return false;
}

http_response_code(404);
header('Content-Type: text/plain; charset=utf-8');
echo 'Not found';
