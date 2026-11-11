# Life Admin

Milestones #1–18: private document uploads, structured analysis architecture, tasks/reminders, an attention-focused dashboard, account management/deletion, deployment preparation, receipt/expense organization, tax-year review/CSV export, real text-PDF and backend AI photo receipt extraction with review, and a unified dashboard/Inbox. No cloud services are deployed.

## Included

- `life-admin-api`: Java 21 target, Spring Boot 3.5, Gradle wrapper, JPA, Validation, Lombok, PostgreSQL, Flyway.
- `life-admin-web`: Next.js App Router, TypeScript, Tailwind CSS, responsive upload dashboard and document list.
- PDF validation: 10 MiB maximum, nonempty file, PDF extension, MIME type, `%PDF-` signature, and PDFBox structure/page validation. This is not malware scanning; originals are not rewritten.
- Randomized storage names, sanitized display names, storage abstraction, metadata failure cleanup, consistent error messages.
- Spring Security accounts with BCrypt passwords, HTTP-only session cookies, CSRF protection, and user-scoped access to documents, analyses, tasks, and reminders.

External notification delivery, email verification, password reset, social login, OCR, and deployment remain deferred. Task creation, editing, completion, reopening, deletion, and analysis-based generation are implemented. Uploading never triggers an AI call. Analysis is an explicit action on the document detail page and is disabled gracefully when the backend has no AI key.

## Requirements

Node.js 20.9+ (22 recommended), JDK 21+ (build emits Java 21 bytecode), and PostgreSQL 17. Docker is optional for the provided database service. Gradle is downloaded by the wrapper.

## 1. Start PostgreSQL

With Docker:

```sh
cp .env.example .env
# Set DATABASE_PASSWORD in .env to your own local password.
docker compose up -d postgres
```

Or use an existing local PostgreSQL installation. Create a `life_admin` database and role with a password, then configure the backend accordingly. Do not put passwords into Git or share them in logs.

## 2. Start Spring Boot

```sh
cd life-admin-api
cp .env.example .env
# Edit .env: match your local database URL, username and password.
set -a
source .env
set +a
./gradlew bootRun
```

Spring Boot does not automatically read `.env`; the shell commands above export it. Quote values containing shell-special characters. The database migration runs on startup; Hibernate validates the schema. The API binds to `127.0.0.1:8080` and stores files under `life-admin-api/uploads/` by default. `DOCUMENT_STORAGE_LOCAL_PATH` can point elsewhere (`STORAGE_DIRECTORY` remains a local compatibility alias).

## 3. Start Next.js

In a second terminal:

```sh
cd life-admin-web
cp .env.example .env.local
npm ci
npm run dev
```

Open **http://localhost:3000**, create an account (registration logs you in), select `docs/fixtures/sample.pdf` (or another PDF), then click **Upload document**. A successful response displays the success message and adds the persisted metadata to the document list. Refresh to confirm the list remains available after logging out and back in.

The browser calls `/api` on Next.js, which proxies to Spring Boot using the server-only `BACKEND_URL`. No database or API secrets belong in `NEXT_PUBLIC_*` variables. Restart Next.js after changing backend configuration; production rewrites are resolved at build time.

## Accounts and development data migration

Accounts persist in PostgreSQL. Email addresses are trimmed and lowercased, with a unique database constraint. Passwords require 8–72 characters and at most 72 UTF-8 bytes; BCrypt uses cost 12. Passwords are never returned by APIs. Registration logs the user in immediately, just like login; both rotate the session ID and CSRF token. In-memory sessions expire after 8 hours of inactivity or a backend restart. **Logging in again restores access to the same account's saved data**, provided the database and file storage are retained.

`V5__user_accounts.sql` adds users and a required foreign key from `documents.user_id`. It intentionally refuses to run when legacy anonymous documents exist. Anonymous workspace UUIDs are not proof of account ownership. No automatic claiming, reassignment, or deletion occurs.

For existing development installations, preserve the old database and upload directory, then create a **new empty database** for accounts:

```sh
# Run with your local PostgreSQL administrative connection; prompts for credentials as needed.
createdb --owner=life_admin life_admin_accounts
```

Set `DATABASE_URL=jdbc:postgresql://localhost:5432/life_admin_accounts` and a new `STORAGE_DIRECTORY` in the ignored backend environment file, then start Spring Boot to apply V1–V7. Create accounts and upload test PDFs again. Do not edit Flyway history or map anonymous rows to arbitrary users. Any non-development legacy data requires a separate verified ownership migration before upgrading.

The agent-managed preview uses **http://localhost:3001**, backend port 8084, and a separate retained database/upload directory at `.local/accounts-preview/` (Git-ignored). Older anonymous preview stores are untouched. Normal installation defaults remain port 3000/8080 with your configured PostgreSQL.

## Cookie, CSRF, and production configuration

The browser uses same-origin Next.js `/api` rewrites; credentials use the HTTP-only `JSESSIONID` cookie, never localStorage. CORS permits only the exact `FRONTEND_URL` origin (default `http://localhost:3000`). Keep the same-origin proxy topology for HTTPS production; wildcard credentialed CORS is never enabled. Set `FRONTEND_URL=http://localhost:3001` when using the agent-managed preview.

`GET /api/auth/csrf` returns a masked Spring Security CSRF token and its header name. It can establish an anonymous session for login/registration. All state-changing requests—including login, registration, upload, and logout—must send this token in the returned header. The frontend obtains a fresh token before each mutation, including after authentication/logout rotation. Spring's session token repository and default XOR handler retain BREACH protection. The old `X-Life-Admin` header is no longer an authentication or CSRF mechanism.

Spring Security's authentication manager verifies BCrypt passwords. The login controller invokes Spring's session fixation/CSRF strategies and explicitly saves the security context. Logout uses Spring's logout filter to invalidate the session, clear authentication and CSRF state, and expire the cookie. Protected API reads return JSON 401 when unauthenticated; CSRF failures return 403. Missing and another user's resources both return 404. API responses are non-cacheable. The frontend renders no protected content until `/auth/me` succeeds and clears private client state through a full navigation on logout.

Local HTTP: `HttpOnly=true`, `SameSite=Strict`, `COOKIE_SECURE=false`. HTTPS production: activate `prod`; `application-prod.yml` forces `Secure=true`, retains HTTP-only/Strict, and development mocks are disabled even if `dev` is also active. Serve browser pages and `/api` over one HTTPS origin, keep the backend/storage private, and set the server-only `BACKEND_URL` before building Next.js. If you later change to cross-site hosting, review an explicit origin allowlist, credentialed CORS, cookie scope, and CSRF together; the current setup intentionally does not support it. TLS termination/HSTS and abuse rate limits belong at the production gateway. In-memory sessions require a single backend instance or sticky routing; shared session storage is future deployment work.

Uploaded files remain outside Next.js public assets, with randomized names and no exposed filesystem paths. PDF viewing and download use an authenticated controller with an ownership check before storage access; no storage path or internal filename is returned.

Security references: [Spring Security 6.5 session persistence and fixation protection](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html), [CSRF protection for JavaScript clients](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html).

## API / Postman

Import `docs/Life-Admin.postman_collection.json`. Keep the cookie jar enabled. Set `accountEmail` and `accountPassword` in your private local Postman environment; do not export passwords. Run **Get CSRF token** before each mutation, then **Register** or **Login**. Authentication rotates the token, so fetch it again afterward. Select a local PDF in the upload request; do not manually set multipart Content-Type.

