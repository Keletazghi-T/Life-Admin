# Deployment preparation (no services deployed)

## Future architecture

Browser → Vercel/Next.js → Spring Boot API → managed PostgreSQL + private object storage.

The present implementation uses a private local filesystem adapter. Business services remain independent of the hosting provider. Do not deploy onto ephemeral storage with real PDFs: either supply a persistent private volume or implement/test the future S3 adapter first.

## Environment variables

Backend variables are read by Spring Boot. `.env` files are examples, not automatically loaded by Java; export them or configure your IDE/hosting environment. Never commit filled secret files.

| Variable | Local/default | Production |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | unset for ordinary local use; `dev` for optional document-analysis samples | `prod` |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/life_admin` | Explicit managed PostgreSQL JDBC URL; TLS configuration below |
| `DATABASE_USERNAME` | `life_admin` | Secret-managed database user |
| `DATABASE_PASSWORD` | Required | Secret-managed password, separate from URL |
| `FRONTEND_URL` | `http://localhost:3000` | One exact HTTPS origin, e.g. `https://app.example.com` |
| `DOCUMENT_STORAGE_TYPE` | `local` | `local` for now; unsupported values fail startup |
| `DOCUMENT_STORAGE_LOCAL_PATH` | `./uploads` | Required private persistent storage path |
| `STORAGE_DIRECTORY` | Legacy local-path alias, still accepted | Use the new variable |
| `PORT` | `8080` | Hosting-assigned port supported |
| `SERVER_ADDRESS` | `127.0.0.1` | Defaults to `0.0.0.0` in prod; restrict access at ingress |
| `COOKIE_SECURE` | `false` for localhost HTTP | Always true in prod, regardless of this variable |
| `COOKIE_SAME_SITE` | `strict` | `strict` recommended for same-site deployment |
| `OPENAI_API_KEY` | Backend-only; optional, enables real photo extraction when present | Backend secret only; no service deployed |
| `OPENAI_MODEL` | `gpt-4o-mini` | Existing document-analysis model |
| `OPENAI_RECEIPT_MODEL` | `gpt-5.4-mini` | Configurable image/structured-output model; backend only |
| `LIFE_ADMIN_DEV_MOCKS` | `false`; document-analysis samples require `dev` plus `true` | Always disabled by the production feature gate, even if `dev` and the flag are also set |

Frontend: `BACKEND_URL` is a **server-only**, credential-free HTTP(S) origin. Its default is `http://127.0.0.1:8080`; future example `https://api.example.com`. Set it before `npm run build`, and rebuild when changing targets. Browser requests stay relative to `/api`, including PDF links. This preserves the existing session/CSRF design and avoids requiring a public API variable.

**All `NEXT_PUBLIC_*` values are exposed to the browser. Never place keys, passwords, storage credentials, database URLs containing credentials, or session secrets in them.** A future direct-browser API mode could use a public API origin, but that is not enabled here.

Profiles share `application.yml`; `application-prod.yml` overrides only deployment/security settings. Local/default and `dev` remain HTTP-friendly. Tests supply isolated PostgreSQL/storage settings, and production integration tests explicitly activate `prod,dev` to verify the production veto. Extra duplicate local/test YAML files are unnecessary.

## Builds and startup

Java 21 and Node 22 LTS are the documented development baseline; use PostgreSQL 17 locally.

```sh
cd life-admin-api
./gradlew clean build
# After setting the backend environment variables above:
SPRING_PROFILES_ACTIVE=prod java -jar build/libs/life-admin-api-0.1.0.jar
```

The build runs all tests. `build/libs/*-plain.jar` is not the runnable Boot artifact.

```sh
cd life-admin-web
npm ci
npm run lint
npm run typecheck
BACKEND_URL=https://api.example.com npm run build
# Local production preview only; Vercel manages its own startup:
npm run start
```

No cloud connections are made during the Next.js build. Do not put credentials in `BACKEND_URL`. The current start script binds loopback; a future self-hosted Node deployment can use `npx next start --hostname 0.0.0.0` behind a trusted ingress.

