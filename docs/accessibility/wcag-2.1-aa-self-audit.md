# CertAlert: WCAG 2.1 AA self-audit

**Status:** self-assessment by the development team, October 2026, against the CertAlert web
interface as of this commit. It is not a third-party audit. It covers what an automated scan
can establish, a review of the templates and scripts, and keyboard checks; it does **not**
yet include testing with screen readers (JAWS, NVDA, VoiceOver) or with users with
disabilities. The draft Accessibility Conformance Report built from it is
[vpat-2.5-rev508-draft.md](vpat-2.5-rev508-draft.md).

## Scope

The CertAlert web application: every page an administrator, auditor or reader reaches -
sign-in and the notice-and-consent banner; People, Servers, Certificates, Projects, Metrics,
Notifications; Administration, Audit log, Integrations, License; the person, server and project
pages; error pages - and the HTML email it sends (the expiry round-up and alerts). Not in
scope: the REST API and OpenAPI document (no user interface), the Helm chart and installer
scripts, and third-party identity providers' sign-in pages reached through SSO.

## Method

1. **Automated, in CI.** `./gradlew a11yTest` (the `a11y` job in `.github/workflows/ci.yml`)
   starts the application in its demo profile - a generated directory of people and servers,
   swept and indexed as a real one would be - with the classification and consent banners on,
   signs in as an administrator, and scans every page above with axe-core 4.14 in Chromium for
   the WCAG 2.0 and 2.1 A and AA rules (`wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`). The build
   fails on any serious or critical violation. It also scans with a search-table row opened
   from the keyboard, and checks that the skip link is the first thing Tab reaches and moves
   focus to the main region. axe's best-practice rules are reported alongside, without failing
   the build. Reports: `build/reports/a11y/axe-summary.md` and `axe-results.json`.
2. **Review** of the Thymeleaf templates, the page scripts and the stylesheet against each
   success criterion below.
3. **Keyboard** walk-through of the main tasks in Chromium: sign in, filter and page a table,
   open a row, export, save a view, create and revoke an API token.

Automated tools find roughly a third of WCAG issues; items marked *manual check pending* below
are where the remaining risk is.

## What was found, and fixed

The first scan - run once the landmarks and the skip link were in - failed on 16 of the 17 pages it
scanned (the consent page passed). Fixed in this change:

| Issue | WCAG | Where | Fix |
| --- | --- | --- | --- |
| Light badges (Tabler `*-lt`), coloured text and link-coloured buttons at 2.1-4.4:1 | 1.4.3 | Every page | Darker text for each hue, 5:1 or more (`app.css`) |
| Notification bell and account menu named by `aria-label` that left out their visible text | 2.5.3 | Every page | Named by visually hidden text that includes what is shown |
| Links told apart by colour alone in running text | 1.4.1 | Admin, Integrations | Underlined |
| Port field of the endpoint probe without a label | 1.3.1, 4.1.2 | Server page | `<label for>` |
| Search-table rows opened by clicking a cell, not reachable by keyboard | 2.1.1, 4.1.2 | People, Servers | A real button with `aria-expanded` and a name; focus kept on it |
| No skip link; no `main` landmark | 2.4.1, 1.3.1 | Every page | "Skip to main content" first in tab order; `<main id="main-content">` |
| No level-one heading; card titles skipped a level | 1.3.1, 2.4.6 | Every page | Page title is the `h1`; card titles `h2` |
| Classification banners drawn with CSS `content:`, which screen readers skip or read inconsistently | 1.3.1 | Every page | Real text in a labelled region, top and bottom (CA-16) |
| Pagination "Newer/Older" looked disabled but was not said to be | 4.1.2 | Audit log | `aria-disabled` |
| Header cells with no text; column filters named only by a placeholder | 1.3.1, 3.3.2 | Tables | Visually hidden header text; `aria-label` on each filter |
| Field and check-box edges at about 1.3:1 | 1.4.11 | Forms | Borders at 3.5:1 |
| Idle timeout with no warning | 2.2.1 | Every page | Alert two minutes before, with "Stay signed in" (`session-timeout.js`) |
| Sync status updated silently | 4.1.3 | Page header | `role="status"` |

After these, the scan reports **no WCAG violations of any impact** on any page, and no
best-practice notes.

## Success criteria

Level: **S** supports, **P** partially supports, **D** does not support, **N/A** not
applicable.

### Perceivable