| Method | Endpoint | Result |
| --- | --- | --- |
| POST | `/api/documents/upload` | Multipart field `file`; 201 with metadata and Location header |
| GET | `/api/documents?page=0` | Newest first, 50 records per page, current user only |
| GET | `/api/documents/{id}` | Metadata; 404 for missing or another user's document |
| POST | `/api/documents/{id}/analyze` | Extract text, analyze, persist, and return structured analysis (200); reuse an existing result |
| GET | `/api/documents/{id}/analysis` | Saved analysis (200); 404 if absent or inaccessible |

Authentication endpoints:

| Method | Endpoint | Result |
| --- | --- | --- |
| GET | `/api/auth/csrf` | Masked token and header name; no authentication required |
| POST | `/api/auth/register` | `{email,password}`; 201 safe `{id,email}` and authenticated session |
| POST | `/api/auth/login` | `{email,password}`; 200 safe `{id,email}` or generic invalid-credentials 401 |
| GET | `/api/auth/me` | `{id,email}` or 401 |
| POST | `/api/auth/logout` | Invalidate session and expire cookie; 204 |

No request can select an owner using a user ID in its body/query. Controllers derive the user UUID from Spring Security; domain services continue to accept owner UUIDs independently of the web authentication mechanism.

## Milestone #3: tasks and development sample analysis

Tasks live under **Your action plan** on the existing document detail page. Add a task using the inline form, optionally set a description/date, use the checkbox to complete or reopen it, and use Edit/Delete for changes. Status, dates, descriptions, and changes are saved in PostgreSQL. Manual tasks do not require analysis or an AI key. Dates are date-only `YYYY-MM-DD` strings or null; past dates are allowed, impossible dates and timestamps are rejected.

To test the complete workflow **without real AI**, start Spring Boot with these additional environment variables (after loading your normal database environment):

```sh
export SPRING_PROFILES_ACTIVE=dev
export LIFE_ADMIN_DEV_MOCKS=true
export OPENAI_API_KEY=
./gradlew bootRun
```

The sample endpoint requires both the `dev` profile and `LIFE_ADMIN_DEV_MOCKS=true`. It is unavailable by default, and it is always disabled when the `prod` profile is active. Restart the backend after changing these flags. The frontend discovers availability from the backend; there is no public frontend flag that can enable mock writes.

1. Upload `docs/fixtures/TestRecord.pdf` (a harmless sample PDF).
2. Open its row under My Documents.
3. Expand **DEVELOPMENT TOOLS** and click **Load mock analysis**. This explicit action saves the sample explanation, October 15, 2026 deadline, required items, and next steps. It does not extract facts or call an AI provider.
4. Read the **DEVELOPMENT SAMPLE** label. This provenance is stored in PostgreSQL and stays visible after refresh or after mocks are disabled.
5. In **DEVELOPMENT TOOLS**, click **Generate sample action plan** to create the three suggested tasks. The sample deadline is copied as their initial due date; generated sample tasks remain labeled as development data.
6. Complete/reopen a task, refresh, edit its title/description/date, and delete it with the inline confirmation.

The mock loader is restricted to `TestRecord.pdf`, checks authenticated user ownership, reuses an existing mock, and refuses to overwrite real analysis. The normal Analyze document behavior is not replaced by mocks. Existing mock results remain explicitly labeled when read through the normal analysis endpoint.

Task generation is independent of AI providers: `TaskService` uses only saved `DocumentAnalysis.nextSteps` and its nullable deadline. It performs no AI calls and infers no dates. A short PostgreSQL document-row lock and a unique generation ledger make repeated/concurrent generation safe. Repeat generation leaves manual tasks, edits, completion status, and due dates unchanged. It also does **not** recreate tasks that were deliberately deleted. There is no reset/regenerate feature in this milestone.

`V3__tasks_and_analysis_source.sql` adds the task table, task generation ledger, and analysis provenance. Tasks have a required document foreign key and `TODO`/`COMPLETED` status. Every task API checks the parent document's user ownership; wrong-user and missing records return the same 404 response.

| Method | Endpoint | Result |
| --- | --- | --- |
| GET | `/api/documents/{id}/tasks` | Current user's document tasks (200) |
| POST | `/api/documents/{id}/tasks` | Create `{title, description?, dueDate?}` (201) |
| PUT | `/api/tasks/{id}` | Update title, description, and due date (200); status is preserved |
| PATCH | `/api/tasks/{id}/status` | Set `{status: "TODO"}` or `{status: "COMPLETED"}` (200) |
| DELETE | `/api/tasks/{id}` | Delete task (204) |
| POST | `/api/documents/{id}/tasks/generate` | Generate once from saved analysis and return all tasks (200); 409 without analysis |
| GET | `/api/development/capabilities` | Whether development mock loading is enabled |
| POST | `/api/development/documents/{id}/mock-analysis` | Explicit sample loading, development-only (200) |

All endpoints require an authenticated session; mutations also require a valid CSRF token. Invalid titles, dates, statuses, or JSON return 400. Titles are limited to 1,000 characters and descriptions to 4,000 characters. Omit optional fields or send null; send null to clear a due date or description when updating.

## Verification

```sh
cd life-admin-api
./gradlew test
```

Integration tests launch a temporary real PostgreSQL database using embedded PostgreSQL (no Docker required). Tests cover upload, stored bytes, persisted metadata, listing, owner isolation, unsafe filenames, invalid PDFs, empty/oversize files, missing headers/parts, invalid IDs/pagination, and cleanup after metadata failure. Task tests additionally cover creation, retrieval, editing, completing/reopening, deletion, strict date validation, every ownership boundary, missing records, sample provenance and gating, provider-independent generation, preserving edits/deletions, and concurrent duplicate prevention. Analysis tests additionally cover PDFBox extraction and limits, unreadable/encrypted PDFs, missing files/documents, cross-user access before file reads, PostgreSQL analysis persistence, repeat requests, nullable values, provider failures, structured-response validation, and startup/upload without an AI key. Downloads require network access on first run.

```sh
cd life-admin-web
npm run lint
npm run typecheck
npm run build
```

## Analysis configuration and behavior

Click a document under **My Documents**, then **Analyze document**. Without an API key, Spring Boot starts normally; analysis of a readable PDF returns HTTP 503 with “AI analysis is not configured.” Uploads and document listing still work. No placeholder or fabricated analysis is saved.

To enable the isolated OpenAI adapter, set these variables **only for Spring Boot**, then restart the backend:

```sh
# Add the real key to your ignored backend .env; never commit or put it in Next.js.
OPENAI_API_KEY=
OPENAI_MODEL=gpt-4o-mini
```

The adapter sends extracted text to the Responses API using a strict JSON schema and `store: false`. No PDF files or credentials pass from Next.js to an AI provider. The REST protocol, model, prompts, credentials, and response parsing are isolated in `ai/OpenAiDocumentAnalysisProvider`; orchestration depends only on `DocumentAnalysisProvider`. Provider HTTP responses and document text are not logged by the adapter. Tests mock provider responses and require no actual key or paid requests.

`actionRequired` is `true`, `false`, or `null` (shown as **Unclear**); `deadline` is an ISO date or `null` (shown as **No deadline identified**). Arrays are validated lists of strings. The model is instructed to use null for unsupported or ambiguous dates, including missing years. Structured output does not guarantee factual accuracy; the detail page always asks users to verify information against the original.