## Database and migration safety

Flyway is the source of truth. Migrations V1–V8 initialize a brand-new database; Hibernate uses `validate`, never `create` or `create-drop`. No historical migration was rewritten. V5 intentionally refuses legacy anonymous document ownership rather than silently assigning those documents to accounts. Preserve existing data and use an explicit migration plan if that guard applies.

Supply the managed database hostname and port through `DATABASE_URL`, for example:

```text
jdbc:postgresql://db.example.com:5432/life_admin?sslmode=verify-full&sslrootcert=/run/secrets/postgres-ca.crt
```

Use the provider's actual CA certificate mounted privately; `verify-full` checks both certificate trust and hostname. Do not install a non-validating SSL factory or globally disable validation. `sslmode=require` encrypts but does not provide the same identity verification. Keep credentials out of JDBC URLs because infrastructure may log connection URLs. See [pgJDBC connection and TLS parameters](https://jdbc.postgresql.org/documentation/use/).

No managed database was contacted. Local embedded-PostgreSQL smoke tests use local connections; they do not certify a future provider's TLS setup. Validate its CA/hostname before deployment. Choose connection-pool sizing for the provider's limits (Spring `SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE` can override the default).

## Sessions, proxy, CORS and CSRF

**Recommended:** one public HTTPS origin, with `/api/*` proxied to the private Spring API and the rest served by Next.js. The existing Next rewrite supports this. A gateway can alternatively route `/api` directly to Spring and page requests to Next.js. Ensure `Set-Cookie`, Cookie, Origin, multipart bodies, and `X-CSRF-TOKEN` are forwarded correctly, with API responses never cached. Set `FRONTEND_URL` to the public origin. Trust forwarded headers only from your ingress; no blanket forwarded-header trust was added.

CORS accepts only that exact configured origin, with credentials and explicitly allowed methods/headers. Wildcards, URL credentials, paths, query strings and insecure production frontend origins are rejected. CORS runs before security authentication for preflight, while authentication and CSRF remain required on actual mutations. Local ports matter: if running the frontend on 3001, set `FRONTEND_URL=http://localhost:3001`. Do not mix `localhost` and `127.0.0.1` browser origins accidentally.

Session cookies are host-only, Path `/`, HttpOnly, SameSite Strict, and Secure in prod. Avoid a broad cookie Domain: the proxy makes the cookie belong to the public application host without sharing it with every subdomain. Local HTTP uses non-Secure cookies. Sessions expire after eight hours and are currently in-memory; use a single backend instance or sticky routing. Shared persistent sessions are later infrastructure work. Password-change security versions and deleted-user checks continue to revoke other sessions on their next request.

For **direct browser requests** from `https://app.example.com` to `https://api.example.com`, both HTTPS hosts are same-site but different origins: keep Secure/HttpOnly, use exact `FRONTEND_URL` CORS, `credentials: "include"`, a host-only API cookie and an API-fetched masked CSRF token on every mutation. SameSite Strict can work for these same-site hosts. All API fetches and PDF links would need a coordinated direct-API frontend adapter; the current frontend intentionally uses the same-origin proxy. For unrelated sites (for example a default Vercel domain and unrelated API host), cookies may require SameSite None + Secure and may still be blocked by browser third-party-cookie policy. Prefer the unified-origin arrangement; do not disable CSRF as a workaround.

## Health, errors and logging

`GET /api/health` is public and returns only `{"status":"UP"}`. It is a **liveness** check, not a database/storage readiness probe. No Actuator endpoints were added. Existing account/data endpoints remain protected.

Production suppresses detailed fallback error bodies, SQL/bind logging and Hibernate SQL-exception details. Application logs use safe operational messages without document text, passwords or tokens. Keep deployment/debug/request-body tracing disabled; never embed credentials in origins or connection URLs. Redact secrets at the hosting log collector too.

Login, registration, password-change and deletion rate limits remain a production ingress requirement. No paid infrastructure or distributed limiter was added.

## Storage and uploads

