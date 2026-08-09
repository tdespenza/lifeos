---
name: LifeOS
description: "Cross-platform design contract for deliberate planning, reflection, and action. Visual choices marked [ASSUMPTION] require product-owner confirmation."
status: draft
updated: 2026-08-08
sources:
  - ../../REQUIREMENTS.md
  - ../epics.md
colors:
  surface-base: '#F7F8FA'
  surface-raised: '#FFFFFF'
  surface-subtle: '#EEF2F5'
  ink-primary: '#13212B'
  ink-secondary: '#52616B'
  ink-muted: '#74838D'
  border: '#D5DEE4'
  primary: '#1F5C7A'
  primary-foreground: '#FFFFFF'
  accent: '#D99A2B'
  accent-foreground: '#1A1408'
  success: '#227A58'
  warning: '#946B00'
  danger: '#B42318'
  focus: '#1F5C7A'
  surface-base-dark: '#10191F'
  surface-raised-dark: '#18252D'
  ink-primary-dark: '#F2F6F8'
  ink-secondary-dark: '#B8C6CD'
  border-dark: '#38505D'
  primary-dark: '#7CC4E5'
  accent-dark: '#F5C86D'
  danger-dark: '#FF9B91'
  focus-dark: '#A5E2F7'
typography:
  display:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 36px
    fontWeight: '650'
    lineHeight: '1.15'
  headline:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 24px
    fontWeight: '650'
    lineHeight: '1.25'
  body:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 16px
    fontWeight: '400'
    lineHeight: '1.5'
  label:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 13px
    fontWeight: '600'
    lineHeight: '1.35'
  meta:
    fontFamily: 'system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif'
    fontSize: 12px
    fontWeight: '400'
    lineHeight: '1.4'
rounded:
  sm: 6px
  md: 10px
  lg: 16px
  full: 9999px
spacing:
  '1': 4px
  '2': 8px
  '3': 12px
  '4': 16px
  '5': 24px
  '6': 32px
  '7': 48px
components:
  primary-button:
    background: '{colors.primary}'
    foreground: '{colors.primary-foreground}'
    radius: '{rounded.md}'
    minHeight: 44px
  focus-ring:
    color: '{colors.focus}'
    width: 2px
    offset: 2px
  metric-card:
    background: '{colors.surface-raised}'
    border: '{colors.border}'
    radius: '{rounded.md}'
---

## Brand & Style

**[ASSUMPTION]** LifeOS should feel like a trustworthy control surface for a
private life: calm, precise, and capable without becoming clinical. Neutral
surfaces support reading and planning; deep blue-teal marks primary action and
navigation; amber marks attention or review, never decoration.

The visual language is **quiet operational clarity**. Web, JavaFX, and Flutter
share the semantic tokens and language while retaining native controls,
navigation, secure storage, and accessibility conventions.

## Colors

Use `{colors.primary}` for navigation, links, primary actions, and active focus.
Use `{colors.accent}` only for attention or selected planning emphasis. Success,
warning, and danger always pair color with text or an accessible icon. No
gradients, decorative neon, or color-only finance/privacy/security signals.
Verify WCAG 2.2 AA contrast after every token override.

## Typography

Use the system sans stack for speed, legibility, and platform parity. Use the
display role for page titles and empty-state headings, not body copy. Labels are
sentence case and remain readable at enlarged text settings.

## Layout & Spacing

Use the 4/8/12/16/24/32/48 scale. Desktop may use a rail and two-column work
areas; tablet collapses secondary panels; mobile stacks one primary decision per
screen. Dashboard content is capped around 1200px; focused forms and reading
surfaces around 760px.

## Elevation & Depth

Use tonal layering before shadows. A one-pixel border is preferred for data
surfaces. Shadows are subtle and reserved for popovers, dialogs, and drag
feedback; depth never implies a security boundary.

## Shapes

Inputs, buttons, cards, and dialogs use small/medium radii. Full pills are for
compact status tags only. Interactive targets are at least 44px on web/desktop
and 48dp on mobile.

## Components

- **Application rail:** persistent desktop navigation; collapses to a sheet or
  bottom navigation on smaller surfaces. Active state uses color plus text/shape.
- **Page header:** title, context, freshness timestamp, and one primary action.
- **Metric card:** value, label, period, source/freshness, and trend explanation.
- **Record list/table:** deterministic ordering, bounded loading, keyboard
  navigation, and explicit empty/partial/stale states.
- **Status message:** impact, recovery action, and optional correlation/support
  affordance.
- **Confirmation dialog:** names consequence; one primary and one cancel action;
  never nested.
- **Assistant response:** separates answer, evidence/source ids, limitations,
  and proposed actions. Side effects require confirmation.
- **Auth form:** password, OIDC, and passkey paths with generic errors and no
  client-side provider secrets.

## Do's and Don'ts

| Do | Don't |
|---|---|
| Show freshness, source, and partial state | Present stale/incomplete data as current |
| Pair semantic color with text, icon, or shape | Encode meaning through color alone |
| Keep sensitive actions explicit and reversible when possible | Hide destructive effects behind vague copy |
| Honor platform navigation, keyboard, dynamic type, and secure storage | Force one interaction model across all clients |
| Use calm, direct microcopy | Use gamification or artificial urgency |