| Criterion | Level | Result | Notes |
| --- | --- | --- | --- |
| 1.1.1 Non-text content | A | S | Logos have alt text; icons are decorative beside text, or the control carries a text name. Metrics are tables with numbers, the bars beside them decoration. |
| 1.2.1-1.2.5 Time-based media | A/AA | N/A | No audio or video. |
| 1.3.1 Info and relationships | A | S | Landmarks (banner, navigation, main, classification regions), one `h1` per page, `th` with `scope`, labelled fields and fieldsets for check-box groups. *Manual check pending:* screen-reader reading of the expanded certificate detail. |
| 1.3.2 Meaningful sequence | A | S | DOM order is reading order; the fixed banners come first and last. |
| 1.3.3 Sensory characteristics | A | S | Instructions do not rely on shape or position. |
| 1.3.4 Orientation | AA | S | Responsive (Tabler/Bootstrap); no orientation lock. |
| 1.3.5 Identify input purpose | AA | S | Sign-in fields carry `username`/`password` purposes; no other personal-data fields. |
| 1.4.1 Use of colour | A | S | Status is a word in a badge, not a colour; links underlined in text. |
| 1.4.2 Audio control | A | N/A | No audio. |
| 1.4.3 Contrast (minimum) | AA | S | Scanned on every page; text at 4.5:1 or more. Operator-chosen banner colours are the operator's: the README says to pick a pair with 4.5:1. |
| 1.4.4 Resize text | AA | S | Relative units; usable at 200% zoom. |
| 1.4.5 Images of text | AA | S | None, the logo aside. |
| 1.4.10 Reflow | AA | P | Pages reflow at 320 CSS px; the wide search tables scroll horizontally, as data tables may. |
| 1.4.11 Non-text contrast | AA | S | Field borders and focus rings at 3:1 or more. |
| 1.4.12 Text spacing | AA | P | Content adapts; the classification bars have a fixed height and may clip enlarged spacing (the marking is still in the text). |
| 1.4.13 Content on hover or focus | AA | S | Only native `title` tooltips and Bootstrap dropdowns, dismissible with Esc. |

### Operable

| Criterion | Level | Result | Notes |
| --- | --- | --- | --- |
| 2.1.1 Keyboard | A | P | Every page's controls, the search tables (sorting, paging, opening a row), dropdowns and dialogs work from the keyboard; checked by the scan for opening a row. *Manual check pending:* the contact and project pickers' suggestion lists. |
| 2.1.2 No keyboard trap | A | S | Bootstrap modals release focus on close. |
| 2.1.4 Character key shortcuts | A | N/A | None. |
| 2.2.1 Timing adjustable | A | S | The idle timeout (ENS session policy, 15 minutes by default) is warned of two minutes ahead with a way to stay signed in; the absolute session limit is a security requirement (exception). |
| 2.2.2 Pause, stop, hide | A | S | Nothing moves or updates on its own beyond status text. |
| 2.3.1 Three flashes | A | S | Nothing flashes. |
| 2.4.1 Bypass blocks | A | S | Skip link, landmarks, headings. |
| 2.4.2 Page titled | A | S | "CertAlert · <page>". |
| 2.4.3 Focus order | A | S | DOM order; dialogs move focus in and back. |
| 2.4.4 Link purpose (in context) | A | S | Row actions carry the row's name ("Revoke token *name*"). |
| 2.4.5 Multiple ways | AA | S | Navigation bar, search tables, links between people, servers and projects. |
| 2.4.6 Headings and labels | AA | S | |
| 2.4.7 Focus visible | AA | S | Bootstrap focus rings kept; the skip link is visible on focus. |
| 2.5.1-2.5.4 Pointer gestures, cancellation, label in name, motion | A | S | No path or multipoint gestures; click on release; names include visible text; no motion input. |

### Understandable

| Criterion | Level | Result | Notes |
| --- | --- | --- | --- |
| 3.1.1 Language of page | A | S | `lang="en"`. |
| 3.1.2 Language of parts | AA | N/A | Directory data is shown as written. |
| 3.2.1 On focus / 3.2.2 On input | A | S | Filters update the table in place, without moving focus or the page. |
| 3.2.3 Consistent navigation / 3.2.4 Consistent identification | AA | S | One layout fragment for every page. |
| 3.3.1 Error identification | A | S | Errors are said in text next to the form, in a status region. |
| 3.3.2 Labels or instructions | A | S | |
| 3.3.3 Error suggestion | AA | S | Messages say what to change ("Give the token a name of up to 120 characters"). |
| 3.3.4 Error prevention (legal, financial, data) | AA | S | Deleting and revoking ask for confirmation; prune and cleanup have previews. |

### Robust

| Criterion | Level | Result | Notes |
| --- | --- | --- | --- |
| 4.1.1 Parsing | A | S | (Treated as always satisfied for HTML since the 2023 WCAG errata.) |
| 4.1.2 Name, role, value | A | S | Native controls; the row toggle and sort buttons expose `aria-expanded`/`aria-sort`. *Manual check pending* with a screen reader. |
| 4.1.3 Status messages | AA | S | Counts, saved-view and export results, job status and form results are `role="status"`/`aria-live`. |

## Email

The round-up and alert emails are HTML with a plain-text alternative; layout tables are
`role="presentation"`, data tables have header cells, and the classification marking is text
at the top and bottom of both parts and in the subject. *Manual check pending* in Outlook with a
screen reader.

## Known gaps and next steps

1. Screen-reader testing (JAWS and NVDA on Windows with Chrome/Edge, VoiceOver on macOS)
   of the search tables, the expanded certificate detail, the pickers and the Integrations
   page.
2. Windows High Contrast / `forced-colors` mode: badges lose their tint (their text remains);
   not yet reviewed.
3. Text-spacing override on the classification bars (1.4.12).
4. A user test with keyboard-only and screen-magnifier users before the ACR is published.
