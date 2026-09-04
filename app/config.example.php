<?php

/*
 * Copy this file to app/config.php on the server and replace the placeholders.
 * Keep config.php outside Hostinger's public_html directory.
 */
return [
    'db_host' => 'localhost',
    'db_port' => 3306,
    'db_name' => 'your_hostinger_database_name',
    'db_user' => 'your_hostinger_database_user',
    'db_password' => 'replace-with-your-database-password',
    'admin_username' => 'replace-with-your-admin-username',
    // Generate with: php -r "echo password_hash('choose-a-strong-password', PASSWORD_DEFAULT), PHP_EOL;"
    'admin_password_hash' => 'replace-with-a-password_hash-value',
    // International format without + or spaces, for example 919876543210.
    'whatsapp_business_number' => 'replace-with-your-whatsapp-business-number',
];
