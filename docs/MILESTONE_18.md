# Milestone #18 — Smarter Receipt Understanding & Review

## Changes

The existing backend-only Responses API request now asks for six nullable structured fields: merchant, purchase date, total amount, currency, category and description. The same request supplies all six suggestions; no additional provider call was introduced.

Category instructions now prioritize visible purchased line items or an explicitly stated service, rather than merchant names, logos or advertising. They use only the existing category enum. Food/drinks, including groceries, use MEALS; household supplies use HOME; stationery uses OFFICE. Mixed purchases without a predominant category and unreadable/insufficient item evidence must return null. BUSINESS/CHARITY need explicit evidence of the purpose. No category was added or renamed.

Descriptions are short purchase summaries grounded in visible items/services. The prompt forbids invented items, personal circumstances, tax conclusions, personal/payment identifiers and model reasoning. Blank, oversized (over 240 characters) or control-containing descriptions are discarded to null. All fields remain independently nullable: a known merchant/total does not require a guessed description/category. No fake confidence scores were added. Invalid structured output continues using the existing safe diagnostic and manual-review path.

The existing `ReceiptExtractionDetails.description` field is reused. A separate `suggestedDescription` now persists alongside the other suggestions and is returned in receipt/detail APIs. An unreviewed form pre-fills this suggestion; reviewed forms display the user's confirmed description. The existing label helper shows “Suggested by Life Admin,” “Your entry — save to confirm,” “Confirmed by you,” or “Could not determine automatically.” Saving remains the only operation that confirms values.

Tax relevance stays outside the model's response schema and remains the user's choice. The existing deductibility disclaimer remains visible. The provider does not decide tax treatment. Reviewed receipts remain protected against extraction, including their descriptions. No raw response, image, credential or reasoning was added to logs or persistence.

## Database migration

`V13__receipt_suggested_description.sql` adds only nullable `receipts.suggested_description VARCHAR(240)`. It does not backfill suggestions, modify historical confirmed descriptions, or change files. Existing receipts show an unknown description suggestion until a separately authorized extraction supplies one; no historical extraction is automatically rerun.

## Preserved behavior

The Milestone #17 upload request IDs, unique account/request index, automatic-attempt guard, frontend click guards, manual retry confirmation/version checks/cooldown and read-only refresh behavior are unchanged. PDFBox/local PDF extraction remains unchanged; it currently leaves the description suggestion null. JPEG/PNG, `accept="image/*"`/rear-camera hint, manual review, all-years receipt lists, summaries, Tax Organization, private file access, authentication, CSRF, ownership, deletion cleanup, Dashboard/Inbox, documents, tasks and reminders remain supported.

## Verification

- **240 backend tests passed**, including six-field structured output, nullable/invalid descriptions, independent uncertainty, rejection of extra reasoning/tax fields, no sensitive logging, suggestion persistence, correction/login persistence, confirmed-value protection and V12→V13 preservation.
- Existing PDF, ownership, failure/manual-review, duplicate/concurrent upload, retry, Dashboard/Inbox and receipt tests passed in that full suite.
- **Six relevant mocked Chrome tests passed**: two capture/review tests, three year-filter tests and an isolated backend HTTP-mocked end-to-end flow. They verify description hints, blank unknowns, user correction, refresh/login persistence, tax choice, camera attributes, mobile layout and unchanged duplicate guards.
- Frontend lint, TypeScript checks and production build passed.
- Tests used intercepted HTTP or the test-only mocked provider backend with disposable PostgreSQL/files. No existing live receipts/files were edited, deleted or retried. The live backend was not restarted or migrated during implementation.
- **Real OpenAI API requests made: ZERO.** Mocked tests establish contract and workflow behavior, not live model accuracy.

## Files changed

Backend:
- `life-admin-api/src/main/java/com/lifeadmin/ai/OpenAiReceiptExtractionProvider.java`
- `life-admin-api/src/main/resources/ai/receipt-extraction.schema.json`
- `life-admin-api/src/main/java/com/lifeadmin/entity/Receipt.java`
- `life-admin-api/src/main/java/com/lifeadmin/dto/ReceiptResponse.java`
- `life-admin-api/src/main/java/com/lifeadmin/service/ReceiptExtractionWorkflow.java`
- `life-admin-api/src/main/resources/db/migration/V13__receipt_suggested_description.sql`

Frontend:
- `life-admin-web/src/lib/receipts.ts`
- `life-admin-web/src/app/receipts/receipt-form.tsx`

Tests/harness:
- `life-admin-api/src/test/java/com/lifeadmin/ai/OpenAiReceiptExtractionProviderTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/ReceiptAiIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/ReceiptReviewIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/ProductionConfigurationIntegrationTest.java`
- `life-admin-api/src/test/java/com/lifeadmin/browser/ReceiptAiBrowserApplication.java`
- `life-admin-web/tests/receipt-capture.test.mjs`
- `life-admin-web/tests/receipt-ai.browser.mjs`

Documentation:
- `README.md`
- `docs/MILESTONE_18.md`

## Controlled manual verification still needed

Restart Spring Boot from the configured terminal to apply additive V13 and load the new prompt/schema. Restart/redeploy a production frontend if you use one; the running Next development server loads frontend edits itself. Restarting does not retry receipts.

A separately authorized controlled image test is still needed to assess live category/description quality on real visible line items, mixed purchases and unreadable items. This was deliberately not performed. Suggestions can still be wrong and require human review. Physical-device camera behavior also remains a manual check. PDF description extraction and HEIC conversion were not added. User-entered descriptions retain the existing 2,000-character limit; only AI suggestions use the new 240-character bound.
