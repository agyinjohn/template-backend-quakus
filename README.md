# Apex Commerce backend

Quarkus (Java 21) REST API for the Apex Commerce SaaS: store owners sign up,
choose a storefront template, manage products, categories and orders, and
their shoppers browse and check out through the public API. Every store is a
tenant; all admin queries are scoped to the signed-in owner's store.

The frontend (Astro) lives in
[apex-ecommerce-temaplates](https://github.com/agyinjohn/apex-ecommerce-temaplates).

## Run locally

Requirements: Java 21 and PostgreSQL (Maven comes with the wrapper).

```bash
# one-time database setup
createuser apex --pwprompt          # password: apex
createdb apex_commerce -O apex

./mvnw quarkus:dev                  # http://localhost:8080, Swagger UI at /q/swagger-ui
./mvnw test                         # tests use in-memory H2, no database needed
```

## Configuration (environment variables)

| Variable | Default | Purpose |
|---|---|---|
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | `jdbc:postgresql://localhost:5432/apex_commerce`, `apex`, `apex` | PostgreSQL connection |
| `JWT_PUBLIC_KEY_LOCATION`, `JWT_PRIVATE_KEY_LOCATION` | bundled dev keys (dev/test only) | RSA key pair that signs login tokens. **Required in production**; the app refuses to start without them. |
| `CORS_ORIGINS` | `http://localhost:4321` | Comma-separated frontend origins |
| `APP_PUBLIC_URL` | `http://localhost:8080` | Base URL used in uploaded image links |
| `APP_UPLOAD_DIR` | `uploads` | Where uploaded images are stored |
| `APP_FRONTEND_URL` | `http://localhost:4321` | Frontend URL used for links in emails |
| `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD` | `localhost`, `587` | SMTP server for order emails (any provider: SendGrid, Mailgun, Amazon SES, Gmail…) |
| `MAIL_FROM` | `Apex Commerce <orders@apex-commerce.local>` | Sending address. Shopper emails show the store's name with this address. |
| `MAIL_START_TLS`, `MAIL_LOGIN` | `REQUIRED`, `REQUIRED` | Set to `DISABLED` for a local test SMTP server |
| `MAIL_MOCK` | `true` in dev/test, `false` in production | `true` logs emails instead of sending them |
| `MAIL_ENABLED` | `true` | `false` turns order emails off entirely (checkout then doesn't promise one) |
| `APP_ENCRYPTION_KEY` | dev key (dev/test only) | 32-byte base64 key that encrypts stores' Paystack secret keys. **Required in production** (`openssl rand -base64 32`). Keep it safe: if it changes, stores must reconnect Paystack. |
| `PAYSTACK_BASE_URL` | `https://api.paystack.co` | Only change for testing against a fake Paystack |
| `PAYMENT_EXPIRY_MINUTES` | `60` | Unpaid online orders are cancelled (stock released) after this long |

Generate production keys with:

```bash
openssl genrsa -out private.pem 2048 && openssl rsa -in private.pem -pubout -out public.pem
```

## API overview

Authenticated endpoints take `Authorization: Bearer <token>` from signup/login.

| Method | Path | |
|---|---|---|
| POST | `/api/auth/signup` | `{fullName, email, password, storeName}` → token, user, store |
| POST | `/api/auth/login` | `{email, password}` → token, user, store |
| GET | `/api/auth/me` | current user and store |
| POST | `/api/auth/password/forgot` | `{email}` → always 202; emails a reset link if the account exists (max 3 per hour) |
| POST | `/api/auth/password/reset` | `{token, password}` → new session; link is single-use, valid 1 hour |
| POST | `/api/auth/password/change` | `{currentPassword, newPassword}` (logged in) → new session |
| GET | `/api/templates` | storefront templates owners can choose from |
| GET/PUT | `/api/admin/store` | store settings (name, contact, currency, socials, published) |
| PUT | `/api/admin/store/template` | `{templateId, addSampleProducts}`: choose or change the design |
| POST | `/api/admin/store/sample-data` | add demo products matching the template |
| GET/POST/PUT/DELETE | `/api/admin/products[/{id}]` | product CRUD, search `q`, filter `categoryId`, `status` |
| GET/POST/PUT/DELETE | `/api/admin/categories[/{id}]` | category CRUD |
| GET, PATCH | `/api/admin/orders[/{id}]` | list orders, change status (cancel restocks) |
| GET | `/api/admin/dashboard` | counts, revenue, recent orders, low stock |
| POST | `/api/admin/uploads` | multipart `file` → `{url, path}` (images up to 5 MB) |
| GET | `/api/public/stores/{slug}` | store + chosen template (unpublished stores 404) |
| GET | `/api/public/stores/{slug}/categories` | |
| GET | `/api/public/stores/{slug}/products` | `category`, `q`, `featured`, `sort`, `page`, `size` |
| GET | `/api/public/stores/{slug}/products/{productSlug}` | |
| POST | `/api/public/stores/{slug}/orders` | checkout; prices come from the database and stock is reserved atomically |

## Accounts and password reset

Reset links contain a random 256-bit token; only its SHA-256 hash is stored, it works once, expires
after an hour, and requesting a new link invalidates the previous one. The forgot-password response is
identical whether or not the email has an account. After a reset or change, every login token issued
before it is rejected (`passwordChangedAt`), so other devices are signed out, and the owner gets a
"password changed" email.

## Payments

Each store connects **its own Paystack account** (Store settings → Payments), so money goes straight to
the store owner. The secret key is checked with Paystack, stored encrypted, and never returned by the API.
Stores can also offer direct bank transfer (with their own instructions) and cash on delivery.

Online payment flow:

1. Checkout with `paymentMethod: PAYSTACK` creates the order (stock reserved, unpaid) and a Paystack
   transaction with an unguessable reference, and returns `paymentUrl` (Paystack's hosted page:
   cards and mobile money).
2. Paystack sends the shopper back to `/s/<slug>/checkout/complete?reference=…`; the frontend calls
   `POST /api/public/stores/{slug}/payments/verify`, which asks Paystack. Success → order `PAID` and the
   confirmation emails go out. Failed/abandoned → the attempt is cancelled right away so the stock is free
   for a retry. Still in progress (e.g. waiting for mobile-money approval) → left open.
3. Paystack's webhook (`POST /api/public/stores/{slug}/payments/paystack-webhook`, signed with the store's
   key) confirms payments when the shopper never comes back. Each store pastes this URL into its Paystack
   dashboard; the admin shows it.
4. Every 5 minutes, unpaid online orders older than `PAYMENT_EXPIRY_MINUTES` are checked with Paystack once
   more and cancelled if still unpaid.

Amounts and currency are checked against the order before anything is marked paid, and a payment that is
confirmed twice (return page and webhook) is only processed once.

| Method | Path | |
|---|---|---|
| GET/PUT | `/api/admin/store/payments` | payment settings: bank transfer (+ instructions), cash on delivery |
| PUT/DELETE | `/api/admin/store/payments/paystack` | connect (`{secretKey}`) or disconnect Paystack |

## Order emails

Sent after the order is saved, in the background (a mail outage never blocks a checkout; failures are logged):

| When | To | Reply-to |
|---|---|---|
| Order placed (online payments: once paid) | shopper: confirmation with items, total, address, payment method, bank details for transfers | store |
| Order placed | store contact email (or the owner's login email): new-order alert with a dashboard link | shopper |
| Status → paid, shipped, delivered, cancelled | shopper: status update | store |

Templates are in `src/main/resources/templates/emails` (Qute, HTML-escaped). The checkout response
includes `confirmationEmail` (the address emailed, or `null` when emails are off) so the storefront
only promises an email that was sent.

## Adding a template

Templates are rendered by the frontend. To make a new one selectable, add the
frontend template, then add an entry with the same id to
`TemplateCatalog.java` (and optionally sample products to
`src/main/resources/sample-catalog.json`).

## Notes

* Schema is managed by Hibernate (`update`). Add Flyway migrations before the
  first production release so schema changes are reviewed and versioned.
* Images are stored on local disk; swap `MediaStorage` for S3 or similar when
  running more than one instance.
