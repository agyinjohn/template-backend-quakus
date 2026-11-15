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
