# Milestone #17 — Smart Receipt Capture & Review UX

## Flow

Take a photo (using the browser camera control) or choose a JPEG/PNG/PDF → Upload receipt → “Reading your receipt…” → original receipt and editable suggestions → Save receipt → Confirmed by you.

The existing backend upload flow still commits the original file and metadata before extraction. There is no extra frontend extraction POST on successful upload. Text PDFs use PDFBox; image-only PDFs remain available for manual review. Extraction failure keeps the file and shows the existing friendly message. “Try reading again” opens an explicit confirmation explaining that it makes another attempt and replaces suggestions. Users can continue entering details manually instead.

## Duplicate/cost protection

- A synchronous frontend ref blocks multiple submissions before React has rendered the disabled state.
- The UI sends an `Idempotency-Key` UUID for each upload action, retaining it for a retry after a failed response. Pending file identity and the request ID are retained in account-scoped session storage where supported; no file bytes are stored there.
- Additive migration V12 introduces nullable `upload_request_id` and a unique account/request index. Existing rows remain unchanged. The existing account lock serializes creation: duplicate submissions return the original receipt, without storing another file or starting another extraction.
- Only the request that created the receipt starts automatic extraction. A locked automatic-attempt guard skips already attempted or reviewed receipts.
- Review-page refresh/render only reads receipt metadata. If an original request is still finishing, bounded GET polling refreshes its status; it never calls extraction.
- A manual retry sends the reviewed form's current receipt version through `If-Match`. Under the receipt lock, duplicate/stale versions return the current result without another provider call. A new version cannot trigger another retry within 30 seconds of the preceding result. The UI also guards concurrent clicks and requires confirmation.
- Reviewed values cannot be overwritten by extraction. No automatic provider retry loop was added.

## Validation

- **231 backend tests passed**, including JPEG/PNG automatic extraction, duplicate and concurrent submissions, failed-extraction preservation, account-scoped tokens, GET refresh safety, manual retry versions/cooldown, reviewed-value protection, additive migration preservation, PDF behavior, account isolation, Dashboard/Inbox and existing receipt regressions.
- **Six relevant mocked Chrome tests passed**: two capture/review tests, three all-years filter tests, and the existing isolated HTTP-mocked end-to-end photo test (correction, refresh/login persistence, deletion, failure/manual review).
- Capture tests intercept all API calls and check double submissions, explicit retry confirmation, disabled/processing UI, image preview, camera attributes and a 390-pixel viewport.
- Frontend lint, TypeScript and production build passed.
- **Real OpenAI calls: ZERO.** The live backend was not restarted, and its existing database/files were not modified. Backend tests and the HTTP-mocked browser harness use disposable databases/files. No existing receipt was retried or uploaded as a test.

## Files changed

Backend:
- `life-admin-api/src/main/java/com/lifeadmin/controller/ReceiptController.java`
- `life-admin-api/src/main/java/com/lifeadmin/controller/ReceiptExtractionController.java`
- `life-admin-api/src/main/java/com/lifeadmin/service/ReceiptService.java`
- `life-admin-api/src/main/java/com/lifeadmin/service/ReceiptExtractionWorkflow.java`
- `life-admin-api/src/main/java/com/lifeadmin/repository/ReceiptRepository.java`
- `life-admin-api/src/main/java/com/lifeadmin/entity/Receipt.java`
- `life-admin-api/src/main/resources/db/migration/V12__receipt_upload_request.sql`

Frontend:
- `life-admin-web/src/app/receipts/receipt-upload.tsx`
- `life-admin-web/src/app/receipts/receipt-form.tsx`
- `life-admin-web/src/app/receipts/[id]/receipt-detail.tsx`
- `life-admin-web/src/lib/receipts.ts`
- `life-admin-web/src/app/globals.css`

Tests:
- `life-admin-api/src/test/java/com/lifeadmin/ReceiptAiIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/ReceiptReviewIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/ProductionConfigurationIntegrationTest.java`
- `life-admin-web/tests/receipt-capture.test.mjs`

Documentation:
- `README.md`
- `docs/MILESTONE_17.md`

## Limits and startup

Restart Spring Boot from the configured terminal to load the new safeguards and additive V12 migration. No startup operation retries receipts. The live backend was deliberately left running without applying this change during testing.

Browser camera behavior depends on the device/browser. Physical-device camera hardware was not tested; desktop file selection remains available. HEIC files are not supported or converted—use JPEG/PNG. No native application was added.

Extraction remains synchronous and bounded by the existing provider timeout. If processing is interrupted or its response is lost, the original may remain saved without suggestions; use the existing receipt/manual review rather than creating another upload. Session storage may be unavailable in restricted browsers; the in-memory guard remains but cannot retain an upload key across a full reload in that case. Selecting a genuinely new file/action creates a new receipt; this is request deduplication, not file-content deduplication.

The new UI always sends the protective headers. Legacy API callers that omit `Idempotency-Key` or `If-Match` retain their prior behavior and must adopt these headers to get the same safeguards. Repeated deliberate, confirmed retries after the cooldown are separate attempts; no hard per-account spend cap was added.

Milestone #18 was not started.
