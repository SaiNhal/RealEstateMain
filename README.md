# NEXSTRUCT — Hostinger PHP/MySQL Website

NEXSTRUCT is a PHP 8.3 and MySQL application designed for Hostinger Business shared hosting. The responsive HTML, CSS, JavaScript, WhatsApp enquiry flow, and admin dashboard are preserved; PHP provides the same `/api/...` endpoints and MySQL stores the leads.

## Files

- `public/index.html` - page structure, content, sections, metadata, and form markup.
- `public/styles.css` - responsive layout, typography, cards, buttons, animations, navigation, FAQ, contact form, and footer styling.
- `public/script.js` - mobile menu behavior, sticky header state, FAQ accordion behavior, scroll reveal animation, and contact form validation.
- `public/` - upload the contents of this directory to Hostinger's `public_html/`; it contains the site, admin dashboard, and PHP API router.
- `app/bootstrap.php` - PHP 8.3 API, authentication, validation, prepared MySQL queries, secure sessions, and CSV export.
- `app/config.example.php` - configuration template for database and administrator credentials.
- `database/schema.sql` - MySQL schema to import through phpMyAdmin.

## Hostinger deployment

1. Point the domain to the Hostinger website, enable its free SSL certificate, and select **PHP 8.3** in hPanel. The included web-root rules redirect HTTP traffic to HTTPS after the certificate is active.
2. In hPanel, create a MySQL database and user. Import [`database/schema.sql`](database/schema.sql) with phpMyAdmin.
3. Copy `app/config.example.php` to `app/config.php`; replace every placeholder with the database credentials, the administrator username/password hash, and WhatsApp Business number. Generate the hash with `php -r "echo password_hash('a-long-unique-password', PASSWORD_DEFAULT), PHP_EOL;"`. Keep `config.php` outside `public_html`.
4. Upload the **contents** of `public/` to `public_html/`. Upload `app/` beside it at `/home/USERNAME/app/` (not inside `public_html`). The included `.htaccess` files provide the required API rewrites and public-directory protections.
5. Visit `https://YOUR-DOMAIN/api/public-settings`; it should return your configured WhatsApp number. Then visit `https://YOUR-DOMAIN/admin.html`, sign in, submit a test enquiry, and confirm it appears in the dashboard.

For a local PHP server, keep `public/` and `app/` as shown and run from the project root:

```bash
php -S localhost:8080 public/router.php
```

## Included Sections

- Header and navigation
- Hero section
- About section
- Services and service categories
- Why Choose Us
- Six-step process
- Infrastructure and development
- Commitment
- FAQ accordion
- Project CTA
- Contact details
- Enquiry form
- Footer with navigation, services, and contact links

## Branding

All visible text, metadata, alt text, and footer copyright use **NEXSTRUCT** branding.

Copyright text:

```text
© 2026 NEXSTRUCT (OPC) PRIVATE LIMITED. All rights reserved.
```

## Notes

The site uses visually similar remote construction, architecture, interior, and development images where original reference assets were not reused.

Submitted enquiries are validated and saved to MySQL. They appear immediately in the authenticated admin dashboard.

The lead workspace supports search, status stages (New, Contacted, Qualified, Won, Lost), internal follow-up notes, budget and timeline context, and CSV export for offline sales workflows.

## Security and operations

- Database credentials and the password hash remain outside the web root. No secret is sent to the browser.
- The API uses prepared statements, server-side field limits/validation, `utf8mb4`, HTTP-only same-site secure cookies on HTTPS, session-ID rotation at sign-in, and CSRF protection for admin updates and logout.
- Enquiry submissions are limited to five accepted requests per connection per 15 minutes; sign-in attempts are limited to ten per connection per 15 minutes. The limits use short-lived, locked files in PHP's temporary directory and one-way hashes of connection addresses. A honeypot field blocks common automated form submissions.
- Back up the MySQL database regularly using hPanel. The WhatsApp URL is still generated only in the browser with `encodeURIComponent`; WhatsApp is never sent automatically.
