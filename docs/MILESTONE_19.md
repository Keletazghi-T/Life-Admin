# Milestone 19 — Receipt Spending Insights

The Receipts page now links to `/receipts/insights`. The year selector defaults to the current UTC year and offers previous purchase years present in the account's saved receipts. A year containing only unreviewed dated receipts can be selected and will correctly show zero confirmed spending.

`GET /api/receipts/insights?year=2025` is authenticated and derives the owner exclusively from the session. It returns USD totals, included reviewed receipt count, average rounded to cents (half up), category totals, merchant totals/counts sorted by spending descending, and twelve monthly totals. Omitted year defaults to the current UTC year. Invalid years are rejected.

Every financial aggregate requires `SAVED`, `REVIEWED`, confirmed USD currency, a non-null confirmed amount, and a confirmed purchase date in the selected year. No suggested fields, upload dates, pending-deletion receipts, or tax-relevance flags contribute to totals. Missing categories display as Uncategorized; existing category values and merchant names are not rewritten or merged. Counts and average use the same eligible receipts as the spending total.

Queries aggregate in PostgreSQL across all matching receipts, independently of receipt-list pagination, within one read-only repeatable-read transaction. Existing indexes support owner/purchase-date filtering. All merchant groups are returned (no merchant pagination in this milestone).

No database migration is required. No existing records or uploaded files are modified, and no extraction calls are introduced. The existing general Receipts All years default and Tax Organization behavior remain unchanged.

## Files changed

- `life-admin-api/src/main/java/com/lifeadmin/controller/ReceiptInsightsController.java` — authenticated read-only endpoint.
- `life-admin-api/src/main/java/com/lifeadmin/service/ReceiptInsightsService.java` — account/year-scoped SQL aggregates and response records.
- `life-admin-api/src/test/java/com/lifeadmin/ReceiptInsightsIntegrationTest.java` — isolated PostgreSQL/API tests.
- `life-admin-api/src/test/java/com/lifeadmin/browser/ReceiptAiBrowserApplication.java` — optional `--manual-receipts` mode for existing browser regressions, using a disposable database/storage and no credential; existing mocked-provider mode retained.
- `life-admin-web/src/lib/receipt-insights.ts` — typed API response/client.
- `life-admin-web/src/app/receipts/insights/page.tsx` — responsive insights page, year selection, loading/error/empty states.
- `life-admin-web/src/app/receipts/page.tsx` — Spending Insights link.
- `life-admin-web/src/app/globals.css` — scoped table width and long-name wrapping.
- `life-admin-web/tests/receipt-insights.test.mjs` — intercepted desktop/mobile browser tests; all API traffic mocked.
- `life-admin-web/tests/receipt-review.test.mjs` — real isolated API-backed review-to-insights checks; corrected the pre-existing five-hint assertion to include Milestone 18's sixth Description field.
- `docs/MILESTONE_19.md` — this implementation/validation record.

## Validation

All automated backend tests use isolated databases/storage and cleared OpenAI credentials. Browser regression tests use the disposable test backend on 8085 and test frontend on 3002, never the existing preview database or files. The photo integration test uses MockRestServiceServer, never a real provider request.

Backend coverage includes authentication/account isolation, year boundaries/defaults, reviewed versus unreviewed receipts, missing amounts, suggestion exclusion, deletion-state exclusion, precise category/merchant/month totals, empty years, confirmed edits, and aggregation beyond list page size. The database also prevents REVIEWED receipts from having missing confirmed amounts. Tests verify the query leaves fixture rows unchanged.

Browser coverage includes navigation from Receipts, current/prior year selection, totals and breakdowns, all twelve months, empty results, error recovery, refresh, desktop/mobile widths, and saved receipt edits flowing into insights through the actual isolated backend. Existing upload/review, tax/CSV, dashboard/Inbox and mocked photo extraction regressions are also run.

No physical mobile device or real account data is needed for automated validation. A physical-device visual check remains optional. No real OpenAI test is necessary for this database-only feature.

Final results: **257 backend tests passed; 13 browser regression tests passed; 1 separate mocked photo-extraction browser test passed; frontend lint, TypeScript checks, and production build passed.** Real OpenAI API requests made: **ZERO**. The initial full browser run exposed a stale five-field hint expectation; it was corrected to six and the entire browser suite passed on rerun.

The test-only servers were stopped after validation. The final production frontend build targets the normal local backend at 8084. The existing live backend was not restarted; restart it through the usual development procedure to load the new endpoint. No paid/live extraction test is needed.
