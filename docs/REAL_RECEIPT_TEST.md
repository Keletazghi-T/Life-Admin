# One controlled receipt-photo test

The real Spring Boot → OpenAI image request path is implemented. During Milestone #16, automated tests used mocked HTTP and **zero real API calls** were made. The tool-launched preview does not inherit your terminal's `OPENAI_API_KEY`. Mock browser results do not establish live model accuracy.

## Start the existing preview from your configured terminal

Use the terminal where your key is already **exported**. Do not paste the key into code, a command argument, screenshots or chat. Verify presence without printing its value:

```sh
if test -n "${OPENAI_API_KEY:-}"; then
  echo 'OPENAI_API_KEY is present'
else
  echo 'OPENAI_API_KEY is missing in this terminal'
fi
```

Stop only the existing Life Admin preview backend before restarting it. It listens on port 8084. If it was started from your terminal, use Ctrl-C there. For the tool-launched preview, inspect the listener, then terminate that process:

```sh
lsof -nP -iTCP:8084 -sTCP:LISTEN
# After verifying this is the Life Admin Java preview:
kill -TERM "$(lsof -tiTCP:8084 -sTCP:LISTEN)"
```

Wait for that process to exit. Then run from the same configured terminal:

```sh
cd /Users/keletazghitesfazghi/Documents/Life-Admin
export OPENAI_API_KEY
export OPENAI_RECEIPT_MODEL=gpt-5.4-mini
LC_ALL=C LANG=C ./life-admin-api/gradlew -p life-admin-api receiptPreview --no-daemon --console=plain
```

`receiptPreview` reopens the existing `.local/accounts-preview/postgres` database and `.local/accounts-preview/uploads` files without resetting either. It uses port 8084 and the frontend at **http://localhost:3001**. Unlike the earlier preview launcher, it does not override the API key with an empty property. The startup message reports credential presence without displaying it. Keep this terminal running. If the key is absent, the application still starts and manual review remains available.

If you normally use a separate PostgreSQL installation rather than this workspace's embedded preview, keep your existing `DATABASE_URL`, `DATABASE_USERNAME`, `DATABASE_PASSWORD` and `DOCUMENT_STORAGE_LOCAL_PATH` configuration and use `bootRun --no-daemon` instead. Do not switch database/storage paths to conduct the test.

## Make exactly one image request

1. Open **http://localhost:3001**, log in, and open Receipts.
2. Upload **one** file: `docs/fixtures/receipt-photo.png` **or** `receipt-photo.jpg`, not both. These are clearly labeled fictional test receipts with no private purchase data.
3. Upload automatically makes one application-level Responses API request if configured. Do not re-upload or click **Read receipt details** again during this controlled test. There is no application retry loop.
4. Check the original next to the suggestions. The fixture visibly says Oak Cafe, September 20, 2025, and TOTAL USD 6.72. Those are comparison facts printed in the image, not values injected into the provider implementation. A category is a suggestion and may be blank.
5. Confirm the receipt is **Needs review**. Its authenticated API representation should have `extractionMethod: OPENAI_IMAGE`, the configured `extractionModel`, and suggestions in `suggested*` fields. A successful result uses `EXTRACTED` or `PARTIAL`; `NO_MATCH` means no reliable fields were returned. HTTP or parsing failure uses `EXTRACTION_UNAVAILABLE` and allows manual entry.
6. Correct a suggested value, click **Save receipt**, and confirm **Reviewed**. Refresh, log out, log in, and reopen it to verify the corrected value persisted. These actions do not trigger more extraction calls.
7. If the first call fails, stop and inspect configuration/account access before making another paid request. Do not share provider credentials or private diagnostics. Report the safe status/message and whether a request was attempted.

Text PDFs still use PDFBox without calling OpenAI. Scanned PDFs remain manual-review-only. Existing reviewed receipts cannot be overwritten by extraction. Receipt saving remains USD-only; no automatic conversion or tax-deductibility determination is performed.

## Reproduce mocked browser checks without credits

The ordinary backend test task clears inherited API credentials. The isolated photo browser harness also replaces HTTP and uses a disposable database, never the retained preview database:

```sh
# Terminal 1: test-only backend on port 8085
./life-admin-api/gradlew -p life-admin-api receiptAiBrowser --no-daemon

# Terminal 2: production frontend pointing only at that mocked backend
BACKEND_URL=http://127.0.0.1:8085 npm --prefix life-admin-web run build
npm --prefix life-admin-web run start -- --port 3002

# Terminal 3
npm --prefix life-admin-web run test:browser:ai
```

