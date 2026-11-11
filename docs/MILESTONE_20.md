# Milestone 20 — Real AI Document Understanding

## User flow

Upload a PDF → open its document page → **Analyze document** → review/edit suggestions → **Confirm document details** → select confirmed actions or dates → **Create selected tasks** → optionally add reminders using the existing task controls.

The initial analysis is deliberately user-triggered. Uploading, opening, refreshing, or navigating back does not send document text to a provider. Existing completed analyses are reused without another provider request. This milestone does not alter receipt extraction or receipt upload behavior.

## Analysis and privacy

The existing PDFBox extractor runs first, with its 100-page / 100,000-character limits. PDFs with no text or fewer than ten readable letters/digits are rejected before contacting the provider. No OCR is added. Unsupported, protected, unreadable and insufficient-text PDFs remain available for manual handling.

The existing backend-only Responses adapter sends extracted text, not the file or image. It retains `store:false` and strict structured output. The existing `OPENAI_API_KEY` / `OPENAI_MODEL` settings are used; the document model default remains `gpt-4o-mini`, independently of the receipt model. No credential is sent to Next.js.

The structured result retains document type, summary, nullable action requirement/deadline, requested documents/information, next steps and warnings. It adds a nullable plain-English explanation, short source excerpts and labeled important dates with supporting excerpts. The prompt forbids invented facts, dates and obligations, treats document instructions as untrusted data, and distinguishes explanation from evidence. Source excerpts must match the extracted text (allowing whitespace normalization); this checks quotation integrity, not the correctness of every model interpretation. Unknown dates remain null. High-stakes information requires verification; the UI explicitly disclaims legal, tax, immigration, medical and financial advice.

Protocol reference: [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs).

No extracted-text copy is added to the database; existing in-memory PDF extraction behavior is retained. Safe `document_analysis_failure` warnings include timestamp, stage, HTTP status, allowlisted error code/type, constrained request ID and model, and sanitized network exception classes/category. They never include raw bodies, document text, credentials, headers or exception messages. Spring web/HTTP logging is explicitly INFO to prevent Boot debug mode from logging body representations. Receipt diagnostics are unchanged.

## Review and task safety

Original suggestions remain unchanged. User confirmation stores a separate validated `confirmed_content` snapshot and timestamp. Users can edit type/title, summary, explanation, action requirement, deadline, requested information, next steps, warnings and important dates. They can also enter and confirm manual details without AI. Confirmation alone creates no tasks or reminders. Optimistic version checks prevent stale tabs overwriting newer confirmation.

Task generation rejects unconfirmed analysis, including legacy suggestions that have never been reviewed. Existing tasks remain untouched. The UI lets users select individual confirmed next steps, important dates or the primary deadline. Undated steps do not inherit a deadline automatically. Reminder creation continues through existing task controls.

A database lock and per-analysis action fingerprint prevent repeated selections from recreating the same title/date action, including after that generated task was edited or deleted. Changing the confirmed title or date constitutes a different action and may create a new task if explicitly selected. Legacy whole-plan generation history is preserved. The compatible no-selection generation endpoint still supports an explicitly requested whole confirmed plan.

## Retry and failure protection

A durable, account-scoped attempt claim commits before the external request. Concurrent application instances serialize claims using the owned document row lock. Later duplicate requests cannot spend again simply because an earlier attempt failed.

A failed attempt requires explicit retry confirmation with the current attempt ID and a 30-second cooldown. Stale retry submissions are rejected. No automatic retries or repair calls are implemented. Refresh only reads the saved state. Success is reused; confirmed manual details saved during an in-flight attempt are never overwritten by its response.

A process interruption can leave an attempt marked PROCESSING. It is deliberately not automatically reclaimed because delivery to the provider may be uncertain. The user can confirm manual details; operational investigation is needed before enabling another paid attempt in that exceptional case.

Provider/validation failures keep the original document and show a generic manual-review fallback. PDF extraction failures provide a safe, specific explanation. Failure records contain only a safe user-facing message, timestamps, status and attempt identifier.

## Migration

`V14__document_review_and_attempts.sql` adds:

- nullable `meaning`, `confirmed_content`, `confirmed_at`, and empty-default `evidence` / `important_dates` columns to document analyses;
- `MANUAL` as an allowed analysis source;
- `document_analysis_attempts`, linked to documents with cascading deletion;
- `document_action_generations`, linked to analyses with cascading deletion.

