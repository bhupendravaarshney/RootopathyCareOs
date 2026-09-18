# CareOS Design System 1.0 review brief

**Artifact kind:** `design-system`  
**Status:** `DRAFT_NOT_APPROVED`  
**Draft version:** `review-draft-1`  
**Approval authority:** Owner decision required

## Proposed review baseline

The existing React CSS is a reference visual language, not CareOS Design System 1.0. Its dark palette, font stack, radii, and 760-pixel drawer rule may be retained, replaced, or split into themes only through an owner-reviewed token and component package. Final tokens must be semantic rather than tied to a single screen.

### Required token decisions

| Token family        | Required definition                                                                                                                                                                                              |
| ------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Color               | Canvas, navigation, surfaces, borders, text hierarchy, focus, link, selected, informational, success, warning, danger, disabled, overlay, chart/data, and high-contrast behavior with light/dark theme decision. |
| Typography          | Licensed font assets or system fallback, display/heading/body/label/code scales, weights, line heights, letter spacing, truncation, wrapping, and localization expansion.                                        |
| Spacing and sizing  | Base grid, control heights, touch targets, content widths, density modes, icon sizes, table rows, drawer/sidebar widths, and safe-area handling.                                                                 |
| Shape and elevation | Radius scale, borders, shadows/elevation, separators, focus-ring geometry, and high-contrast fallback.                                                                                                           |
| Motion              | Duration/easing, progress/loading, drawer/dialog transition, reduced-motion alternative, and prohibition on motion-only meaning.                                                                                 |
| Responsive          | Exact behavior at 1440, 1024, 768, 390, and 320 pixels; container rules; persistent versus drawer navigation; table/card transformations; dialog/full-screen behavior; and zoom/reflow.                          |
| Content             | Voice, capitalization, dates/times/numbers, validation, generic security errors, destructive language, reason prompts, empty states, and support correlation references.                                         |
| Icons/assets        | Approved library/version, custom CareOS assets, accessible-name rules, decorative treatment, SVG sanitization, licensing, and asset integrity/distribution.                                                      |

The current reference values (`#06130f`, `#10251d`, `#152e25`, mint accents, system/Manrope/Inter fallbacks, 10/16/20-pixel radii) are observations only. They must not be copied into an approved bundle without contrast, brand, licensing, theme, and accessibility review.

### Required component contracts

Each component must publish anatomy, properties, content rules, variants, states, keyboard behavior, focus behavior, screen-reader semantics, responsive behavior, and test identifiers:

- application shell, top bar, sidebar/drawer, workspace tabs, breadcrumbs, page header, and skip link;
- buttons, links, icon buttons, menus, disclosure, tabs, badges, status indicators, and tooltips;
- text/date/number inputs, textarea, select/combobox, checkbox, radio, switch, file/evidence picker, field help, validation summary, and read-only display;
- data table, responsive record card, server filter bar, sort, opaque cursor pager, result count, bulk-selection decision, and no-result/empty states;
- banner/notice, inline error, RFC 9457 problem summary, loading/skeleton, retry state, dependency outage, toast, and support-reference treatment;
- modal and non-modal dialog, confirmation/reason dialog, recent-authentication prompt, maker-checker decision panel, and focus restoration;
- readiness checklist, metric/stat card, timeline, configuration diff, audit detail, evidence viewer, export status, and approval history;
- date/timezone/effective-range editor and weekly/overnight operating-hours editor.

### Accessibility baseline to approve

- WCAG 2.2 AA target, including 200% zoom and 320 CSS-pixel reflow expectations.
- Visible focus for every interactive or scrollable keyboard target and logical focus order.
- Minimum target size and spacing policy, with documented exceptions.
- Semantic HTML first; ARIA only where native semantics are insufficient.
- Error identification in text, error-summary focus, field association, live-region restraint, and no color-only status.
- Dialog focus containment/return, escape/cancel rules, background inertness, and destructive-action safeguards.
- Reduced motion, Windows high contrast/forced colors, screen magnification, and common screen-reader/browser test matrix.

## Owner decisions required

1. Name the design-system owner, package/versioning model, distribution mechanism, and supported browser/device matrix.
2. Approve themes, brand assets, fonts/licensing, icon source, semantic token names, contrast evidence, and content standards.
3. Approve exact breakpoints and component transformations at 1440/1024/768/390/320; decide whether additional fluid thresholds are permitted.
4. Decide density, touch-target, localization, right-to-left, high-contrast, reduced-motion, and print/export presentation requirements.
5. Approve component API ownership and how design tokens map to checked React code without screen-specific rules entering shared components.

## Acceptance checklist

- [ ] Versioned native design library, token source, generated artifacts, licenses, and integrity metadata are supplied.
- [ ] All required components include default, hover, focus, active, disabled, read-only, loading, error, success, and permission states where applicable.
- [ ] Responsive and accessibility annotations cover the complete M1 screen/state set.
- [ ] Automated contrast/token/component checks and manual assistive-technology acceptance criteria are defined.
- [ ] Engineering can consume the package reproducibly without copying values from screenshots.
- [ ] A named authority records approval evidence for the exact checksum-bound bundle.
