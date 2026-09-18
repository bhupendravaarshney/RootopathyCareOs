# CareOS Design System 1.0 candidate

**Artifact kind:** `design-system`  
**Status:** `CANDIDATE_FOR_APPROVAL`  
**Candidate version:** `m1-candidate-1`  
**Approval:** Not granted

## Decision baseline

This candidate adopts the existing CareOS dark visual language as Design System 1.0, removes external font licensing as a dependency, and formalizes semantic tokens and component behavior. Product screens may compose these contracts but may not introduce private colors, spacing, focus behavior, or breakpoint rules.

### Tokens

| Family   | Approved candidate values and rules                                                                                                                                                                                                             |
| -------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Font     | `Inter`, `Segoe UI`, `Roboto`, system UI, sans-serif. No remote font request. Body 16/24; small 13/20; label 13/18 semibold; H1 40/48 at wide and 28/36 at narrow; H2 24/32; H3 18/26; code uses system monospace. Text must wrap at 200% zoom. |
| Canvas   | `canvas=#06130f`, `navigation=#10251d`, `surface=#152e25`, `surface-raised=#19382d`, `surface-input=#071712`, `overlay=rgba(0,0,0,.70)`.                                                                                                        |
| Text     | `text=#edf7f2`, `text-muted=#a7bbb2`, `text-subtle=#82a696`, `link=#b6f4d7`, `disabled=#71847b`. Text contrast must meet WCAG 2.2 AA against every permitted surface.                                                                           |
| Semantic | `focus=#9bd7f5`, `success=#75dab4`, `success-surface=#0b3b2d`, `info=#9bd7f5`, `info-surface=#102d3b`, `warning=#f4c96b`, `warning-surface=#392e12`, `danger=#ff9f9f`, `danger-surface=#3a1818`. Status never relies on color alone.            |
| Border   | `border=#2d4c40`, `border-strong=#4f7063`, semantic borders use the corresponding foreground at 55% minimum contrast. One-pixel default, two-pixel selected/error.                                                                              |
| Spacing  | Four-pixel base grid: `4, 8, 12, 16, 20, 24, 32, 40, 48, 64`. Screen gutters: 64 wide, 32 compact, 16 narrow, 12 at 320.                                                                                                                        |
| Shape    | Radius `8, 12, 16, 20`; controls 12; cards 16 or 20; pills 999. Shadow is reserved for modal/drawer elevation and cannot communicate status.                                                                                                    |
| Sizing   | Controls at least 44 CSS pixels high and touch targets at least 44 by 44. Content maximum 1440 pixels; readable text measure 75 characters. Sidebar 310 wide, compact sidebar 260, drawer maximum 330.                                          |
| Focus    | Three-pixel `focus` outline, two-pixel offset. Forced-colors uses system `Highlight`. Focus is never removed and every scroll region is focusable and named.                                                                                    |
| Motion   | 160ms standard, 220ms drawer/dialog, ease-out entry and ease-in exit. `prefers-reduced-motion` reduces transitions and animations to effectively zero. No motion-only meaning.                                                                  |

### Responsive contract

| Review width | Candidate behavior                                                                                                         |
| ------------ | -------------------------------------------------------------------------------------------------------------------------- |
| 1440         | 310px persistent navigation, four-column metrics, full table, dialog maximum 720px.                                        |
| 1024         | 260px persistent navigation, two-column metrics/forms, full table or reviewed horizontal table region.                     |
| 768          | Persistent 260px navigation remains; single-column forms where needed. Document/body horizontal overflow is forbidden.     |
| 390          | Drawer navigation, single-column content, record cards replace action-heavy tables, full-width dialog/sheet, 16px gutters. |
| 320          | Same drawer model, 12px gutters, stacked actions, no clipped label or horizontal document overflow.                        |

The navigation breakpoint is `760px`: 768 remains persistent and 390/320 use the drawer. Fluid layouts between reference widths are allowed only when they retain the same information, order, permissions, and keyboard behavior.

### Component contracts

- **Application shell:** skip link, sticky top bar, named organization switcher, persistent navigation or modal drawer, one `main`, visible route title, and focused page heading after navigation.
- **Page header:** breadcrumb when hierarchy depth exceeds one, eyebrow, H1, concise purpose, lifecycle badge, and permission-derived actions. One primary action maximum.
- **Inputs:** persistent label, optional/required text, help, units/format, server error association, read-only treatment, and 44px target. Placeholder is never the label.
- **Problem summary:** RFC 9457 title/detail, safe correlation reference, field links, focus on failed submit, and retry only when safe.
- **Data view:** server filter bar, result count, deterministic sort, table at persistent-navigation widths, equivalent record cards at drawer widths, opaque cursor controls, empty and no-result distinction.
- **Dialogs:** native dialog semantics, labelled title/description, focus containment, escape for non-destructive cancellation, focus restoration, reason field where policy requires, and destructive action last.
- **Readiness/approval:** typed gate outcome with icon and text, evidence/freshness, deep link, decision actor separation, and no client-authored completion.
- **Timeline/diff/evidence:** ordered server timestamps, stable labels by schema version, minimum-necessary values, redaction markers, and accessible added/removed/changed text.
- **Toast:** supplementary only; durable result remains in page content. Error and approval outcomes cannot exist only in a toast.
- **Skeleton/loading:** retains layout, has one polite status, stops on error, respects reduced motion, and never looks like actual data.

### Content and accessibility

- Sentence case, direct verbs, UTC stored time displayed in the selected IANA timezone, locale-aware dates/numbers, and timezone abbreviation plus offset where ambiguity matters.
- Generic authentication and hidden-resource responses do not disclose account, membership, tenant, or resource existence.
- WCAG 2.2 AA is the release target at 320 CSS pixels and 200% zoom. Required manual coverage: keyboard only, NVDA/Firefox, NVDA/Chrome, VoiceOver/Safari, forced colors, and reduced motion.
- Icon-only controls require accessible names; decorative icons are hidden. SVG sources must be repository-owned, sanitized, and bundled. The initial icon source is the pinned `lucide-react` package; custom brand marks require separate integrity/licensing evidence.
- English is the initial locale, but layouts must permit 30% text expansion. Bidirectional/RTL production support is deferred and must not be claimed.

## Verification and acceptance

- Token contrast, forbidden literal colors in shared components, focus visibility, target size, reduced motion, exact viewport behavior, Axe serious/critical findings, keyboard completion, and document overflow are automated where practical.
- Component acceptance includes default, hover, focus, active, disabled, read-only, loading, empty, error, denied, conflict, and success variants where applicable.
- M1-01 through M1-23 must use these tokens and contracts; any exception is documented beside the screen and approved with the same package.
- Supported browsers are the current and previous major releases of Chrome, Edge, Firefox, and Safari at approval time. Mobile review covers current iOS Safari and Android Chrome.

## Approval boundary

This document is a reproducible candidate, not an approved design library. Approval must identify the exact candidate digest, visual mockup artifact, component exceptions, accessibility review evidence, and accountable design/product/accessibility authorities. Until then, production styling remains blocked.