The AI browser test refuses a backend without the test-only mock marker. Stop the isolated servers after testing. Rebuild with your normal `BACKEND_URL` before using the production build elsewhere. The existing `test:browser` receipt regression suite requires the main preview to have the API key unset and refuses configured image extraction to avoid credits.

## Safe failure diagnostics (added after the first live attempt)

Restart the backend with the updated code **before a separately authorized second test**. This diagnostic change does not retry any receipt, make an API call at startup, or recover missing details from the first request.

Failures now write one WARN entry prefixed `receipt_extraction_failure` to the **Spring Boot backend terminal/log**, not the browser. It contains:

- UTC timestamp and configured model identifier.
- Failure stage: `HTTP_ERROR`, `NETWORK_ERROR`, `RESPONSE_PARSE_ERROR`, `INVALID_STRUCTURED_OUTPUT`, `REQUEST_ERROR`, or `INPUT_VALIDATION_ERROR`.
- HTTP status when a response was received (including 200 for malformed successful responses).
- OpenAI error code/type only when they match a fixed allowlist of known diagnostic identifiers. Unknown values are omitted (`null`), never copied verbatim.
- `x-request-id` only when it matches the validated `req_` plus 32 hexadecimal characters format. Other formats are omitted.

There are no raw response bodies, provider error messages, exception messages/stack traces, authorization headers, credentials, image/base64 data, receipt fields or user identifiers in these diagnostic entries. Error-body reading is bounded. The browser retains the same friendly failure message. No database schema/data changes were needed; these additional diagnostics are **logs only**, not receipt API fields.

To retain the next backend session's logs locally, use this instead of the earlier startup command, from your configured terminal after stopping the existing backend:

```sh
LC_ALL=C LANG=C ./life-admin-api/gradlew -p life-admin-api receiptPreview --no-daemon --console=plain 2>&1 | tee -a .local/receipt-preview.log
```

`.local/` is Git-ignored. Starting the backend does not perform extraction. After a separately authorized controlled attempt, find its diagnostic entry with:

```sh
rg 'receipt_extraction_failure' .local/receipt-preview.log
```

A null status means no HTTP status was captured, not proof that OpenAI never received the request. A null error code/type means unavailable, unrecognized, or omitted for safety. Interpret the recorded evidence before deciding whether a key, billing, model, schema or network change is needed. No automatic retry has been added.

### Network failure details

For `stage=NETWORK_ERROR`, updated backend logs also include `exceptionClass`, `rootCauseClass` and `networkCategory`. Categories distinguish `TLS_CERTIFICATE_ERROR`, `TLS_ERROR`, `DNS_ERROR`, `CONNECT_TIMEOUT`, `TIMEOUT`, `CONNECTION_REFUSED`, `CONNECTION_RESET`, `CONNECTION_FAILED`, `NO_ROUTE_TO_HOST`, `BROKEN_PIPE`, `CONNECTION_CLOSED`, `INTERRUPTED` and the fallback `NETWORK_IO_ERROR`.

The classifier inspects a bounded cause chain and maps exception types (and selected known socket-message phrases) to these fixed categories. It never logs the original exception message, any message substring, or a stack trace. Unknown failures retain only their class names and the generic category. A null HTTP status still cannot establish whether the remote service received the request.

Restart **only the Spring Boot backend** using the configured terminal and the `receiptPreview` command above to load the new diagnostics. Next.js does not need a restart for this backend-only change. Restarting does not retry stored receipts. After restart, perform exactly one separately authorized controlled extraction, then inspect its `receipt_extraction_failure` entry before taking further action. Do not disable certificate verification to work around TLS failures.

## Receipt purchase-date normalization clarification

The original image instructions required a confidently identified complete date and known year but did not explain two-digit receipt years or supported source formats. The schema had no date-format guidance. The backend already accepted a valid ISO `2026-09-22`; it would discard a raw `09/22/26`. Because raw responses are intentionally not retained, the prior missing suggestion cannot be attributed conclusively to a model null versus an unnormalized response.

The instructions/schema now explicitly recognize MM/DD/YY, MM/DD/YYYY, YYYY-MM-DD and written-month dates. They prefer the actual transaction date over coupon/card expiry, return deadlines, unrelated delivery/order dates and reprints. For clearly contemporary receipts, years 00–49 use 2000–2049 unless receipt evidence contradicts that century; unclear centuries, historical contexts and unsupported 50–99 interpretations remain null. `09/22/26` is the explicit contemporary example → `2026-09-22`; `03/04/26` remains null unless explicit receipt evidence resolves date order. The backend's strict ISO validation, valid-date/range checks, retry safeguards and reviewed-value protection are unchanged. No migration or existing-data update is involved.

