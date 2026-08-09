---
name: LifeOS
status: draft
updated: 2026-08-08
sources:
  - ../../REQUIREMENTS.md
  - ../epics.md
  - ./DESIGN.md
---

# LifeOS — Experience Spine

> `DESIGN.md` owns visual identity. This spine owns information architecture,
> behavior, states, interactions, accessibility, and journeys. **[ASSUMPTION]**
items require product-owner confirmation before client implementation unblocks.

## Foundation

LifeOS is a multi-surface product: Angular web, JavaFX desktop, and Flutter
iOS/Android. All surfaces share terminology, API contracts, privacy behavior,
and `{path.to.token}` references from `DESIGN.md`, while honoring native input,
navigation, secure storage, and accessibility conventions.

**[ASSUMPTION]** The first milestone is private and single-user-first; household
sharing is explicit and permissioned. Initial client scope prioritizes
authentication, Home, Plan, Calendar, Money, and Settings. Vault, Assistant,
Sessions, and labs become full destinations as their epics land.

## Information Architecture

| Surface | Reached from | Purpose |
|---|---|---|
| Sign in / Create account | App open or signed-out route | Registration, login, OIDC, passkey, and recovery |
| Home | Primary navigation | Priorities, reminders, freshness, and cross-domain signals |
| Plan | Primary navigation | Tasks, goals, habits, routines, milestones, dependencies |
| Calendar | Primary navigation | Events, time blocks, conflicts, reminders, optimization |
| Money | Primary navigation | Budgets, transactions, categories, insights, forecasts, goals |
| Vault | Primary navigation | Upload, metadata, search, summaries, proof status |
| Assistant | Primary navigation/contextual action | Grounded questions, recommendations, confirmed actions |
| Sessions | Primary navigation | Schedule, join, timer, recordings, transcripts, summaries |
| Settings | Account menu | Profile, preferences, privacy, AI settings, devices, sessions, sign out |

Desktop uses an application rail; web/tablet collapses it; mobile uses bottom
navigation or a platform sheet. Modal stacks are one level deep. Detail pages
retain parent context and a predictable back path.

## Voice and Tone

Microcopy is calm, direct, and respectful of sensitive data. It names what
happened, what is safe, and what the user can do next.

| Do | Don't |
|---|---|
| “We couldn’t save that. Your changes are still here. Try again.” | “Oops! Something went wrong!” |
| “Some finance data is still loading.” | “Your finances are inaccurate.” |
| “Review before sending.” | “Let the AI handle it.” |
| “No documents yet. Upload one to search it later.” | “Your vault is empty.” |

Never expose whether an email exists during authentication failures. Never put
private document, financial, or health content in push previews by default.

## Component Patterns

| Component | Use | Behavioral rules |
|---|---|---|
| Auth form | Sign in/create account | Keyboard and screen-reader accessible; generic errors; explicit loading/recovery. |
| Application shell | Authenticated surfaces | Announces destination and exposes account/session control. |
| Metric card | Home/analytics | Shows value, period, freshness, source, and partial/stale state. |
| Record list/table | Plan, Calendar, Money, Vault | Bounded pagination/loading, deterministic sort, empty state, accessible actions. |
| Form | Create/edit records | Inline validation, server-error mapping, unsaved-change handling, no false success. |
| Confirmation dialog | Destructive/side-effecting action | States consequence; explicit action; never nested. |
| Assistant response | Assistant/recommendations | Separates answer, evidence, limitation, proposed action, confirmation. |
| Status message | Loading/offline/error/stale/partial | Explains impact and recovery without obscuring the primary task. |

## State Patterns

| State | Treatment |
|---|---|
| Cold load | Layout-matched skeleton; announce destination and loading status. |
| Empty | Explain why it is empty and offer one relevant next action. |
| Offline | Preserve safe input, label cached data, and show pending writes. |
| Partial dependency failure | Keep healthy sections usable; identify missing source and retry. |
| Stale data | Show last-updated time and refresh; never silently overwrite newer edits. |
| Unauthorized | Do not render protected content; route to sign-in or neutral unavailable state. |
| Validation error | Keep input, focus first actionable field, explain the fix. |
| AI uncertainty | Show evidence/limitation; confirm consequential actions. |

## Interaction Primitives

- Web/desktop: reading-order tab flow, visible focus, `Escape` closes the
  topmost overlay, and every pointer action has a keyboard alternative.
- Mobile: platform back gestures, secure storage, permission prompts, and at
  least 48dp touch targets.
- Long-running work shows progress, supports safe cancellation, and exposes a
  resumable status instead of blocking the application.
- Optimistic updates are limited to reversible low-risk changes; server truth
  replaces optimistic state after every mutation.
- Reduced-motion settings disable nonessential animation; no auto-advancing
  carousels or attention loops.

## Accessibility Floor

- Meet WCAG 2.2 AA on web and equivalent platform guidance elsewhere.
- Every interactive element has an accessible name, role, state, and result;
  live regions announce navigation, errors, saves, and async status.
- Focus uses `{colors.focus}` / `{colors.focus-dark}`, remains visible, and
  moves to the first useful heading or invalid field.
- Largest supported text settings must not clip or hide essential actions.
- Charts/trends have a text or table equivalent; color is never the only signal.
- Keyboard, switch access, touch targets, and captions/transcripts remain usable.

## Responsive & Platform

| Surface | Layout | Adaptation |
|---|---|---|
| Web ≥1024px | Rail + 1–2 content columns | Related metrics may sit side by side; tables stay bounded. |
| Web 768–1023px | Collapsed rail + single flow | Secondary panels become drawers/sheets. |
| Web <768px | Top bar + stacked content | One primary decision per screen; complex tables become cards or labeled scroll. |
| JavaFX desktop | Native menu/rail + resizable panes | Keyboard-first workflows and explicit degraded state. |
| Flutter mobile | Bottom navigation + stacked details | Native back, secure storage, dynamic type, permissions, notifications. |

## Key Flows

### Secure entry — Amina, first morning

1. Amina opens LifeOS and chooses sign in/create account.
2. She uses password, OIDC, or passkey; errors remain generic and actionable.
3. Success announces Home and exposes session/device controls in Settings.
4. **Climax:** she sees today’s plan and freshness timestamps without needing to
   understand the service architecture.

Failure: provider/network/credential failure preserves safe form state, offers
approved recovery, and never stores secrets in client logs.

### Plan the day — Jordan, Tuesday morning

1. Jordan sees priorities, a calendar conflict, and metric freshness on Home.
2. He completes a task and creates a time block for the next goal.
3. Server-confirmed state refreshes Home; unavailable Calendar leaves Plan usable.
4. **Climax:** he can act on a clear next step while distinguishing measured
   signals from recommendations.

### Grounded knowledge — Priya, preparing a meeting

1. Priya uploads a document after seeing validation and private-storage behavior.
2. She adds metadata, searches, and asks Assistant for a grounded summary.
3. Evidence, uncertainty, and proposed actions are separated; side effects require confirmation.
4. **Climax:** she can verify the source and decide whether to act.

### Recover a lost device — Marisol, evening

1. Marisol opens Settings → Devices and sees safe metadata for active sessions.
2. She revokes an unfamiliar device; the revocation is confirmed visibly.
3. The device can no longer refresh or access protected data; unrelated sessions remain usable.

## Open Items

- **[ASSUMPTION]** Confirm brand voice, color direction, and dark-mode posture.
- **[ASSUMPTION]** Confirm whether Home prioritizes a daily plan, dashboard metrics, or a configurable combination.
- Confirm supported OIDC providers and passkey account-recovery policy before client auth stories leave UX-blocked status.
