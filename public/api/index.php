<?php
declare(strict_types=1);

require dirname(__DIR__, 2) . '/app/bootstrap.php';

header('X-Content-Type-Options: nosniff');
header('Referrer-Policy: strict-origin-when-cross-origin');

try {
    $path = '/' . trim((string) preg_replace('#^/api/?#', '', parse_url($_SERVER['REQUEST_URI'] ?? '/', PHP_URL_PATH) ?: ''), '/');
    $method = strtoupper($_SERVER['REQUEST_METHOD'] ?? 'GET');

    if ($path === '/public-settings') {
        if ($method !== 'GET') nexstruct_json(405, ['message' => 'Method not allowed.']);
        $number = preg_replace('/\D+/', '', (string) nexstruct_config()['whatsapp_business_number']);
        nexstruct_json(200, ['whatsappBusinessNumber' => preg_match('/^\d{8,15}$/', $number) ? $number : null]);
    }

    if ($path === '/enquiries') {
        if ($method !== 'POST') nexstruct_json(405, ['message' => 'Method not allowed.']);
        if ((int) ($_SERVER['CONTENT_LENGTH'] ?? 0) > NEXSTRUCT_MAX_BODY_BYTES) nexstruct_json(413, ['message' => 'Request is too large.']);

        $enquiry = [
            'name' => nexstruct_clean($_POST['name'] ?? '', 120), 'phone' => nexstruct_clean($_POST['phone'] ?? '', 40),
            'email' => nexstruct_clean($_POST['email'] ?? '', 160), 'location' => nexstruct_clean($_POST['location'] ?? '', 180),
            'projectType' => nexstruct_clean($_POST['projectType'] ?? '', 100), 'budget' => nexstruct_clean($_POST['budget'] ?? '', 80),
            'timeline' => nexstruct_clean($_POST['timeline'] ?? '', 80), 'description' => nexstruct_clean($_POST['description'] ?? '', 4000),
        ];
        if (nexstruct_clean($_POST['website'] ?? '', 200) !== '') {
            nexstruct_json(201, ['message' => 'Thank you. Your project enquiry has been received.', 'stored' => false]);
        }
        if ($enquiry['name'] === '' || nexstruct_length($enquiry['phone']) < 8 || $enquiry['projectType'] === '' || $enquiry['description'] === '') {
            nexstruct_json(400, ['message' => 'Please complete the required project details.']);
        }
        if ($enquiry['email'] !== '' && filter_var($enquiry['email'], FILTER_VALIDATE_EMAIL) === false) {
            nexstruct_json(400, ['message' => 'Please enter a valid email address.']);
        }
        if (!nexstruct_allow_enquiry_submission()) {
            header('Retry-After: ' . NEXSTRUCT_ENQUIRY_RATE_WINDOW);
            nexstruct_json(429, ['message' => 'Too many enquiries were sent from this connection. Please try again in 15 minutes or continue on WhatsApp.']);
        }

        $statement = nexstruct_pdo()->prepare('INSERT INTO enquiries (id, name, phone, email, location, project_type, budget, timeline, description) VALUES (:id, :name, :phone, :email, :location, :project_type, :budget, :timeline, :description)');
        $statement->execute(['id' => nexstruct_id(), 'name' => $enquiry['name'], 'phone' => $enquiry['phone'], 'email' => $enquiry['email'], 'location' => $enquiry['location'], 'project_type' => $enquiry['projectType'], 'budget' => $enquiry['budget'], 'timeline' => $enquiry['timeline'], 'description' => $enquiry['description']]);
        nexstruct_json(201, ['message' => 'Thank you. Your project enquiry has been received.', 'stored' => true]);
    }

    if ($path === '/auth/login') {
        if ($method !== 'POST') nexstruct_json(405, ['message' => 'Method not allowed.']);
        if (!nexstruct_allow_login_attempt()) {
            header('Retry-After: ' . NEXSTRUCT_LOGIN_RATE_WINDOW);
            nexstruct_json(429, ['message' => 'Too many sign-in attempts. Please try again in 15 minutes.']);
        }
        nexstruct_start_session();
        $config = nexstruct_config();
        $username = nexstruct_clean($_POST['username'] ?? '', 120);
        $password = is_string($_POST['password'] ?? null) ? $_POST['password'] : '';
        if ($config['admin_username'] === '' || $config['admin_password_hash'] === '' || !hash_equals($config['admin_username'], $username) || !password_verify($password, $config['admin_password_hash'])) {
            nexstruct_json(401, ['message' => 'Invalid username or password.']);
        }
        session_regenerate_id(true);
        $_SESSION['admin_authenticated'] = true;
        nexstruct_json(200, ['message' => 'Signed in successfully.', 'csrfToken' => nexstruct_csrf_token()]);
    }

    if ($path === '/auth/logout') {
        if ($method !== 'POST') nexstruct_json(405, ['message' => 'Method not allowed.']);
        nexstruct_require_admin(true);
        $_SESSION = [];
        session_destroy();
        nexstruct_json(200, ['message' => 'Signed out.']);
    }

    if ($path === '/auth/session') {
        if ($method !== 'GET') nexstruct_json(405, ['message' => 'Method not allowed.']);
        nexstruct_start_session();
        nexstruct_json(200, ['authenticated' => !empty($_SESSION['admin_authenticated']), 'csrfToken' => !empty($_SESSION['admin_authenticated']) ? nexstruct_csrf_token() : null]);
    }

    if ($path === '/admin/enquiries') {
        if ($method !== 'GET') nexstruct_json(405, ['message' => 'Method not allowed.']);
        nexstruct_require_admin();
        $status = nexstruct_clean($_GET['status'] ?? '', 30);
        $query = nexstruct_clean($_GET['q'] ?? '', 180);
        $sql = 'SELECT * FROM enquiries WHERE 1=1';
        $parameters = [];
        if ($status !== '') { $sql .= ' AND status = :status'; $parameters['status'] = $status; }
        if ($query !== '') { $sql .= ' AND CONCAT(name, " ", phone, " ", email, " ", project_type, " ", location) LIKE :query'; $parameters['query'] = '%' . $query . '%'; }
        $sql .= ' ORDER BY created_at DESC';
        $statement = nexstruct_pdo()->prepare($sql);
        $statement->execute($parameters);
        nexstruct_json(200, ['enquiries' => array_map('nexstruct_enquiry', $statement->fetchAll(PDO::FETCH_ASSOC))]);
    }

    if (preg_match('#^/admin/enquiries/([A-Za-z0-9_-]{16,32})$#', $path, $matches)) {
        nexstruct_require_admin(true);
        $id = $matches[1];
        if ($method === 'PATCH') {
            parse_str((string) file_get_contents('php://input'), $input);
            $status = nexstruct_clean($input['status'] ?? '', 30);
            $note = nexstruct_clean($input['note'] ?? '', 4000);
            if (!in_array($status, ['New', 'Contacted', 'Qualified', 'Won', 'Lost'], true)) nexstruct_json(400, ['message' => 'Choose a valid lead status.']);
            $pdo = nexstruct_pdo();
            $current = $pdo->prepare('SELECT notes FROM enquiries WHERE id = :id');
            $current->execute(['id' => $id]);
            $existing = $current->fetch(PDO::FETCH_ASSOC);
            if (!$existing) nexstruct_json(404, ['message' => 'Lead not found.']);
            $notes = $existing['notes'];
            if ($note !== '') $notes = ($notes === '' ? '' : $notes . "\n---\n") . gmdate('c') . ' | ' . str_replace(["\r", "\n"], ' ', $note);
            $update = $pdo->prepare('UPDATE enquiries SET status = :status, notes = :notes WHERE id = :id');
            $update->execute(['status' => $status, 'notes' => $notes, 'id' => $id]);
            $lead = $pdo->prepare('SELECT * FROM enquiries WHERE id = :id');
            $lead->execute(['id' => $id]);
            nexstruct_json(200, ['message' => 'Lead updated.', 'enquiry' => nexstruct_enquiry($lead->fetch(PDO::FETCH_ASSOC))]);
        }
        if ($method === 'DELETE') {
            $statement = nexstruct_pdo()->prepare('DELETE FROM enquiries WHERE id = :id');
            $statement->execute(['id' => $id]);
            if ($statement->rowCount() !== 1) nexstruct_json(404, ['message' => 'Lead not found.']);
            nexstruct_json(200, ['message' => 'Lead permanently deleted.']);
        }
        nexstruct_json(405, ['message' => 'Method not allowed.']);
    }

    if ($path === '/admin/export') {
        if ($method !== 'GET') nexstruct_json(405, ['message' => 'Method not allowed.']);
        nexstruct_require_admin();
        $rows = nexstruct_pdo()->query('SELECT * FROM enquiries ORDER BY created_at DESC')->fetchAll(PDO::FETCH_ASSOC);
        header('Content-Type: text/csv; charset=utf-8');
        header('Content-Disposition: attachment; filename=nexstruct-leads.csv');
        header('Cache-Control: no-store');
        $output = fopen('php://output', 'wb');
        fputcsv($output, ['ID', 'Submitted', 'Status', 'Name', 'Phone', 'Email', 'Location', 'Project Type', 'Budget', 'Timeline', 'Project Brief', 'Internal Notes']);
        foreach ($rows as $row) fputcsv($output, [$row['id'], nexstruct_iso_datetime($row['created_at']), $row['status'], $row['name'], $row['phone'], $row['email'], $row['location'], $row['project_type'], $row['budget'], $row['timeline'], $row['description'], $row['notes']]);
        fclose($output);
        exit;
    }

    nexstruct_json(404, ['message' => 'Not found.']);
} catch (PDOException $exception) {
    error_log('NEXSTRUCT database error: ' . $exception->getMessage());
    nexstruct_json(503, ['message' => 'The enquiry service is temporarily unavailable. Please continue on WhatsApp.']);
} catch (Throwable $exception) {
    error_log('NEXSTRUCT application error: ' . $exception->getMessage());
    nexstruct_json(500, ['message' => 'The enquiry service is temporarily unavailable. Please continue on WhatsApp.']);
}