Local storage creates its configured directory, uses server-generated keys and validates path access. Place it outside public/static frontend directories with permissions limited to the backend service account. Secure its persistent volume and backups. Account/document deletion continues to track failed cleanup for retry.

A future `DocumentStorage` S3-compatible adapter must implement private store/read/idempotent-delete operations, bounded reads, safe generated keys, TLS, least-privilege credentials, encryption, error translation, and the existing deletion/retry guarantees. It will need endpoint, region, private bucket, and secret-managed credentials/role configuration. Never expose storage credentials or make buckets public. `DOCUMENT_STORAGE_TYPE=s3` deliberately fails until such an adapter exists. No PDFs were migrated.

Receipts also accept validated JPEG/PNG images (up to 16 megapixels), with the same private storage and account-deletion guarantees. Currency is USD only. The application retains a **10 MiB per-file limit** and **11 MiB multipart request limit**. Spring returns a friendly 413. Configure every gateway/proxy to accept the full file plus multipart overhead and return understandable 413s; an upstream rejection cannot be rewritten by Spring. Hosting request timeouts must also accommodate later analysis.

Vercel Functions currently document a 4.5 MB request/response payload limit ([official limits](https://vercel.com/docs/functions/limitations)). Do not assume a Function-mediated upload route can carry the existing 10 MiB PDFs. Before choosing deployment routing, verify external-rewrite limits separately with real boundary-size uploads/downloads, or use a unified gateway that routes API traffic directly to Spring. This milestone does not reduce the PDF limit or add direct public object-storage uploads.

## Verification performed

- Full backend clean build and runnable JAR, with all security/ownership/deletion regressions.
- Fresh isolated PostgreSQL databases initialized entirely by V1–V7.
- Local production frontend against the built JAR: register, login, upload, development sample analysis, tasks/reminder, logout/login and persistence.
- Separate production-profile JAR behind temporary localhost HTTPS: Secure/HttpOnly/Strict cookies, CSRF, health, protected PDF, oversized upload response, and absent development API/UI controls, even with `prod,dev` and the development flag.
- Production frontend build with a non-localhost backend URL; no cloud request or deployment.
- Source secret-pattern review; local environment/build/storage paths remain ignored. The workspace had no tracked Git files at review time, so this was a working-tree review rather than a historical repository audit.

Normal development data was not used for destructive testing. Real AI, cloud accounts, S3 and deployment remain unconfigured.


## Receipt extraction (Milestone #15)

Receipt text extraction runs locally through PDFBox and conservative parsing in all profiles, including production. It needs no API key, paid service or new environment variable. Flyway V10 adds suggestion/extraction metadata columns without replacing existing receipt data. Confirmed values remain separate and cannot be overwritten by extraction.

Photos and PDFs without readable text are marked `OCR_REQUIRED` for manual review; no OCR provider is configured. The former fixed receipt sample provider has been removed. Document-analysis mock flags apply only to the existing document development tools. No services were deployed for this milestone.


## Receipt-photo extraction (Milestone #16)

Milestone #16 extends the local extraction path above: JPEG/PNG receipts can now use the backend-only Responses API when `OPENAI_API_KEY` is inherited by Spring Boot. Text PDFs stay local; scanned PDFs still require manual review. V11 adds only nullable extraction-model metadata. Missing credentials or provider failures preserve the original and allow manual review. API requests have bounded output and timeouts, with no application retry; an explicit user retry is a new request.

The review form separates suggestions from confirmed values, and reviewed receipts cannot be re-extracted. No provider credentials, raw response bodies or image base64 are persisted. `.env.example` contains placeholders only. The frontend's proxy timeout is 90 seconds so the backend's 60-second read timeout can return its safe fallback; a future deployment must also accommodate that duration or move extraction to background jobs.

The running preview used for verification has no inherited API key. See [REAL_RECEIPT_TEST.md](REAL_RECEIPT_TEST.md) for exact terminal startup and one controlled test. Automated tests mock HTTP and do not consume credits. No live call or deployment was performed.