Mocked tests supply generated image bytes containing these printed dates and return controlled structured responses. They verify the prompt/schema, actual image transport and ISO result handling; they do not prove live OCR/model interpretation. No real OpenAI call was made for this change. Another live test is **not necessary to complete automated validation**. A separately authorized single controlled test would be needed to confirm the updated model behavior on the previously problematic image. Do not retry it automatically; manual date entry remains available without another API call. Restart Spring Boot before any such authorized test to load the prompt/schema changes.

## Document-analysis safe diagnostics (after Milestone 20)

Document analysis now emits `document_analysis_diagnostic` log entries and retains the latest safe snapshot in `document_analysis_attempts.diagnostics`. These diagnostics are not returned by the browser-facing attempt endpoint. The existing model, prompt, retry controls, and user-facing messages are unchanged.

Migration `V15__document_analysis_diagnostics.sql` adds one nullable JSONB column. It does not backfill or reinterpret old failures. In particular, the earlier Invoice.pdf failure remains historically unknown; this change does not retry that document.

Every snapshot includes the UTC timestamp and analysis attempt UUID, plus:

- `extractionCompleted`: PDFBox returned extracted text (separate from passing the existing minimum-text check).
- `httpRequestStarted`: the adapter began HTTP dispatch; this does not prove provider receipt or billing.
- HTTP status when received, allowlisted OpenAI error code/type, validated request ID, and sanitized model identifier.
- `stage`: `PRE_REQUEST`, `HTTP_REQUEST`, `HTTP_RESPONSE`, `RESPONSE_PARSE`, `RESPONSE_VALIDATION`, or `PERSISTENCE`.
- `outcome`: progress, failure, or successful persistence.
- Safe exception/root-cause classes and a fixed exception category; no exception messages or stack traces.

The snapshot retains HTTP metadata when later parsing, validation, or saving fails. Both provider schema/evidence validation and service validation are covered. Progress snapshots commit independently of the analysis save transaction so a save rollback does not remove the diagnostics. If diagnostic storage itself is unavailable, `document_analysis_diagnostic_storage_failure` is logged using safe metadata only; the original outcome is not replaced by a diagnostic-write error.

No key, Authorization header, extracted text, prompt, raw response, filenames, or account details enter the diagnostic snapshot. The attempt context is cleared after completion so pooled request threads cannot attach one attempt's diagnostics to another.

To inspect the next **separately authorized** controlled attempt, use its attempt UUID:

```sql
SELECT attempt_id, status, started_at, finished_at, diagnostics
FROM document_analysis_attempts
WHERE attempt_id = '<analysis-attempt-uuid>';
```

For retained backend logs, search for `document_analysis_diagnostic` and that UUID. Stage transitions appear in logs; the database holds the latest snapshot. Do not enable request/body tracing. Restart the Spring Boot backend through the usual local development procedure before the next test so V15 and the diagnostics code are loaded. No frontend restart or model/prompt change is required for this backend-only improvement.

Implementation validation: all **279 backend tests passed**, using disposable databases/storage and mocked providers/HTTP. Coverage includes success, missing key, HTTP 400/401/403/404/429/500, network timeout, malformed output, schema/evidence and service validation failures, persistence failure, sensitive-value exclusion, and context isolation. Existing local database records and uploaded files were not modified; the live backend was not restarted. Real OpenAI API requests during this diagnostic change: **ZERO**.

Changed files:

- `life-admin-api/src/main/java/com/lifeadmin/ai/DocumentAnalysisDiagnostic.java`
- `life-admin-api/src/main/java/com/lifeadmin/ai/OpenAiDocumentAnalysisProvider.java`
- `life-admin-api/src/main/java/com/lifeadmin/service/DocumentAnalysisService.java`
- `life-admin-api/src/main/resources/db/migration/V15__document_analysis_diagnostics.sql`
- `life-admin-api/src/test/java/com/lifeadmin/ai/OpenAiDocumentAnalysisProviderTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/AnalysisIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/ProductionConfigurationIntegrationTest.java`
- `docs/REAL_RECEIPT_TEST.md`

### Validation reason codes

Document diagnostics now include nullable `validationReason` and `validationField` values in the existing JSONB snapshot and safe stage logs. Both are enum-allowlisted. The first reason is retained through exception wrapping. `validationField` is a fixed root field name, never a rejected value, excerpt, validation message, or arbitrary JSON property name.