Historical suggestions are not marked confirmed automatically. Historical receipt/document/task values are not rewritten. Tests migrate an isolated V13 database containing existing suggestions, tasks and a reviewed receipt and verify their prior values remain identical. Existing receipt migrations and application functionality remain intact.

## Files changed

Backend production:

- `life-admin-api/src/main/java/com/lifeadmin/ai/OpenAiDocumentAnalysisProvider.java`
- `life-admin-api/src/main/java/com/lifeadmin/controller/DocumentAnalysisController.java`
- `life-admin-api/src/main/java/com/lifeadmin/controller/TaskController.java`
- `life-admin-api/src/main/java/com/lifeadmin/dto/AnalysisContent.java`
- `life-admin-api/src/main/java/com/lifeadmin/dto/DocumentAnalysisResponse.java`
- `life-admin-api/src/main/java/com/lifeadmin/entity/AnalysisSource.java`
- `life-admin-api/src/main/java/com/lifeadmin/entity/DocumentAnalysis.java`
- `life-admin-api/src/main/java/com/lifeadmin/service/DocumentAnalysisService.java`
- `life-admin-api/src/main/java/com/lifeadmin/service/TaskService.java`
- `life-admin-api/src/main/resources/ai/document-analysis.schema.json`
- `life-admin-api/src/main/resources/application.yml`
- `life-admin-api/src/main/resources/db/migration/V14__document_review_and_attempts.sql`

Frontend:

- `life-admin-web/src/app/documents/[id]/analysis-review.tsx`
- `life-admin-web/src/app/documents/[id]/document-detail.tsx`
- `life-admin-web/src/app/documents/[id]/action-plan.tsx`
- `life-admin-web/src/app/globals.css`
- `life-admin-web/src/lib/documents.ts`
- `life-admin-web/src/lib/tasks.ts`
- `life-admin-web/package.json`

Tests/documentation:

- `life-admin-api/src/test/java/com/lifeadmin/ai/OpenAiDocumentAnalysisProviderTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/AnalysisIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/AccountTestSupport.java`
- `life-admin-api/src/test/java/com/lifeadmin/AccountManagementIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/DocumentManagementIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/TaskIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/ProductionConfigurationIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/browser/ReceiptAiBrowserApplication.java`
- `life-admin-web/tests/document-ai.browser.mjs`
- `docs/MILESTONE_20.md`

Legacy generation fixtures now explicitly confirm their analyses. The test-only browser harness adds a `--document-analysis` mode using MockRestServiceServer and disposable PostgreSQL/storage. It is not packaged in the production application.

## Validation and controlled test boundary

Automated tests use mocked providers/HTTP responses and isolated databases/storage. The new document browser test covers successful analysis, repeated clicks, refresh, edited confirmation, selected actions, duplicate prevention, user isolation, mobile-width layout, confirmed retry, preserved failed documents, and manual fallback. The backend tests cover HTTP 400/401/403/404/429/500, missing key, timeout, malformed/refused output, source-excerpt checks, insufficient text, concurrent claims, confirmation version checks, invalid input, selected task generation, migration preservation and existing workflows.

The existing local database and uploaded files were not used or changed. No live application backend was restarted. Restart the backend normally to apply V14 and load the new endpoint behavior; restart a production Next.js server after rebuilding it. Startup does not initiate analysis.

A separately authorized single controlled real document test is still needed to verify actual model interpretation and account/model availability. Automated mocks verify the contract and application behavior, not live model accuracy. No such real test was performed during implementation. No scanned-PDF OCR, new notification delivery, deployment, or Milestone 21 work was added.

Final automated results: **272 backend tests passed**, **13 existing browser regression tests passed**, **1 new document-understanding browser test passed**, and **1 existing mocked receipt-AI browser test passed**. Frontend lint and TypeScript checks passed. Both browser-test and final production builds passed. The final build targets the normal local backend at 8084; the temporary test servers were stopped.

**Real OpenAI API requests made during implementation/testing: ZERO.** All provider responses in tests were mocked. The existing local database and existing receipt/document files were untouched; V14 was applied only to isolated test databases during this work.