Analysis runs on demand and saves one immutable result per document. Repeat POSTs return the existing result, even if AI configuration later becomes unavailable. `createdAt` and `updatedAt` are identical on creation; there is no reanalysis/edit endpoint in this milestone. The original document upload status remains `UPLOADED`. No database transaction stays open during extraction or the provider request. In-process locks avoid concurrent duplicate analysis calls; the database unique constraint prevents duplicate rows across instances. Different instances can still make concurrent provider calls, so distributed job coordination remains future work.

PDF extraction supports readable, unrestricted PDFs up to 100 pages and 100,000 extracted characters. It rejects larger content rather than silently truncating it. Scanned/image-only, encrypted, restricted, or damaged PDFs return 422 with an explanation. OCR is not included. Missing stored files return 410. Missing/wrong-user documents return 404 before file access or provider invocation. Invalid/refused/incomplete AI responses return 502; connection/read failures return 504. Failed analyses save nothing and can be retried. These limits are not a hardened sandbox for hostile PDFs; public deployment would need isolated parsing workers.

`V2__document_analyses.sql` adds the analysis table, document foreign key, unique document constraint, nullable date/boolean, and JSONB arrays without changing the existing upload schema. Raw extracted text is processed in memory and is not added to the database or returned to the browser.

## Architecture

Browser → Next.js `/api` proxy → Spring controller → service → PostgreSQL + `DocumentStorage` interface. Storage is outside the public web directory and file paths are never returned to clients. Flyway migrations track schema changes. The `user_id` column is a required foreign key to a persistent user; migration V5 rejects legacy anonymous data rather than assigning it insecurely.

Analysis flow: authenticated user ownership check → stored PDF → `PdfTextExtractor` → `DocumentAnalysisProvider` → validated `AnalysisContent` → PostgreSQL `DocumentAnalysis` → document detail page.

File and database writes are not one distributed transaction: normal metadata failures trigger file cleanup; a process crash or cleanup failure can leave orphaned files. Add reconciliation and operational quotas before deployment. Confirmed deletion uses the durable retry strategy described below.