Response checks distinguish `INVALID_RESULT_SHAPE`, `MISSING_REQUIRED_FIELD`, `INVALID_FIELD_TYPE`, `INVALID_ACTION_REQUIRED`, `INVALID_DEADLINE`, `INVALID_IMPORTANT_DATE`, and `SOURCE_EXCERPT_MISMATCH`. Service validation additionally distinguishes `REQUIRED_VALUE_BLANK`, `FIELD_LENGTH_EXCEEDED`, `LIST_LIMIT_EXCEEDED`, and `DATE_YEAR_OUT_OF_RANGE`. Envelope/parsing checks can report `MALFORMED_JSON`, `RESPONSE_TOO_LARGE`, `RESPONSE_NOT_COMPLETED`, or `RESPONSE_REFUSED`. Non-validation failures keep these fields null.

When several service constraints fail, diagnostics choose one violation deterministically. The validation result, HTTP response, source matching, schema, prompt, model and acceptance limits are unchanged. No new migration is necessary. Historical attempts are not backfilled and cannot reveal their previously unrecorded rejection reason.

Changed files for this follow-up: `DocumentAnalysisDiagnostic.java`, `OpenAiDocumentAnalysisProvider.java`, `DocumentAnalysisService.java`, their provider/integration test files, and this document. Tests use mocked responses and isolated database fixtures, and assert that rejected content never appears in the diagnostic snapshot.

Restart the backend to load these additions before any separately authorized controlled test. Invoice.pdf was not retried, the live backend was not restarted, and existing user data/files were untouched. Real OpenAI API requests made: **ZERO**.

### Conservative document evidence filtering

Document source matching now happens in `DocumentAnalysisService` after the complete
provider result passes the existing structural, Bean Validation, field/list limit,
and date/year checks. `DocumentEvidenceFilter` compares literal substrings after
collapsing whitespace (including Unicode space characters and PDF line breaks),
trimming boundary whitespace, and mapping curly quotes/apostrophes and Unicode
hyphen/nonbreaking hyphen to their ordinary equivalents. It preserves case,
words, numbers, accents, and other punctuation; it does not use semantic/fuzzy
matching, dehyphenation, or remove arbitrary characters. En/em dashes and
mathematical minus signs remain distinct.

Unverifiable individual evidence excerpts are omitted. Important-date entries
with unverifiable supporting excerpts are omitted entirely. A structurally valid
analysis may therefore succeed with empty evidence/date lists. Other fields,
including the separate deadline suggestion, remain suggestions requiring review;
matching a quotation does not independently verify the model's interpretation.
Original accepted excerpt text is retained, not rewritten. No rejected content
is logged, and existing safe failure diagnostics remain for validation failures.

Changed implementation: `OpenAiDocumentAnalysisProvider`, `DocumentAnalysisService`,
and new `DocumentEvidenceFilter`. Updated mocked HTTP tests in
`OpenAiDocumentAnalysisProviderTest` and isolated persistence/validation tests in
`AnalysisIntegrationTest`. No migration, prompt/model change, or frontend change.
Existing data/files and Invoice.pdf remain untouched; no live analysis was retried.
Real OpenAI requests during implementation/testing: ZERO. Restart the backend to
load the change before any separately authorized controlled live test.

Verification: full backend suite passed, 288 tests, zero failures/errors/skips.
Coverage includes actual PDFBox multiline text, repeated/Unicode whitespace,
exact and typographic excerpts, unsupported/mixed evidence, persistence/reuse,
and strict validation before filtering. No frontend code changed.

### Content-grounded document classification

Updated `OpenAiDocumentAnalysisProvider` instructions and the `documentType`
description in `ai/document-analysis.schema.json` to request a short human-readable
classification from headings, stated purpose, and contents. Prefer the most
specific supported type (or a supported broader classification). Reserve Unknown
for genuinely unclassifiable content; missing dates or unclear actions alone do
not justify Unknown. Generic examples are illustrative, not a fixed list or a
jurisdiction-specific rule. A request/payment receipt must not be mistaken for
an actual record-check result or legal status.

No validator, evidence filter, model, receipt extraction, or stored analysis was
changed. No migration or automatic reanalysis. Restart the backend for future
separately authorized analyses to use the new guidance. Existing Unknown analyses
remain unchanged. Mocked provider tests verify outbound prompt/schema guidance,
strict schema constraints, and round-trip classifications including Unknown.
Mocks do not measure the live model's classification accuracy.

Guidance reference: [OpenAI Structured Outputs documentation](https://developers.openai.com/api/docs/guides/structured-outputs).
Real OpenAI API requests made during this change: ZERO.
Validation: complete backend suite passed — 293 tests, zero failures/errors/skips.