Reference documentation: [Next.js installation](https://nextjs.org/docs/app/getting-started/installation), [Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html), and [embedded PostgreSQL test setup](https://github.com/zonkyio/embedded-postgres).

Analysis reference documentation: [Apache PDFBox 3 migration](https://pdfbox.apache.org/3.0/migration.html) and [OpenAI structured outputs](https://developers.openai.com/api/docs/guides/structured-outputs).

## Milestone #4: compact document details

The document page now puts **At a glance** and **Your action plan** before the explanation. Task counts and the CSS progress bar update from the existing task list; the dashboard makes no extra task requests. Sample controls are grouped in a collapsed **DEVELOPMENT TOOLS** section, while sample dates and tasks remain explicitly labeled. Backend contracts are unchanged.

Verified in Chrome against the running backend and PostgreSQL: PDF upload, row navigation, sample generation, completion/reopening with progress updates, add/edit/delete, and persistence after refresh. Layout checked at 1440, 768, 390, and 320 pixels, including keyboard document navigation and task saving. All 39 backend tests, frontend lint, TypeScript checks, and the production build passed. No real AI requests were made.

## Milestone #5: saved reminders and upcoming deadlines

Each task has a **Reminders** disclosure. Incomplete tasks with a due date support 1-, 3-, and 7-day presets or a custom date. Presets are calculated by the backend, and the resulting calendar date is saved in PostgreSQL. Reminders can be edited, dismissed/restored, or deleted. These are saved preferences only: **no email, SMS, or push notification is sent**.

Reminder and due dates use `LocalDate` / SQL `DATE`. This milestone uses the **UTC calendar day** for past-date validation and dashboard day counts, with no conversion of stored dates to a different calendar date. Today is allowed; dates before today or after the task deadline are rejected. The same task/date cannot be duplicated, including dismissed reminders. Task locks and a database unique constraint protect concurrent creation.

Completing a task retains its reminder history but makes reminders inactive. Reopening restores actionability only for pending reminders whose dates are still valid. Changing/removing a task deadline never silently changes reminder dates: reminders outside the new deadline become inactive and can be edited or deleted. Deleting a task cascades to its reminders.

The dashboard lists up to 50 incomplete tasks with due dates, including overdue tasks, nearest first. A single projection query supplies document names and navigation IDs; there are no per-document task queries. Sample tasks remain labeled as development data.

Reminder APIs require the authenticated session cookie and, for mutations, a valid CSRF token:

- `GET /api/tasks/{taskId}/reminders`
- `POST /api/tasks/{taskId}/reminders` with `{"daysBefore":7}` **or** `{"reminderDate":"2026-10-08"}`
- `PUT /api/reminders/{id}` with the same date/preset structure
- `PATCH /api/reminders/{id}/status` with `{"status":"DISMISSED"}` or `{"status":"PENDING"}`
- `DELETE /api/reminders/{id}`
- `GET /api/tasks/upcoming`

Reminder responses include `actionable`, derived from reminder status, task status, and current dates. Invalid dates/presets return 400; duplicate dates or tasks unable to accept reminders return 409; missing and other-user resources both return 404. Migration `V4__reminders.sql` adds the reminders table and deadline-query index. Existing API contracts and upload behavior are preserved.

Milestone #5 verification: all 45 backend tests passed, along with frontend lint, TypeScript checks, and production build. Chrome verification covered the complete reminder lifecycle, refresh persistence, completion/reopening, upcoming-document navigation, overdue/today wording, mobile-width controls, and session isolation. No real AI or external notification calls were made.

## Milestone #6 verification

All **53 backend tests** pass, including registration validation, normalized duplicate emails, BCrypt database hashes, real login/logout and `/auth/me`, session-ID rotation, old CSRF token rejection, protected reads/mutations, account isolation across documents/analyses/tasks/reminders/development endpoints, and refusal to migrate anonymous records. The earlier domain tests now use authenticated persisted user fixtures; authentication tests use actual registration and login endpoints.

Frontend lint, TypeScript checks, and production build pass. The Chrome workflow registered A, uploaded TestRecord.pdf, loaded explicit development analysis, generated tasks, and saved a reminder; after logout, a fresh browser login recovered all data. A second account saw none of A's data and received 404 for direct reads and mutations using A's IDs. Replaying a logged-out session cookie failed. Mobile-width register/login/dashboard layouts, confirmation validation, HTTP-only/SameSite cookies, and CSRF rejection were also checked. No real AI or external notification calls were made.

## Milestone #7: secure document management

**Documents** now opens `/documents`, with account-scoped case-insensitive display-name search and four sort orders. Search treats `%` and `_` literally. Results are paged in groups of 50 with deterministic ordering; the dashboard retains its single upload area. `/settings` shows account information and privacy controls, expanded in Milestone #8 below.

Document details offer **View original PDF**, **Download PDF**, **Rename document**, and **Delete document**. The browser opens the original through the same-origin authenticated API, using its existing HTTP-only session cookie. PDFs are returned as `application/pdf`, with `nosniff`, private/no-store caching, safe UTF-8 Content-Disposition, and restrictions on framing/forms. Renaming only changes `displayName`; the sanitized original filename and random storage identifier are unchanged. Download filenames are sanitized separately and end in `.pdf`.

| Method | Endpoint | Behavior |
| --- | --- | --- |
| GET | `/api/documents?search=notice&sort=newest&page=0` | Owner-scoped list; `sort` also accepts `oldest`, `name-asc`, `name-desc` |
| GET | `/api/documents/{id}/file` | Original PDF, inline browser viewing |
| GET | `/api/documents/{id}/file?download=true` | Original PDF, attachment download |
| PATCH | `/api/documents/{id}` | `{displayName}`; nonblank, up to 255 characters, no control/format characters |
| DELETE | `/api/documents/{id}` | Confirmed deletion; 204 only after file cleanup and database commit |

Mutations require CSRF. Every endpoint derives ownership from the authenticated principal. Missing and wrong-owner IDs produce the same 404; unauthenticated reads return 401. The added `displayName` field is backfilled from `originalFileName` by V6; original response fields remain available. Upcoming deadlines use the current display name.

### Deletion and recovery

The UI requires a confirmation explaining that the PDF, analysis, tasks, and reminders will be permanently deleted. Cancel performs no mutation. The backend first commits `DELETION_PENDING` under an owner-scoped document lock. It then locks the record again, removes the stored file through `DocumentStorage`, and deletes the document in a database transaction. Existing foreign-key cascades remove analysis, task-generation history, tasks, and reminders; the User is never deleted.

A file cleanup error returns 503 with a retry message; no success is claimed. Logs identify only the document UUID, without storage paths or exception messages. The pending record remains visible in the owner's document list, while its file, analysis, tasks, reminder actions, and upcoming deadlines become unavailable. The detail page offers **Retry deletion**. If the process stops or a database failure occurs after the file was removed, the previously committed pending record still tracks the work. Retrying is safe because storage deletion is idempotent. A missing file is treated as already removed. No automatic cleanup worker or account-deletion feature is added.

Upload validation now parses PDF structure before storing. Malformed PDFs return a clear 400 response. Encrypted PDFs can still be stored and opened using the user's PDF password in their viewer; text analysis remains unsupported for encrypted/restricted documents. Storage accepts only internal randomized identifiers and does not follow file symlinks when reading. The storage abstraction remains independent of the local filesystem; S3 is not configured.

Development sample loading and generation require authentication, owner access, the dev profile, and the explicit mock flag. Production never exposes the development tools, even for previously saved sample analysis; saved sample data retains its provenance label.

Milestone #7 verification: **62 backend tests** passed, including file ownership, exact PDF bytes and headers, safe filenames, rename validation, literal case-insensitive search, all sort orders, full database/file cascade deletion, and retry after file or database failure. Frontend lint, TypeScript checks, and production build passed. Visible Chrome testing verified the native PDF viewer, original and renamed downloads, search/sort, rename after refresh, task/reminder creation, cancellation and confirmed deletion, another user's denied file/download/rename/delete attempts, and desktop/mobile-width Documents and privacy pages. No real AI or notification calls were made.


## Milestone #8: account management and security

Settings retains the existing green layout and adds a change-password form and a separate danger zone. Account deletion requires a password and the exact text `DELETE` inside a confirmation dialog; Cancel does not modify data. Only the signed-in account can be managed.

| Endpoint | Body | Successful response |
| --- | --- | --- |
| `PUT /api/account/password` | `currentPassword`, `newPassword`, `confirmNewPassword` | 204; sign in again |
| `DELETE /api/account` | `currentPassword`, `confirmation: "DELETE"` | 204; account permanently removed |

Both endpoints require the authenticated HTTP-only session and a fresh CSRF token. Validation runs independently on the backend: current password verification, nonblank passwords, matching confirmation, minimum 8 characters for new passwords, and BCrypt's 72 UTF-8 byte maximum. Responses never include credentials or hashes. Password DTO string representations are redacted.

**Session revocation:** changing a password increments a persisted security version and invalidates the current session. Every subsequent authenticated request checks the current database identity/version, rejecting and invalidating older sessions on any device. Deleting the user similarly revokes all other sessions on their next request. This does not cancel requests already executing. Login retains session-ID rotation and CSRF-token rotation. The frontend clears private state with a full navigation and broadcasts sign-out to other tabs; deliberate success redirects are protected against concurrent login checks.

**Deletion and recovery:** a committed `users.deletion_pending` marker fences new uploads before cleanup begins. Uploads acquire the same database user lock, so deletion includes uploads that were already in progress. Each document uses Milestone #7's durable deletion marker, idempotent storage deletion, and database cascades for analyses, task-generation ledgers, tasks and reminders. The user row is removed only after every document is gone; its foreign-key constraint also prevents accidental orphan records. HTTP 204 is returned only after the final commit.

If storage or database cleanup fails, the API returns 503 with a safe retry message. Already completed cleanup is permanent; remaining files stay tracked by pending document/account records. The account may sign in to retry **Delete permanently** from Settings, but normal document/task/analysis APIs and password changes are blocked. `/api/auth/me` reports `deletionPending` so the frontend routes a returning user to Settings. Restore storage/database availability, then retry deletion with the current password and `DELETE`. No background cleanup worker is installed: before production, monitor pending accounts and cleanup failures and establish an operational retry procedure. A retry never assigns or deletes another user's data.

**Security review:** BCrypt cost 12, authenticated API routes, per-resource ownership, session fixation protection, masked session CSRF, HTTP-only/Strict cookies, and same-origin access remain enabled. The production profile forces Secure cookies. No credentialed wildcard CORS is enabled. Authentication failures remain generic. Unexpected exception logs now record only the exception class; cleanup logs omit paths and exception contents. Do not enable request-body, SQL-bind, Spring Security TRACE, or document-text logging in a sensitive environment. Production still requires HTTPS and gateway rate limits for login, registration, password changes and deletion attempts; no Redis or in-memory limiter was added. Backups and infrastructure retention policies remain a deployment responsibility; deletion here covers the application's live database and local PDF storage.

Verification: **70 backend tests** passed, including all prior milestone regressions, password validation/hash/login/session revocation, bidirectional ownership, full account cascade cleanup, file and final-database failure/retry, pending-account upload rejection, and an in-flight upload/deletion race. Frontend lint, TypeScript checks and production build passed. Actual Chrome testing on `http://localhost:3001` verified password change, old-password rejection/new-password login, other-session revocation, a disposable user's PDF/mock-analysis/tasks/reminder workflow, mobile-width Settings, deletion cancellation/incorrect password/confirmation, success redirect, and deleted-credential rejection. A read-only PostgreSQL/filesystem audit confirmed all deleted records were absent, another user's data remained, and stored PDFs exactly matched surviving document records. No real AI calls were configured or made.


## Milestone #9: dashboard and user experience

The dashboard is an attention-focused home page with one unchanged secure PDF upload area, user-scoped summary counts, task progress, needs-attention items, upcoming deadlines, and five recent documents. **View all documents** opens the existing searchable/sortable Documents page. New accounts see a welcome message, upload area and one concise deadline explanation, without empty statistics or multiple empty sections.

`GET /api/dashboard` requires the current authenticated account and returns:

- `today`: the backend UTC calendar date used for a consistent snapshot.
- `documentCount`: all owned documents, including pending document deletions so cleanup remains visible.
- `openTaskCount` / `completedTaskCount`: TODO / COMPLETED tasks belonging to active owned documents.
- `upcomingDeadlineCount`: open tasks due after today through the next 30 days, inclusive; the UI states this window explicitly.
- `recentDocuments`: at most five, newest upload first, UUID tie-breaker.
- `needsAttention`: at most five overdue/due-today tasks, then unfinished tasks without dates; oldest due dates first.
- `upcomingDeadlines`: at most five open tasks in the upcoming window, nearest first.

Overdue/today tasks and upcoming deadlines are shown separately to avoid duplicate rows. Completed tasks never appear in either list. Counts are exact aggregate queries, not counts inferred from limited result lists. Queries are scoped to the account, list queries are bounded in the database, and a repeatable-read transaction keeps the response consistent. No dashboard cache, authentication changes or AI integration was added.

Document details now follow metadata → at a glance → action plan → explanation → original PDF controls. The prominent development-sample notice appears once near the sample analysis; generated sample tasks retain small labels and development tools remain collapsed. Forms retain labels, statuses include text, focus is visible, and existing native confirmation dialogs support keyboard dismissal.

Verification: **74 backend tests** passed, including dashboard counts, completed-task exclusion, overdue/today/upcoming boundaries, empty accounts, pending documents, bounded lists/newest-first ordering, and bidirectional account isolation. Frontend lint, TypeScript checks and production build passed. Chrome verification covered a populated account login, exact summary/progress values, deadline/recent-document navigation, keyboard activation, View all documents, upload-driven refresh, a separate empty account, error/retry behavior, and mobile-width Dashboard/Documents/Document Details/Login/Register/Settings. Task/reminder controls and confirmation-dialog Escape behavior were checked. Existing account/document data was retained; browser fixtures used disposable accounts. No real OpenAI calls were made.


## Milestone #10: production preparation

See [Deployment preparation](docs/DEPLOYMENT.md) for the environment-variable reference, production JAR/startup, TLS PostgreSQL, storage adapter plan, exact-origin CORS, cookie/CSRF topology, health endpoint and hosting upload-limit considerations. Local setup above remains supported. Nothing was deployed or purchased; OpenAI and cloud storage remain unconfigured.

`GET /api/health` returns only `{"status":"UP"}` publicly. `DOCUMENT_STORAGE_TYPE=local` selects the existing private local adapter; S3 is intentionally unsupported until implemented. `FRONTEND_URL` is one exact browser origin, and production requires HTTPS. Production configuration requires explicit database and storage settings, forces Secure cookies and suppresses sensitive diagnostic logging. Existing Flyway history and all business/security features remain intact.

Verification: **80 backend tests** passed with a clean build and runnable Boot JAR. All seven migrations initialized fresh isolated databases. Actual Chrome workflows verified fresh registration/login/PDF upload/mock analysis/tasks/reminder/logout/login persistence, plus a separate production-profile JAR behind local HTTPS with secure cookies, protected PDF access, CSRF rejection, 10 MiB upload rejection and development API/UI disabled. Frontend lint, TypeScript and production builds passed, including a build configured with a non-localhost backend target. The normal development account database and PDF directory were preserved.


## Milestone #11: receipts and expenses

**Receipts** is available in the sidebar at `/receipts`. Add a PDF, JPEG or PNG (10 MiB maximum) and manually enter merchant, purchase date, amount, category, optional description/notes, and the **potentially tax relevant** flag. No receipt values are extracted or invented, and no deductibility determination is made. Category may be left Uncategorized; merchant/date/amount are required. Purchase dates must be valid, not in the future, and in year 1900 or later.

Receipt detail pages support private viewing/downloads, editing metadata and confirmed permanent deletion. The list supports merchant/description search, category/year filters, purchase-date newest/oldest sorting, amount high/low sorting, and pages of 25. The selected-year cards and tax-organization totals cover all saved receipts for that year, independent of list search/category filters. Tax totals include only explicitly flagged receipts, group by category (including Uncategorized), and exclude deletion-pending records. The dashboard displays a small current-purchase-year count and link.

Money uses PostgreSQL `NUMERIC(14,2)` and Java `BigDecimal`; API amounts are decimal strings and frontend formatting preserves cents. **This milestone supports USD only**, positive amounts with up to two decimal places, and no currency conversion or refunds. Receipt photos are limited to 16 megapixels to bound decoding memory. Images are decoded/type-checked before storage; PDFs are signature/structure checked and encrypted receipt PDFs are rejected. Original bytes are retained, including any image metadata. This is not malware scanning or OCR.

`V8__receipts.sql` adds the account-owned receipt table and indexes without changing existing migrations. The existing storage abstraction now accepts backend-selected PDF/JPG/PNG extensions; keys remain randomized and paths remain private. Receipt deletion commits a durable pending marker before file cleanup, supports retry after filesystem/database failures, and never reports success before cleanup finishes. Account deletion now removes receipt records/files too and retains its pending-account fence against new uploads. All receipt operations derive ownership from the authenticated session.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/receipts` | Multipart `file` plus `metadata` part with `Content-Type: application/json` |
| `GET /api/receipts` | Optional `search`, `year`, `category`, `sort`, `page`; returns `items` and `hasMore` |
| `GET /api/receipts/summary?year=2026` | Exact selected-year counts, amounts and potentially relevant category totals |
| `GET /api/receipts/{id}` | Receipt metadata without user/storage identifiers |
| `PUT /api/receipts/{id}` | Validated replacement metadata; original file remains unchanged |
| `GET /api/receipts/{id}/content?download=true` | Authenticated original bytes; omit `download` for inline viewing |
| `DELETE /api/receipts/{id}` | Confirmed UI deletion; CSRF-protected backend cleanup |

Metadata fields: `merchant`, `purchaseDate` (YYYY-MM-DD), `amount` (decimal string), `currency` (defaults to USD), `category` (nullable), `description`, `taxRelevant`, `notes`. Category codes: `BUSINESS`, `MEDICAL`, `CHARITY`, `HOME`, `EDUCATION`, `TRANSPORTATION`, `MEALS`, `OFFICE`, `OTHER`. Sort values: `newest`, `oldest`, `amount-high`, `amount-low`. Tax missing-information counts cover merchant/date/amount and are normally zero because those fields are required; missing-category counts remain useful for uncategorized flagged receipts.

For future extraction, keep a separate `ReceiptExtractionService` that reads validated stored content and proposes typed values for user review. `ReceiptFileValidator`, `ReceiptInput`, storage and persistence are separate today; no unused OCR/AI adapter or automatic extraction was introduced. Files cannot be replaced during editing in this milestone; create a new receipt if its original file is wrong.

Verification: **94 backend tests passed**, including creation for all three formats, spoofed/unsupported/empty/oversized file rejection, metadata editing/validation, exact decimal and category/year totals, literal search/sorts/pagination, authentication/CSRF, bidirectional ownership, safe file deletion, filesystem/database failure retries, and complete account cleanup. Frontend lint, TypeScript checks and production build passed. Actual Chrome verification covered registration/login; PNG/PDF/JPEG upload and manual entry; image viewing and exact-byte download; editing; filtering/search/year/sorts; tax totals; mobile layouts; dashboard counts; logout/login persistence; cross-account denial; cancellation/deletion; and an existing document PDF upload. A read-only PostgreSQL/storage audit verified the deleted receipt was gone and all remaining document/receipt files matched their records with no orphan files. No AI calls, deployment or paid services were used.


## Milestone #12: tax organization and receipt export

Open **Receipts → Open tax organization** (`/receipts/tax`). Choose a purchase year (current UTC year by default) to see receipt counts, recorded amounts, potentially relevant counts/amounts, category totals, and records needing review. The receipt list supports All / Potentially tax relevant / Needs review filters, date/merchant/category/amount sorting, and pages of 25. Summary/category/review sections cover the full year, independently of the table filter.

New authenticated endpoints:

| Endpoint | Parameters / result |
| --- | --- |
| `GET /api/receipts/tax` | `year`; existing exact-decimal year/category summary plus `recordsNeedingReview` with reasons and detail links |
| `GET /api/receipts/tax/records` | `year`, `filter` (`all`, `tax-relevant`, `needs-review`), `sort` (`date-asc`, `date-desc`, `merchant`, `category`, `amount-asc`, `amount-desc`), `page` |
| `GET /api/receipts/tax/export` | `year`, `scope` (`all`, `tax-relevant`); downloadable `life-admin-receipts-YEAR.csv` |

Every query uses the session account, never a supplied user ID. Only saved receipts are included; deletion-pending receipts remain accessible through the ordinary receipt cleanup interface. All monetary totals still use PostgreSQL numeric and Java BigDecimal. No migration was needed.

Review reasons are factual: missing category, missing file, failed file size/format-signature check, or a file that could not be checked. Merchant, purchase date and amount remain mandatory in the schema/API, so no missing values are invented. Files are checked read-only without decoding entire PDFs/images; this is not a full corruption, authenticity, or malware scan. Review checks never modify receipts or their files. Records without purchase dates cannot currently be saved; no arbitrary year is assigned to them.

CSV contains exactly Purchase Date, Merchant, Amount, Currency, Category, Description, Potentially Tax Relevant, Notes, and Original File Name. It omits database/user identifiers and internal storage names/paths. Exports use UTF-8 with a BOM, CRLF record boundaries, quoted fields and doubled quotes. Formula-like text (including leading whitespace/control characters before spreadsheet operators) receives a leading apostrophe in the export to keep it literal; stored values are unchanged. Decimal amounts are emitted without scientific notation. Both export scopes include the entire selected year, independently of table pagination/filtering. Empty exports contain the header only.

**Print summary** invokes browser printing. Print CSS includes the Life Admin/year heading, summary counts/amounts, category totals, review records and concise disclaimer; navigation, controls, export buttons and the paginated receipt table are excluded. No server-side PDF generation was added.

Limits: USD-only/manual entry from Milestone #11 remains. Life Admin organizes records; it provides no tax advice, deductibility decisions, or tax calculation. Current review/export processing loads the selected account/year's metadata, and CSV is assembled in memory; high-volume streaming/background exports are future work. No OCR/AI, paid services, cloud storage or deployment was introduced.

Verification: **102 backend tests passed**, including exact large/decimal totals, year/category/tax-relevant totals, review reasons in isolated storage, CSV headers/escaping/formula neutralization, both export scopes, empty years, list filters/sorts/pagination and explicit bidirectional account isolation. Frontend lint, TypeScript and production build passed. Chrome verified login; discovery from Receipts; year changes and empty-year results; review-to-detail navigation; filters/all sorts; both downloaded CSV files and their parsed contents; print CSS/browser print output; mobile layout; account isolation; and logout/login persistence. Before/after read-only metadata/file fingerprints confirmed that all pre-existing receipts were unchanged. Only newly created test accounts/receipts were used for browser fixtures; no existing user receipt was edited or deleted.


## Milestone #13: receipt extraction and review

Historical milestone: the fixed receipt sample provider described below was removed in Milestone #15. See the current workflow below.

**Receipts → Add receipt → Upload receipt → Review receipt details → Save receipt.** Upload a PDF/JPEG/PNG first, preview the original when the browser supports it, enter or correct the details, then save. View/download links remain available. The review form includes merchant, purchase date, amount, currency, category, description, potentially tax relevant, and existing notes. USD remains the only supported currency; category, description and notes are optional. Saved receipts can be edited again in the same form.

V9 is an additive migration. It preserves existing receipt fields/files and marks previously manually saved receipts `REVIEWED`. A separate `reviewStatus` (`UPLOADED`, `NEEDS_REVIEW`, `REVIEWED`) leaves the existing `SAVED`/`DELETION_PENDING` file-cleanup lifecycle intact. File-only uploads have no invented merchant/date/amount. Required positive amount, valid nonfuture date from 1900 onward, nonblank merchant, supported currency and field lengths are validated by the backend on review. The existing multipart upload with complete `metadata` still works and saves those user-entered details as reviewed.

New/existing receipt API behavior (authenticated session and CSRF on mutations):

- `POST /api/receipts`: multipart `file`; optional `metadata` preserves the prior API. File only → `UPLOADED`.
- `PUT /api/receipts/{id}`: validated receipt details → `REVIEWED`.
- `GET /api/receipts/extraction-capabilities`: whether a backend extraction provider is available. The UI hides development tools when disabled/unreachable.
- `POST /api/receipts/{id}/extract`: stage suggestions for an owned, unreviewed receipt. Development only at present; returns 404 when no provider is configured, including production.
- `GET /api/receipts/{id}/extraction`: read persisted suggestions for the current owner's receipt.

`ReceiptExtractionService` is the provider boundary, with `ReceiptExtractionWorkflow` handling account ownership, locking, file checks and staging. `DevelopmentReceiptExtractionService` is enabled only for `dev & !prod` (even activating dev and prod together cannot enable it). It returns ShopRite / September 20, 2026 / 6.72 USD / Meals / Grocery purchase, with no tax flag selected. The separate **DEVELOPMENT TOOLS** section says **“This loads sample values for testing. It does not read the receipt.”** Values carry **“DEVELOPMENT SAMPLE — Not extracted from the actual receipt.”** No receipt contents are interpreted and there are no confidence scores.

Suggestions, source and timestamp persist separately from confirmed receipt details. Reloading an unreviewed receipt restores the suggestions; editing and saving confirms the user's values, preserving sample provenance. Sample loading cannot replace a reviewed receipt. Each extraction/review checks ownership before reading storage and refuses unavailable/invalid files. Existing authenticated viewing/downloading, CSRF, deletion retries and account cleanup remain in place. No file paths, owner IDs or stored filenames are accepted from the review form. A future provider can implement the same structured contract without changing review/persistence APIs; no OpenAI/OCR implementation or external call was added.

**Tax Organization** totals, category totals and CSV use reviewed values only and update immediately after saving. Unreviewed receipts appear in Records needing review. A draft without a purchase date is shown for review in its upload year, clearly excluded from totals/exports; saving assigns it to the confirmed purchase year. No purchase date is inferred from the upload time. Tax relevance is a user organization choice, never a deductibility determination.

Run checks:

```bash
./life-admin-api/gradlew -p life-admin-api test
npm --prefix life-admin-web run lint
npm --prefix life-admin-web run typecheck
npm --prefix life-admin-web run build
# Start local frontend/backend with backend profile dev first.
# Browser tests create NEW disposable accounts/receipts, leave them for inspection,
# and never edit/delete pre-existing receipts or use external extraction.
LIFE_ADMIN_TEST_URL=http://localhost:3001 npm --prefix life-admin-web run test:browser
```

The browser suite uses the free `playwright-core` dev dependency with installed Chrome; override `CHROME_PATH` on other machines. `LIFE_ADMIN_TEST_HEADED=1` opens visible Chrome. Artifacts default to `/tmp/life-admin-m13-browser` (override `LIFE_ADMIN_TEST_ARTIFACTS`). Tests require a local instance and refuse a remote URL.

Verification: **111 backend tests** passed, including migration from V8 with existing receipt data, review/suggestion persistence, corrections, validation, CSRF/ownership, invalid IDs/files, tax year/category/CSV updates, account deletion of drafts, and production exclusion. **3 frontend browser tests** passed through actual Chrome, covering PDF/sample and image/manual workflows, invalid fields, failed-save recovery, refreshed values, account isolation, CSV downloads and login persistence. Frontend lint, TypeScript and production build passed. Read-only before/after fingerprints confirmed all **9 pre-existing receipts' original fields and file bytes were unchanged**; only additive review columns were introduced.

Remaining limits: no real receipt reading; development sample values are fixed and not inferred. USD only. Native PDF previews depend on browser support, with authenticated view/download links available. Existing selected-year review/CSV processing remains in memory. No deployment, paid service or AI connection was made.


## Milestone #14: unified dashboard and Life Admin Inbox

The authenticated dashboard now answers **“What needs my attention?”** with five real account-scoped cards: Documents, Receipts, Open tasks, Upcoming deadlines and Receipts needing review. **Needs attention** shows up to eight items, with **View all** when more exist. **Inbox** (`/inbox`) joins Dashboard, Documents and Receipts in the sidebar and shows the full attention list in pages of 25. Filters: All, Documents, Receipts, Tasks and Deadlines; sorts: Most urgent first and Newest first.

`GET /api/dashboard` aggregates counts, the first eight unified `attention` items, `attentionCount` and up to five `recentActivity` items. Prior dashboard task/document fields remain for API compatibility. `GET /api/inbox?filter=all&sort=urgent&page=0` returns `items`, filtered `totalCount`, unfiltered `attentionCount`, `hasMore` and the UTC `today`. Supported filter values are `all|documents|receipts|tasks|deadlines`; sorts `urgent|newest`. Invalid filter/sort/page values return 400. Both endpoints derive ownership exclusively from the authenticated session; supplied owner parameters cannot change scope.

Attention rules are implemented in `InboxService`, not assembled by the frontend:

- One item per open task, combining its due date and earliest pending reminder. Completed tasks and their reminders are excluded. A reminder can bring a task forward without creating another item. Dates are explicitly labeled **Due** or **Reminder**; reminder reasons retain the task's due date when it has one.
- Urgency: overdue, due today, upcoming within 30 days, receipt awaiting review, later-dated tasks/deadlines, then other incomplete records. Stable ID tie-breaking makes pagination deterministic for unchanged data.
- Upcoming deadline counts cover tomorrow through UTC today + 30 days. For a task, its due date is counted preferentially, otherwise its earliest pending reminder; an earlier overdue reminder does not hide a still-upcoming task deadline. Each task contributes at most once. Actionable document-only deadlines are also included.
- `UPLOADED`/`NEEDS_REVIEW` receipts get one combined item including missing metadata. Saving as `REVIEWED` removes that reason. A reviewed receipt with missing category still has a single missing-information item. The receipt-review card counts review status only, not already-reviewed receipts missing a category.
- A document upload alone is never a problem. An actionable saved analysis without any task plan prompts the user to verify its suggested actions/deadline. Documents with task records, including completed plans, do not create duplicate/stale analysis alerts. Development sample origins remain visibly labeled.
- Incomplete deletion records link to existing cleanup controls; tasks on those documents stay excluded.
- Tasks with dates are also included in the Deadlines filter, but occur once in All. Documents includes standalone document/analysis items; tasks representing document actions are under Tasks. Receipt upload dates do not make receipts deadlines.

**Quick actions** offers Upload document, Add receipt and View tax organization. The existing PDF file validation, drag/drop, selection and upload API are reused in a dialog rather than another large dashboard form. Add receipt opens its existing upload flow directly. The same upload/add actions appear in an empty Inbox. When no attention exists the page says **“You're all caught up.”** Failed requests show retry controls rather than false zero counts. Task links target and focus the existing task inside its document detail page after async task loading.

**Recent activity** is a read-only projection of existing upload, receipt-created/updated and completed-task timestamps, maximum five. It is a snapshot, not an audit trail: receipt-review and completed-task dates use the record's latest save, so later edits can update those entries; reopening a task removes its completed entry. No historical event log or artificial activity is created.

No migration or new configuration was needed. The services use a fixed number of scoped SQL/JPA queries, with grouped reminders and no per-item entity/file lookups. Inbox sorting/filtering/pagination currently loads the account's actionable metadata in memory; very large accounts may eventually need database-side unified pagination. Receipt file integrity checks remain in receipt review/Tax Organization; the Inbox surfaces metadata/review issues, not a new file scan. Authentication, CSRF, private file access, task/reminder APIs, account deletion and existing tax/export/review functionality are unchanged.

Verification: **120 backend tests passed**, including the previous suite and nine Inbox tests for real counts, review/completion removal, urgency, deduplication, reminder/deadline behavior, actionable analysis, filters/sorts/pagination, bidirectional ownership, activity and empty states. **Five frontend Chrome browser tests passed** (including all receipt-review regressions): dashboard → Inbox → document/task/reminder navigation; PDF upload through Quick actions; receipt review and attention removal; tax navigation; filtering/sorting; logout/login persistence; isolated empty accounts; retry states; and desktop/mobile-width layouts. Frontend lint, TypeScript and production build passed. Only new disposable development fixtures were used; no OpenAI calls, deployment or paid service was involved.

Run the same checks listed in Milestone #13. `npm --prefix life-admin-web run test:browser` now includes both receipt-review and unified-Inbox suites. Inbox screenshots default to `/tmp/life-admin-m14-browser`. The tests require local development, create new disposable accounts/records and leave them for inspection. The read-only before/after audit captured 201 pre-existing database-row/file fingerprints. Stored files and all but two row fingerprints matched. One older task was marked completed at 2026-09-21 19:12:48 UTC; one older receipt was reviewed/updated at 19:44:49 UTC. Both belong to older accounts rather than newly created M14 browser fixtures. These live edits were left untouched; their origin has not been confirmed, so the whole-database preservation audit is not reported as a clean pass. No migration, reset, deletion or restoration was performed by this milestone.


## Milestone #15: real receipt text extraction

Receipt uploads now securely save the original file, then attempt local extraction automatically. PDFBox reads text-based PDFs and a conservative parser proposes merchant, purchase date, total amount, currency and category when identifiable. The receipt becomes `NEEDS_REVIEW`. The original preview stays beside **Review receipt details**; fields distinguish **Suggested by Life Admin** from **Confirmed by you**. Unknown fields remain blank with **Could not determine automatically.** Saving validated corrections marks the receipt `REVIEWED`; Tax Organization totals, category totals and CSV use those confirmed values.

This is actual text parsing, not a sample generator. Merchant headings/business-like first lines, labeled dates and labeled totals are supported. Ambiguous numeric dates, malformed or conflicting totals and uncertain currency are left unknown. A bare dollar sign does not imply USD. Category is suggested only from an explicit recognized category in the receipt, not guessed from the merchant. Description and tax relevance remain user choices.

JPEG/PNG photos and PDFs without readable text receive `OCR_REQUIRED`, with no invented suggestions. Unreadable/restricted PDFs receive `EXTRACTION_UNAVAILABLE`. No OCR or AI provider is connected; these files can still be reviewed manually. PDF extraction retains the existing 100-page/100,000-character limits. Receipt saving remains USD-only; other explicitly detected currencies are shown for review but cannot be saved or silently converted. Layouts and formats outside the conservative parser may require manual entry even when text is readable.

`ReceiptExtractionService` remains the provider boundary. `LocalReceiptExtractionService` combines PDFBox with `ReceiptTextParser`; the workflow owns account checks, file validation and persistence. It works in every profile without a key or external service. The fixed development receipt provider and its UI button have been removed. Existing document-analysis development tools are unchanged.

V10 adds separate `suggestedMerchant`, `suggestedPurchaseDate`, `suggestedAmount`, `suggestedCurrency`, `suggestedCategory`, `extractionStatus`, `extractionMethod`, `extractionTimestamp` and `extractionError` fields. Existing fields and files are preserved. Historical sample JSON is never loaded as real suggestions; previously reviewed sample-origin records retain a provenance warning. Extraction refuses reviewed receipts with 409 and never overwrites confirmed fields. Upload commits before extraction, so provider failure does not remove the original receipt. The older upload API with complete user-entered metadata still creates a reviewed receipt.

The existing authenticated/CSRF-protected APIs remain: file-only `POST /api/receipts` now attempts extraction automatically; `POST /api/receipts/{id}/extract` retries an owned unreviewed receipt; `GET /api/receipts/{id}/extraction` returns persisted suggestions. Account ownership is checked before reading the file. Viewing, downloading, deletion cleanup, Dashboard, Inbox, Documents, tasks and reminders remain intact.

Verification: **159 backend tests passed**, including real PDF extraction, missing/ambiguous fields, malformed amounts, OCR-unavailable files, failed-provider recovery, corrections, reviewed-value protection, migration preservation, validation, account isolation, tax totals and CSV. **Six actual Chrome browser tests passed**, covering upload → suggestions → correction → save, logout/login persistence, blank uncertain fields, photo/manual review, account isolation, and Dashboard/Inbox regressions. Frontend lint, TypeScript checks and production build passed. A read-only before/after audit confirmed all **265 pre-existing database-row/file fingerprints unchanged**.

Run the checks listed under Milestone #13. Receipt browser artifacts now default to `/tmp/life-admin-m15-browser`. Fixtures in `docs/fixtures` are test inputs only: `receipt-text.pdf` contains fictional readable receipt text; `receipt-uncertain.pdf` contains ambiguous/malformed values; `receipt-scanned.pdf` is a blank PDF exercising the no-readable-text path. No extraction rule depends on these filenames or hard-coded fixture values. No deployment, paid service or OpenAI connection was made.


## Milestone #16: backend AI receipt-photo extraction

JPEG/PNG uploads now use `OpenAiReceiptExtractionProvider` through `ReceiptExtractionService` routing. Spring Boot sends the actual stored image as an `input_image` data URL to **POST https://api.openai.com/v1/responses**, with a strict JSON schema, `store: false`, bounded output and no application retries. Readable PDFs continue using PDFBox/local parsing; scanned/image-only PDFs remain `OCR_REQUIRED`. No upload is sent from the browser to OpenAI.

Backend configuration reads `OPENAI_API_KEY` through `app.ai.openai.api-key` and `OPENAI_RECEIPT_MODEL` through `app.ai.openai.receipt-model` (default **gpt-5.4-mini**). The key is neither returned by an API nor logged. Image base64 exists only during request processing; full raw responses are not saved. V11 adds only nullable `extraction_model`; prior data remains untouched. Existing statuses are reused: `EXTRACTED`, `PARTIAL` or `NO_MATCH` with method `OPENAI_IMAGE`; missing configuration gives `OCR_REQUIRED`; HTTP/network/malformed-response failures give `EXTRACTION_UNAVAILABLE` and a safe manual-entry message. All these outcomes leave the stored receipt available for review.

The provider treats responses as untrusted. It rejects malformed structured output; invalid field values become null. Merchant is trimmed and length-limited, dates must be valid/unambiguous as instructed and in the allowed range, amounts must be positive decimal values fitting the existing money column, currencies use recognized ISO codes, and categories must belong to the existing enum. No replacement values, confidence scores or tax-deductibility conclusions are invented. USD-only saving remains unchanged.

Suggested fields stay separate from confirmed fields. The original receipt remains visible beside editable suggestions; **Save receipt** confirms the user's values. Reviewed receipts reject extraction before provider invocation. Ownership, CSRF, private content/downloads and deletion cleanup remain enforced. An existing unreviewed photo can be retried explicitly with **Read receipt details** after configuration is available. The Next.js proxy allows the backend's bounded AI timeout to return a safe fallback.

Verification: **204 backend tests passed**; frontend lint, TypeScript checks and production build passed. **Seven Chrome browser tests passed**: the six existing receipt/Dashboard/Inbox regressions plus an isolated mocked-HTTP photo workflow covering suggestions, correction, saving, refresh, login persistence, ownership, deletion and failure/manual review. Automated Gradle tests clear inherited API credentials. The dedicated test-classpath-only browser harness substitutes HTTP responses and cannot be packaged as production code. Mock responses are test evidence for the integration path, not evidence of live AI accuracy. All **304 pre-existing row/file fingerprints matched** after the additive migration and verification.

**Live API calls made during implementation: 0.** The development tool process did not inherit `OPENAI_API_KEY`; the current preview remains in manual-photo-review mode. The real image request path is implemented, but a live provider result has not been verified. Follow [the one-receipt live test instructions](docs/REAL_RECEIPT_TEST.md) from the terminal that owns your exported key. No API key was printed or placed in source, frontend code or Git. No deployment or reset was performed.

Official references: [image inputs](https://developers.openai.com/api/docs/guides/images-vision), [structured output](https://developers.openai.com/api/docs/guides/structured-outputs), and [GPT-5.4 mini capabilities](https://developers.openai.com/api/docs/models/gpt-5.4-mini).


## Milestone #17: smart receipt capture and review

Receipt capture now offers a browser camera control alongside existing photo/PDF selection, a clear “Reading your receipt…” state, guarded upload submissions and an explicit confirmed manual retry. Automatic extraction remains backend-owned; a new account-scoped upload request ID prevents duplicate submissions from creating another receipt or extraction. Version checks and a cooldown protect manual retries. Refresh only reads metadata.

See [the Milestone #17 report](docs/MILESTONE_17.md) for the complete changed-file list, flow, verification and remaining limits. All 231 backend tests and six relevant mocked Chrome tests passed, along with lint, TypeScript and production build. Existing live data/files were untouched and real OpenAI requests made during implementation/testing were **zero**. Restart the backend to load the safeguards and additive V12 migration. No Milestone #18 work was started.


## Milestone #18: smarter receipt understanding and review

The existing image request now returns an optional short purchase description and uses visible items/services to guide the existing category choices. Both remain editable suggestions; uncertainty stays null rather than being filled with guesses. V13 adds only nullable `suggested_description`, preserving confirmed descriptions and all historical data. Tax relevance remains user-controlled. Existing duplicate/retry safeguards, local PDF extraction and camera capture remain unchanged.

See [the Milestone #18 report](docs/MILESTONE_18.md) for all changed files, migration, validation and controlled manual checks still needed. Verification passed: **240 backend tests**, **six relevant mocked Chrome tests**, frontend lint, TypeScript and production build. Existing live data/files were untouched; **zero real OpenAI API requests** were made. Restart Spring Boot to load the new schema/prompt and additive migration before any separately authorized live test.
