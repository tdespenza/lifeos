import { bootstrapApplication } from '@angular/platform-browser';
import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { FormsModule } from '@angular/forms';

type Destination = 'home' | 'plan' | 'calendar' | 'money' | 'vault' | 'assistant' | 'sessions' | 'settings';
type Metric = { key: string; value: number; periodDays: number; sourceVersion: string };
type Dashboard = { periodDays: number; metrics: Metric[]; sourceVersion: string };
type TrendPoint = { date: string; value: number; sourceVersion: string };
type Trend = { metricKey: string; points: TrendPoint[]; requestedDays: number };
type WorkspaceItem = { kind: string; id: string; title: string; detail: string; status?: string };
type WorkspaceData = { source: string; items: WorkspaceItem[]; emptyMessage: string };

const NAVIGATION: ReadonlyArray<{ id: Destination; label: string; description: string }> = [
  { id: 'home', label: 'Home', description: 'Priorities, reminders, and measured signals.' },
  { id: 'plan', label: 'Plan', description: 'Tasks, goals, habits, routines, and milestones.' },
  { id: 'calendar', label: 'Calendar', description: 'Events, focus blocks, conflicts, and reminders.' },
  { id: 'money', label: 'Money', description: 'Budgets, transactions, insights, and forecasts.' },
  { id: 'vault', label: 'Vault', description: 'Private documents, search, and proof status.' },
  { id: 'assistant', label: 'Assistant', description: 'Grounded answers and confirmed actions.' },
  { id: 'sessions', label: 'Sessions', description: 'Scheduled sessions, timers, and recordings.' },
  { id: 'settings', label: 'Settings', description: 'Profile, privacy, AI preferences, and devices.' },
];

@Component({
  selector: 'lifeos-dashboard',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="app-shell">
      <header class="topbar">
        <a class="brand" href="#main" (click)="focusMain($event)">LifeOS</a>
        <span class="environment">Private workspace</span>
        <button *ngIf="authenticated" type="button" class="secondary topbar-action" (click)="signOut()">Sign out</button>
      </header>

      <ng-container *ngIf="authenticated; else authForm">
        <div class="body-shell">
        <nav class="rail" aria-label="Primary navigation">
          <button
            *ngFor="let item of navigation"
            type="button"
            class="nav-item"
            [class.active]="destination === item.id"
            [attr.aria-current]="destination === item.id ? 'page' : null"
            (click)="select(item.id)">
            <span>{{ item.label }}</span>
          </button>
        </nav>

        <main id="main" class="content" tabindex="-1" aria-labelledby="page-title">
          <div class="page-heading">
            <div>
              <p class="eyebrow">{{ currentItem.label }}</p>
              <h1 id="page-title">{{ currentItem.label }}</h1>
              <p class="description">{{ currentItem.description }}</p>
            </div>
            <p class="freshness" aria-live="polite">{{ freshness }}</p>
          </div>

          <ng-container *ngIf="destination === 'home'; else otherDestination">
            <section *ngIf="error" class="status status-error" role="alert">
              <strong>Home is temporarily unavailable.</strong>
              <span>{{ error }}</span>
              <button type="button" class="secondary" (click)="loadDashboard()">Try again</button>
            </section>
            <p *ngIf="!dashboard && !error" class="status" role="status" aria-live="polite">
              Loading measured signals…
            </p>
            <section *ngIf="dashboard" class="metric-grid" aria-label="Dashboard metrics">
              <article *ngFor="let metric of dashboard.metrics" class="metric-card">
                <p class="metric-label">{{ metric.key }}</p>
                <p class="metric-value">{{ metric.value }}</p>
                <p class="metric-meta">{{ metric.periodDays }}-day window · {{ metric.sourceVersion }}</p>
                <p *ngIf="trends[metric.key]" class="trend" [attr.aria-label]="metric.key + ' daily trend'">
                  Daily: {{ trendValues(trends[metric.key]) }}
                </p>
              </article>
            </section>
            <p *ngIf="dashboard && !dashboard.metrics.length" class="status" role="status">
              No measured signals yet. Start with a task or calendar event.
            </p>
          </ng-container>

          <ng-template #otherDestination>
            <ng-container *ngIf="destination === 'settings'">
              <section class="settings-stack">
                <div class="empty-state">
                  <h2>Passkey recovery codes</h2>
                  <p>Generate a replacement set when you are signed in. Codes are shown once and remain only in this tab.</p>
                  <p *ngIf="recoveryError" class="status status-error" role="alert">{{ recoveryError }}</p>
                  <button type="button" class="primary" (click)="generateRecoveryCodes()" [disabled]="recoveryBusy">
                    {{ recoveryBusy ? 'Generating…' : 'Generate replacement codes' }}
                  </button>
                  <button type="button" class="secondary" (click)="registerPasskey()" [disabled]="passkeyBusy">
                    {{ passkeyBusy ? 'Waiting for authenticator…' : 'Register this browser as a passkey' }}
                  </button>
                  <p *ngIf="passkeyMessage" class="status status-partial" role="status">{{ passkeyMessage }}</p>
                  <div *ngIf="recoveryCodes.length" class="recovery-codes" aria-live="polite">
                    <p class="status-partial">Store these codes securely. They cannot be displayed again.</p>
                    <ul aria-label="One-time passkey recovery codes">
                      <li *ngFor="let code of recoveryCodes"><code>{{ code }}</code></li>
                    </ul>
                    <p class="metric-meta">Expires {{ recoveryExpiresAt | date:'medium' }}</p>
                  </div>
                </div>
              </section>
            </ng-container>
            <ng-container *ngIf="destination !== 'settings'">
              <section *ngIf="workspaceLoading" class="status" role="status" aria-live="polite">
                Loading private {{ currentItem.label.toLowerCase() }}…
              </section>
              <section *ngIf="workspaceError" class="status status-error" role="alert">
                <strong>{{ currentItem.label }} is temporarily unavailable.</strong>
                <span>{{ workspaceError }}</span>
                <button type="button" class="secondary" (click)="loadWorkspaceData()">Try again</button>
              </section>
              <section *ngIf="workspaceData as data" class="workspace-panel" aria-live="polite">
                <div class="workspace-panel-heading">
                  <div>
                    <h2>{{ currentItem.label }} overview</h2>
                    <p class="metric-meta">Source {{ data.source }} · bounded owner-scoped read</p>
                  </div>
                  <button type="button" class="secondary" (click)="loadWorkspaceData()">Refresh</button>
                </div>
                <form *ngIf="destination === 'plan'" class="quick-form" (ngSubmit)="createTask()" novalidate>
                  <label for="taskTitle">New task</label>
                  <input id="taskTitle" name="taskTitle" [(ngModel)]="taskTitle" maxlength="255" required placeholder="e.g. Prepare weekly review" />
                  <button type="submit" class="primary" [disabled]="actionBusy || !taskTitle.trim()">{{ actionBusy ? 'Saving…' : 'Add task' }}</button>
                </form>
                <form *ngIf="destination === 'calendar'" class="quick-form" (ngSubmit)="createCalendarEvent()" novalidate>
                  <label for="eventTitle">New event</label>
                  <input id="eventTitle" name="eventTitle" [(ngModel)]="eventTitle" maxlength="140" required placeholder="e.g. Focus block" />
                  <div class="form-grid">
                    <label>Starts <input name="eventStart" type="datetime-local" [(ngModel)]="eventStart" required /></label>
                    <label>Ends <input name="eventEnd" type="datetime-local" [(ngModel)]="eventEnd" required /></label>
                  </div>
                  <button type="submit" class="primary" [disabled]="actionBusy || !eventTitle.trim() || !eventStart || !eventEnd">{{ actionBusy ? 'Saving…' : 'Add event' }}</button>
                </form>
                <form *ngIf="destination === 'money'" class="quick-form" (ngSubmit)="createTransaction()" novalidate>
                  <label for="transactionAmount">New expense (minor units)</label>
                  <div class="form-grid">
                    <input id="transactionAmount" name="transactionAmount" type="number" min="1" step="1" [(ngModel)]="transactionAmount" required placeholder="2500" />
                    <input name="transactionCategory" [(ngModel)]="transactionCategory" maxlength="64" required placeholder="Category" />
                  </div>
                  <button type="submit" class="primary" [disabled]="actionBusy || !transactionAmount || !transactionCategory.trim()">{{ actionBusy ? 'Saving…' : 'Record expense' }}</button>
                </form>
                <form *ngIf="destination === 'assistant'" class="quick-form" (ngSubmit)="askAssistant()" novalidate>
                  <label for="assistantQuestion">Ask a bounded assistant question</label>
                  <textarea id="assistantQuestion" name="assistantQuestion" [(ngModel)]="assistantQuestion" maxlength="4000" required placeholder="What should I focus on today?"></textarea>
                  <button type="submit" class="primary" [disabled]="actionBusy || !assistantQuestion.trim()">{{ actionBusy ? 'Asking…' : 'Ask assistant' }}</button>
                </form>
                <form *ngIf="destination === 'sessions'" class="quick-form" (ngSubmit)="confirmSessionAction()" novalidate>
                  <label for="sessionId">Session ID</label>
                  <input id="sessionId" name="sessionId" [(ngModel)]="sessionId" maxlength="36" required placeholder="Session UUID" />
                  <label for="artifactVersion">Artifact version</label>
                  <input id="artifactVersion" name="artifactVersion" type="number" min="0" step="1" [(ngModel)]="artifactVersion" required />
                  <label for="actionItem">Confirm one action item</label>
                  <input id="actionItem" name="actionItem" [(ngModel)]="actionItem" maxlength="255" required placeholder="Exact extracted action text" />
                  <button type="submit" class="primary" [disabled]="actionBusy || !sessionId.trim() || !actionItem.trim()">{{ actionBusy ? 'Saving…' : 'Create follow-up task' }}</button>
                  <p class="field-help">This is an explicit confirmation. The transcript never creates a task automatically.</p>
                </form>
                <p *ngIf="actionMessage" class="status status-partial" role="status">{{ actionMessage }}</p>
                <div *ngIf="data.items.length" class="workspace-list">
                  <article *ngFor="let item of data.items" class="workspace-item">
                    <div>
                      <p class="metric-label">{{ item.kind }}</p>
                      <h3>{{ item.title }}</h3>
                      <p class="metric-meta">{{ item.detail }}</p>
                    </div>
                    <span *ngIf="item.status" class="item-status">{{ item.status }}</span>
                  </article>
                </div>
                <p *ngIf="!data.items.length" class="status" role="status">{{ data.emptyMessage }}</p>
              </section>
            </ng-container>
          </ng-template>
        </main>
        </div>
      </ng-container>

      <ng-template #authForm>
        <main class="auth-panel" aria-labelledby="auth-title">
          <section class="auth-card">
            <p class="eyebrow">Private workspace</p>
            <h1 id="auth-title">{{ authTitle }}</h1>
            <p class="description">{{ authDescription }}</p>
            <form (ngSubmit)="submitAuth()" #form="ngForm" novalidate>
              <label for="email">Email</label>
              <input id="email" name="email" type="email" autocomplete="username" [(ngModel)]="email" required />
              <label *ngIf="authMode === 'register'" for="displayName">Display name</label>
              <input *ngIf="authMode === 'register'" id="displayName" name="displayName" type="text" autocomplete="name" [(ngModel)]="displayName" required />
              <ng-container *ngIf="authMode !== 'recovery'">
                <label for="password">Password</label>
                <input id="password" name="password" type="password" autocomplete="current-password" [(ngModel)]="password" required />
              </ng-container>
              <ng-container *ngIf="authMode === 'recovery'">
                <label for="recoveryCode">One-time recovery code</label>
                <input id="recoveryCode" name="recoveryCode" type="text" inputmode="text" autocomplete="one-time-code" pattern="[A-Z2-7]{4}(?:-[A-Z2-7]{4}){2}" maxlength="14" [(ngModel)]="recoveryCode" required />
                <p class="field-help">Use one unused code from your recovery set. Codes are accepted once and are never stored in this browser.</p>
              </ng-container>
              <p *ngIf="authError" class="status status-error" role="alert">{{ authError }}</p>
              <button class="primary" type="submit" [disabled]="form.invalid || authBusy">
                {{ authBusy ? 'Working…' : (authMode === 'login' ? 'Sign in' : (authMode === 'register' ? 'Create account' : 'Recover access')) }}
              </button>
            </form>
            <button *ngIf="authMode === 'login'" type="button" class="secondary passkey-button" (click)="signInWithPasskey()" [disabled]="authBusy">
              {{ authBusy ? 'Working…' : 'Use a passkey' }}
            </button>
            <div class="auth-links">
              <button *ngIf="authMode !== 'login'" type="button" class="link-button" (click)="setAuthMode('login')">Back to sign in</button>
              <button *ngIf="authMode === 'login'" type="button" class="link-button" (click)="setAuthMode('register')">Create an account instead</button>
              <button *ngIf="authMode === 'login'" type="button" class="link-button" (click)="setAuthMode('recovery')">Use a recovery code</button>
            </div>
          </section>
        </main>
      </ng-template>
    </div>
  `,
  styles: [`
    :host { display: block; min-height: 100vh; color: #13212b; background: #f7f8fa; font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }
    * { box-sizing: border-box; }
    .app-shell { min-height: 100vh; }
    .topbar { align-items: center; background: #fff; border-bottom: 1px solid #d5dee4; display: flex; gap: 16px; justify-content: space-between; min-height: 64px; padding: 12px 24px; }
    .brand { color: #1f5c7a; font-size: 22px; font-weight: 700; text-decoration: none; }
    .environment, .eyebrow, .metric-meta, .freshness { color: #52616b; font-size: 13px; }
    .body-shell { display: grid; grid-template-columns: 216px minmax(0, 1fr); margin: 0 auto; max-width: 1440px; min-height: calc(100vh - 64px); }
    .rail { border-right: 1px solid #d5dee4; padding: 24px 12px; }
    .nav-item { background: transparent; border: 0; border-radius: 10px; color: #52616b; cursor: pointer; display: block; font: inherit; min-height: 44px; padding: 10px 12px; text-align: left; width: 100%; }
    .nav-item:hover, .nav-item.active { background: #eaf3f7; color: #1f5c7a; font-weight: 650; }
    button:focus-visible, a:focus-visible { outline: 3px solid #1f5c7a; outline-offset: 2px; }
    .content { padding: 40px clamp(20px, 5vw, 72px); }
    .page-heading { align-items: start; display: flex; gap: 24px; justify-content: space-between; margin-bottom: 32px; }
    .eyebrow { letter-spacing: .08em; margin: 0 0 8px; text-transform: uppercase; }
    h1, h2 { margin: 0; }
    h1 { font-size: clamp(28px, 4vw, 40px); line-height: 1.15; }
    h2 { font-size: 24px; }
    .description { color: #52616b; font-size: 16px; line-height: 1.5; margin: 10px 0 0; }
    .freshness { margin: 0; white-space: nowrap; }
    .metric-grid { display: grid; gap: 16px; grid-template-columns: repeat(auto-fit, minmax(210px, 1fr)); }
    .metric-card, .empty-state, .status { background: #fff; border: 1px solid #d5dee4; border-radius: 10px; padding: 20px; }
    .metric-label, .metric-value, .metric-meta, .trend { margin: 0; }
    .metric-label { color: #52616b; font-size: 14px; font-weight: 650; }
    .metric-value { font-size: 32px; font-weight: 700; margin-top: 12px; }
    .metric-meta { margin-top: 8px; }
    .trend { border-top: 1px solid #eef2f5; color: #227a58; font-size: 13px; line-height: 1.5; margin-top: 16px; padding-top: 12px; }
    .status { display: flex; flex-direction: column; gap: 8px; line-height: 1.5; }
    .status-error { border-color: #f1b6ae; }
    .status-partial { color: #946b00; }
    .secondary { align-self: start; background: #fff; border: 1px solid #1f5c7a; border-radius: 8px; color: #1f5c7a; cursor: pointer; font: inherit; min-height: 44px; padding: 8px 14px; }
    .topbar-action { margin-left: auto; }
    .auth-panel { align-items: center; display: flex; justify-content: center; min-height: calc(100vh - 64px); padding: 24px; }
    .auth-card { background: #fff; border: 1px solid #d5dee4; border-radius: 16px; max-width: 460px; padding: 32px; width: 100%; }
    .auth-card form { display: flex; flex-direction: column; gap: 10px; margin-top: 24px; }
    .auth-card label { color: #52616b; font-size: 14px; font-weight: 650; margin-top: 8px; }
    .auth-card input { border: 1px solid #aebdc5; border-radius: 8px; font: inherit; min-height: 44px; padding: 8px 12px; }
    .auth-card input:focus-visible { border-color: #1f5c7a; outline: 3px solid #1f5c7a; outline-offset: 2px; }
    .field-help { color: #52616b; font-size: 13px; line-height: 1.45; margin: 0; }
    .primary { background: #1f5c7a; border: 1px solid #1f5c7a; border-radius: 8px; color: #fff; cursor: pointer; font: inherit; min-height: 44px; padding: 8px 14px; }
    .primary:disabled { cursor: not-allowed; opacity: .55; }
    .passkey-button { align-self: stretch; margin-top: 12px; width: 100%; }
    .link-button { background: transparent; border: 0; color: #1f5c7a; cursor: pointer; font: inherit; margin-top: 20px; min-height: 44px; padding: 8px 0; text-decoration: underline; }
    .auth-links { display: flex; flex-wrap: wrap; gap: 8px 20px; }
    .empty-state { max-width: 720px; }
    .settings-stack { display: grid; gap: 16px; max-width: 720px; }
    .workspace-panel { display: grid; gap: 16px; max-width: 900px; }
    .workspace-panel-heading { align-items: start; display: flex; gap: 16px; justify-content: space-between; }
    .workspace-panel-heading h2 { font-size: 24px; }
    .workspace-list { display: grid; gap: 12px; }
    .quick-form { background: #fff; border: 1px solid #d5dee4; border-radius: 10px; display: grid; gap: 10px; max-width: 900px; padding: 16px; }
    .quick-form label { color: #52616b; display: grid; font-size: 13px; font-weight: 650; gap: 6px; }
    .quick-form input, .quick-form textarea { border: 1px solid #aebdc5; border-radius: 8px; font: inherit; min-height: 42px; padding: 8px 10px; }
    .quick-form textarea { min-height: 90px; resize: vertical; }
    .form-grid { display: grid; gap: 10px; grid-template-columns: repeat(auto-fit, minmax(180px, 1fr)); }
    .workspace-item { align-items: start; background: #fff; border: 1px solid #d5dee4; border-radius: 10px; display: flex; gap: 16px; justify-content: space-between; padding: 16px; }
    .workspace-item h3, .workspace-item p { margin: 0; }
    .workspace-item h3 { font-size: 18px; margin-top: 6px; }
    .item-status { background: #eaf3f7; border-radius: 999px; color: #1f5c7a; font-size: 12px; font-weight: 650; padding: 6px 10px; white-space: nowrap; }
    .recovery-codes { background: #f7f8fa; border: 1px solid #d5dee4; border-radius: 10px; margin-top: 20px; padding: 16px; }
    .recovery-codes ul { display: grid; gap: 8px; grid-template-columns: repeat(2, minmax(0, 1fr)); list-style: none; margin: 12px 0; padding: 0; }
    .recovery-codes code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 15px; letter-spacing: .05em; }
    .empty-state p { line-height: 1.5; }
    @media (max-width: 767px) {
      .topbar { padding: 12px 16px; }
      .environment { display: none; }
      .body-shell { display: block; }
      .rail { border-bottom: 1px solid #d5dee4; border-right: 0; display: flex; gap: 4px; overflow-x: auto; padding: 8px 12px; position: sticky; top: 0; z-index: 1; }
      .nav-item { flex: 0 0 auto; text-align: center; width: auto; }
      .content { padding: 28px 16px; }
      .page-heading { display: block; }
      .freshness { margin-top: 16px; }
    }
    @media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; transition: none !important; } }
    @media (prefers-color-scheme: dark) {
      :host { background: #10191f; color: #f2f6f8; }
      .topbar, .metric-card, .empty-state, .status { background: #18252d; border-color: #38505d; }
      .rail { border-color: #38505d; }
      .nav-item, .description, .environment, .eyebrow, .metric-meta, .freshness { color: #b8c6cd; }
      .nav-item:hover, .nav-item.active { background: #263d48; color: #a5e2f7; }
      .brand { color: #7cc4e5; }
      .trend { border-color: #38505d; color: #7cc4e5; }
      .secondary, .link-button { background: #18252d; color: #7cc4e5; border-color: #7cc4e5; }
      .auth-card { background: #18252d; border-color: #38505d; }
      .auth-card label, .auth-card .description, .field-help { color: #b8c6cd; }
      .auth-card input { background: #10191f; border-color: #38505d; color: #f2f6f8; }
      .recovery-codes { background: #10191f; border-color: #38505d; }
      .workspace-item { background: #18252d; border-color: #38505d; }
      .item-status { background: #263d48; color: #a5e2f7; }
      .quick-form { background: #18252d; border-color: #38505d; }
      .quick-form input, .quick-form textarea { background: #10191f; border-color: #38505d; color: #f2f6f8; }
    }
  `],
})
export class DashboardComponent {
  readonly navigation = NAVIGATION;
  destination: Destination = 'home';
  dashboard?: Dashboard;
  trends: Record<string, Trend> = {};
  error?: string;
  freshness = 'Waiting for source data';
  authenticated = false;
  authMode: 'login' | 'register' | 'recovery' = 'login';
  authBusy = false;
  authError?: string;
  email = '';
  displayName = '';
  password = '';
  recoveryCode = '';
  recoveryCodes: string[] = [];
  recoveryExpiresAt?: string;
  recoveryBusy = false;
  recoveryError?: string;
  passkeyBusy = false;
  passkeyMessage?: string;
  workspaceData?: WorkspaceData;
  workspaceLoading = false;
  workspaceError?: string;
  actionBusy = false;
  actionMessage?: string;
  taskTitle = '';
  eventTitle = '';
  eventStart = '';
  eventEnd = '';
  transactionAmount?: number;
  transactionCategory = '';
  assistantQuestion = '';
  sessionId = '';
  artifactVersion = 0;
  actionItem = '';
  private accessToken?: string;
  private workspaceRequestVersion = 0;

  constructor() {}

  get currentItem() {
    return this.navigation.find((item) => item.id === this.destination) ?? this.navigation[0];
  }

  get authTitle(): string {
    return this.authMode === 'login'
      ? 'Sign in to LifeOS'
      : (this.authMode === 'register' ? 'Create your LifeOS account' : 'Recover access to LifeOS');
  }

  get authDescription(): string {
    if (this.authMode === 'recovery') {
      return 'Use a one-time code to create a passkey session. The code stays in memory and is sent only to Identity.';
    }
    return 'Your access token stays in memory for this tab and is never written to browser storage.';
  }

  select(destination: Destination): void {
    this.destination = destination;
    this.focusMain();
    if (destination === 'home' && !this.dashboard && !this.error) this.loadDashboard();
    if (destination !== 'home' && destination !== 'settings') this.loadWorkspaceData();
  }

  focusMain(event?: Event): void {
    event?.preventDefault();
    window.setTimeout(() => document.getElementById('main')?.focus(), 0);
  }

  toggleAuthMode(): void {
    this.setAuthMode(this.authMode === 'login' ? 'register' : 'login');
  }

  setAuthMode(mode: 'login' | 'register' | 'recovery'): void {
    this.authMode = mode;
    this.authError = undefined;
    this.password = '';
    this.recoveryCode = '';
  }

  async submitAuth(): Promise<void> {
    this.authBusy = true;
    this.authError = undefined;
    const registering = this.authMode === 'register';
    const recovering = this.authMode === 'recovery';
    const endpoint = registering
      ? '/api/v1/accounts'
      : (recovering ? '/api/v1/auth/passkey/recover' : '/api/v1/auth/login');
    const body = recovering
      ? { email: this.email, code: this.recoveryCode.trim().toUpperCase() }
      : (this.authMode === 'login'
        ? { email: this.email, password: this.password }
        : { email: this.email, displayName: this.displayName, password: this.password });
    const headers: Record<string, string> = { Accept: 'application/json', 'Content-Type': 'application/json' };
    if (registering) headers['Idempotency-Key'] = crypto.randomUUID();
    try {
      const registrationResponse = await fetch(endpoint, { method: 'POST', headers, body: JSON.stringify(body) });
      if (!registrationResponse.ok) throw new Error('authentication failed');
      // Registration intentionally returns the account resource, not a bearer token. Login is a
      // separate credential exchange so account creation cannot accidentally mint a session.
      const response = registering
        ? await fetch('/api/v1/auth/login', {
          method: 'POST',
          headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
          body: JSON.stringify({ email: this.email, password: this.password }),
        })
        : registrationResponse;
      if (!response.ok) throw new Error('authentication failed');
      const result = await response.json() as { accessToken?: string };
      if (!result.accessToken) throw new Error('authentication response incomplete');
      this.accessToken = result.accessToken;
      this.authenticated = true;
      this.password = '';
      this.recoveryCode = '';
      this.freshness = 'Waiting for source data';
      this.loadDashboard();
    } catch {
      this.authError = 'We could not complete that request. Check your details and try again.';
    } finally {
      this.authBusy = false;
    }
  }

  async signInWithPasskey(): Promise<void> {
    this.authBusy = true;
    this.authError = undefined;
    try {
      if (!('PublicKeyCredential' in window) || !navigator.credentials?.get) {
        throw new Error('passkeys are not supported by this browser');
      }
      const optionsResponse = await fetch('/api/v1/auth/passkey/options', {
        method: 'POST',
        headers: { Accept: 'application/json' },
      });
      if (!optionsResponse.ok) throw new Error('passkey options unavailable');
      const options = await optionsResponse.json() as { challengeId?: string; publicKey?: Record<string, unknown> };
      if (!options.challengeId || !options.publicKey) throw new Error('passkey options incomplete');
      const credential = await navigator.credentials.get({
        publicKey: decodeRequestOptions(options.publicKey),
      }) as PublicKeyCredential | null;
      if (!credential) throw new Error('passkey assertion cancelled');
      const response = credential.response as AuthenticatorAssertionResponse;
      const assertionResponse = await fetch('/api/v1/auth/passkey/assertion', {
        method: 'POST',
        headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
        body: JSON.stringify({
          challengeId: options.challengeId,
          credential: {
            id: credential.id,
            rawId: encodeBase64Url(new Uint8Array(credential.rawId)),
            response: {
              clientDataJSON: encodeBase64Url(new Uint8Array(response.clientDataJSON)),
              authenticatorData: encodeBase64Url(new Uint8Array(response.authenticatorData)),
              signature: encodeBase64Url(new Uint8Array(response.signature)),
              userHandle: response.userHandle ? encodeBase64Url(new Uint8Array(response.userHandle)) : null,
            },
            type: credential.type,
            clientExtensionResults: credential.getClientExtensionResults(),
          },
        }),
      });
      if (!assertionResponse.ok) throw new Error('passkey assertion rejected');
      const result = await assertionResponse.json() as { accessToken?: string };
      if (!result.accessToken) throw new Error('passkey response incomplete');
      this.accessToken = result.accessToken;
      this.authenticated = true;
      this.freshness = 'Waiting for source data';
      this.loadDashboard();
    } catch {
      this.authError = 'We could not complete passkey sign-in. Try again or use another recovery method.';
    } finally {
      this.authBusy = false;
    }
  }

  signOut(): void {
    this.workspaceRequestVersion++;
    this.accessToken = undefined;
    this.authenticated = false;
    this.dashboard = undefined;
    this.trends = {};
    this.freshness = 'Waiting for source data';
    this.recoveryCodes = [];
    this.recoveryExpiresAt = undefined;
    this.recoveryError = undefined;
    this.passkeyMessage = undefined;
    this.workspaceData = undefined;
    this.workspaceError = undefined;
    this.workspaceLoading = false;
    this.actionMessage = undefined;
    this.taskTitle = '';
    this.eventTitle = '';
    this.eventStart = '';
    this.eventEnd = '';
    this.transactionAmount = undefined;
    this.transactionCategory = '';
    this.assistantQuestion = '';
    this.sessionId = '';
    this.artifactVersion = 0;
    this.actionItem = '';
  }

  generateRecoveryCodes(): void {
    this.recoveryBusy = true;
    this.recoveryError = undefined;
    fetch('/api/v1/auth/passkey/recovery-codes', {
      method: 'POST',
      headers: this.authHeaders(),
    })
      .then((response) => response.ok ? response.json() : Promise.reject(new Error('recovery codes unavailable')))
      .then((value: { codes?: string[]; expiresAt?: string }) => {
        if (!Array.isArray(value.codes) || !value.codes.length || !value.expiresAt) {
          throw new Error('recovery response incomplete');
        }
        this.recoveryCodes = value.codes;
        this.recoveryExpiresAt = value.expiresAt;
      })
      .catch(() => {
        this.recoveryCodes = [];
        this.recoveryExpiresAt = undefined;
        this.recoveryError = 'Recovery codes could not be generated. Try again later.';
      })
      .finally(() => { this.recoveryBusy = false; });
  }

  async registerPasskey(): Promise<void> {
    this.passkeyBusy = true;
    this.passkeyMessage = undefined;
    try {
      if (!('PublicKeyCredential' in window) || !navigator.credentials?.create) {
        throw new Error('passkeys are not supported by this browser');
      }
      const optionsResponse = await fetch('/api/v1/auth/passkey/registration/options', {
        method: 'POST',
        headers: this.authHeaders(),
      });
      if (!optionsResponse.ok) throw new Error('passkey registration options unavailable');
      const options = await optionsResponse.json() as { challengeId?: string; publicKey?: Record<string, unknown> };
      if (!options.challengeId || !options.publicKey) throw new Error('passkey registration options incomplete');
      const credential = await navigator.credentials.create({
        publicKey: decodeCreationOptions(options.publicKey),
      }) as PublicKeyCredential | null;
      if (!credential) throw new Error('passkey registration cancelled');
      const response = credential.response as AuthenticatorAttestationResponse;
      const registrationResponse = await fetch('/api/v1/auth/passkey/registration', {
        method: 'POST',
        headers: { ...this.authHeaders(), 'Content-Type': 'application/json' },
        body: JSON.stringify({
          challengeId: options.challengeId,
          credential: {
            id: credential.id,
            rawId: encodeBase64Url(new Uint8Array(credential.rawId)),
            response: {
              clientDataJSON: encodeBase64Url(new Uint8Array(response.clientDataJSON)),
              attestationObject: encodeBase64Url(new Uint8Array(response.attestationObject)),
              transports: response.getTransports?.() ?? [],
            },
            type: credential.type,
            clientExtensionResults: credential.getClientExtensionResults(),
          },
        }),
      });
      if (!registrationResponse.ok) throw new Error('passkey registration rejected');
      this.passkeyMessage = 'Passkey registered. You can use it on your next sign-in.';
    } catch {
      this.passkeyMessage = 'Passkey registration could not be completed. Try again or use another sign-in method.';
    } finally {
      this.passkeyBusy = false;
    }
  }

  loadDashboard(): void {
    this.error = undefined;
    this.freshness = 'Loading…';
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 5000);
    fetch('/api/v1/analytics/dashboard?periodDays=30', {
      signal: controller.signal,
      headers: this.authHeaders(),
    })
      .then((response) => (response.ok ? response.json() : Promise.reject(new Error('The dashboard source returned an error.'))))
      .then((value: Dashboard) => {
        this.dashboard = value;
        this.freshness = `Source ${value.sourceVersion} · ${value.periodDays}-day window`;
        const keys = value.metrics.map((metric) => metric.key).slice(0, 8);
        return Promise.allSettled(keys.map((key) => this.loadTrend(key)));
      })
      .catch(() => {
        this.error = 'No private metrics were loaded. Check the gateway connection and try again.';
        this.freshness = 'Source unavailable';
      })
      .finally(() => window.clearTimeout(timeout));
  }

  async createTask(): Promise<void> {
    await this.performAction('/api/v1/tasks', {
      title: this.taskTitle.trim(),
      priority: 3,
      dueAt: null,
    }, 'Task created.');
    this.taskTitle = '';
  }

  async createCalendarEvent(): Promise<void> {
    if (!this.eventStart || !this.eventEnd) return;
    await this.performAction('/api/v1/calendar/events', {
      title: this.eventTitle.trim(),
      description: null,
      startAt: new Date(this.eventStart).toISOString(),
      endAt: new Date(this.eventEnd).toISOString(),
      timeZone: Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC',
      recurrence: null,
      reminders: [],
    }, 'Calendar event created.');
    this.eventTitle = '';
    this.eventStart = '';
    this.eventEnd = '';
  }

  async createTransaction(): Promise<void> {
    if (!this.transactionAmount || !this.transactionCategory.trim()) return;
    await this.performAction('/api/v1/finance/transactions', {
      currency: 'USD',
      amountMinor: Math.trunc(this.transactionAmount),
      direction: 'EXPENSE',
      occurredOn: new Date().toISOString().slice(0, 10),
      merchant: null,
      category: this.transactionCategory.trim(),
    }, 'Expense recorded.');
    this.transactionAmount = undefined;
    this.transactionCategory = '';
  }

  async askAssistant(): Promise<void> {
    this.actionBusy = true;
    this.actionMessage = undefined;
    try {
      const result = await this.postJson('/api/v1/assistant/grounded-questions', {
        query: this.assistantQuestion.trim(),
        maxOutputTokens: 256,
        maxSources: 4,
      });
      const answer = this.isRecord(result) && typeof result['content'] === 'string'
        ? result['content']
        : 'The assistant returned a bounded response.';
      this.actionMessage = answer;
    } catch {
      this.actionMessage = 'The assistant is unavailable or does not have enough indexed evidence.';
    } finally {
      this.actionBusy = false;
    }
  }

  async confirmSessionAction(): Promise<void> {
    const sessionId = this.sessionId.trim();
    const actionItem = this.actionItem.trim();
    if (!sessionId || !actionItem || !Number.isInteger(this.artifactVersion) || this.artifactVersion < 0) return;
    this.actionBusy = true;
    this.actionMessage = undefined;
    try {
      await this.postJson(`/api/v1/media/sessions/${encodeURIComponent(sessionId)}/post-session/tasks`, {
        actionItem,
        priority: 3,
        dueAt: null,
      }, {
        'Idempotency-Key': crypto.randomUUID(),
        'If-Match': `"${this.artifactVersion}"`,
      });
      this.actionMessage = 'Follow-up task created.';
      this.actionItem = '';
      await this.loadWorkspaceData();
    } catch {
      this.actionMessage = 'The action item could not be confirmed. Check the session and artifact version, then try again.';
    } finally {
      this.actionBusy = false;
    }
  }

  private async performAction(path: string, body: Record<string, unknown>, successMessage: string): Promise<void> {
    this.actionBusy = true;
    this.actionMessage = undefined;
    try {
      await this.postJson(path, body, { 'Idempotency-Key': crypto.randomUUID() });
      this.actionMessage = successMessage;
      await this.loadWorkspaceData();
    } catch {
      this.actionMessage = 'That action could not be completed. No partial private state was shown.';
    } finally {
      this.actionBusy = false;
    }
  }

  private async postJson(path: string, body: Record<string, unknown>, extraHeaders: Record<string, string> = {}): Promise<unknown> {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 8000);
    try {
      const response = await fetch(path, {
        method: 'POST',
        signal: controller.signal,
        headers: { ...this.authHeaders(), 'Content-Type': 'application/json', ...extraHeaders },
        body: JSON.stringify(body),
      });
      if (!response.ok) throw new Error('action unavailable');
      return await response.json().catch(() => ({}));
    } finally {
      window.clearTimeout(timeout);
    }
  }

  async loadWorkspaceData(): Promise<void> {
    const destination = this.destination;
    if (destination === 'home' || destination === 'settings') return;
    const requestVersion = ++this.workspaceRequestVersion;
    this.workspaceLoading = true;
    this.workspaceError = undefined;
    this.workspaceData = undefined;
    try {
      const data = await this.fetchWorkspaceData(destination);
      if (requestVersion !== this.workspaceRequestVersion || this.destination !== destination) return;
      this.workspaceData = data;
    } catch {
      if (requestVersion !== this.workspaceRequestVersion || this.destination !== destination) return;
      this.workspaceError = 'No private data was loaded. Check the gateway connection and try again.';
    } finally {
      if (requestVersion === this.workspaceRequestVersion) this.workspaceLoading = false;
    }
  }

  private async fetchWorkspaceData(destination: Destination): Promise<WorkspaceData> {
    if (destination === 'assistant') {
      return {
        source: 'local assistant boundary',
        items: [{
          kind: 'Assistant',
          id: 'assistant-boundary',
          title: 'Grounded answers and confirmed actions',
          detail: 'Use the bounded assistant API when a conversation or document is selected. Provider and vector state remain visible as explicit partial results.',
          status: 'Ready',
        }],
        emptyMessage: 'No assistant conversations have been created yet.',
      };
    }
    if (destination === 'vault') {
      const result = await this.fetchJson('/api/v1/documents/search?q=lifeos&pageSize=20');
      return this.workspaceDataFromList('document search', this.asList(result), 'No matching documents were found.');
    }
    if (destination === 'plan') {
      const [tasks, goals] = await Promise.all([
        this.fetchJson('/api/v1/tasks?limit=50'),
        this.fetchJson('/api/v1/goals?limit=50'),
      ]);
      const items = [
        ...this.asList(tasks).map((item) => this.workspaceItem('Task', item)),
        ...this.asList(goals).map((item) => this.workspaceItem('Goal', item)),
      ];
      return { source: 'task-goal service', items, emptyMessage: 'No tasks or goals have been created yet.' };
    }
    if (destination === 'calendar') {
      const result = await this.fetchJson('/api/v1/calendar/events?limit=50');
      return this.workspaceDataFromList('calendar service', this.asList(result), 'No calendar events are scheduled yet.');
    }
    if (destination === 'money') {
      const [budgets, transactions] = await Promise.all([
        this.fetchJson('/api/v1/finance/budgets?page=0&pageSize=25'),
        this.fetchJson('/api/v1/finance/transactions?page=0&pageSize=25'),
      ]);
      const items = [
        ...this.asList(budgets).map((item) => this.workspaceItem('Budget', item)),
        ...this.asList(transactions).map((item) => this.workspaceItem('Transaction', item)),
      ];
      return { source: 'finance service', items, emptyMessage: 'No budgets or transactions have been recorded yet.' };
    }
    if (destination === 'sessions') {
      const [assets, sessions] = await Promise.all([
        this.fetchJson('/api/v1/media/assets?limit=25'),
        this.fetchJson('/api/v1/media/sessions?limit=25'),
      ]);
      const items = [
        ...this.asList(sessions).map((item) => this.workspaceItem('Session', item)),
        ...this.asList(assets).map((item) => this.workspaceItem('Media asset', item)),
      ];
      return { source: 'media service', items, emptyMessage: 'No sessions or media assets exist yet.' };
    }
    throw new Error('unsupported destination');
  }

  private async fetchJson(path: string): Promise<unknown> {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 5000);
    try {
      const response = await fetch(path, { signal: controller.signal, headers: this.authHeaders() });
      if (!response.ok) throw new Error('workspace source unavailable');
      return await response.json() as unknown;
    } finally {
      window.clearTimeout(timeout);
    }
  }

  private workspaceDataFromList(source: string, list: Record<string, unknown>[], emptyMessage: string): WorkspaceData {
    return { source, items: list.map((item) => this.workspaceItem(source, item)), emptyMessage };
  }

  private asList(value: unknown): Record<string, unknown>[] {
    if (Array.isArray(value)) return value.filter(this.isRecord);
    if (this.isRecord(value)) {
      for (const key of ['items', 'content', 'events', 'documents', 'tasks', 'goals', 'budgets', 'transactions', 'sessions', 'assets']) {
        const nested = value[key];
        if (Array.isArray(nested)) return nested.filter(this.isRecord);
      }
    }
    return [];
  }

  private isRecord(value: unknown): value is Record<string, unknown> {
    return typeof value === 'object' && value !== null && !Array.isArray(value);
  }

  private workspaceItem(kind: string, item: Record<string, unknown>): WorkspaceItem {
    const id = this.firstString(item, ['id', 'taskId', 'goalId', 'eventId', 'budgetId', 'transactionId', 'sessionId', 'assetId'])
      ?? 'owner-scoped-resource';
    const title = this.firstString(item, ['title', 'name', 'category', 'subject', 'kind']) ?? kind;
    const status = this.firstString(item, ['status', 'state', 'lifecycleStatus']);
    const detailParts = [
      this.firstString(item, ['startAt', 'occurredOn', 'periodStart', 'dueAt', 'targetDate', 'createdAt']),
      this.firstString(item, ['description', 'currency', 'amountMinor', 'allocationMinor', 'targetMinor']),
    ].filter((part): part is string => Boolean(part));
    return { kind, id, title, detail: detailParts.join(' · ') || 'Owner-scoped resource', status };
  }

  private firstString(item: Record<string, unknown>, keys: string[]): string | undefined {
    for (const key of keys) {
      const value = item[key];
      if (typeof value === 'string' && value.trim()) return value.trim();
      if (typeof value === 'number' || typeof value === 'boolean') return String(value);
    }
    return undefined;
  }

  private loadTrend(metricKey: string): Promise<void> {
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 3000);
    return fetch(`/api/v1/analytics/trends?metricKey=${encodeURIComponent(metricKey)}&periodDays=30&days=30`, {
      signal: controller.signal,
      headers: this.authHeaders(),
    })
      .then((response) => (response.ok ? response.json() : Promise.reject(new Error('trend unavailable'))))
      .then((value: Trend) => { this.trends[metricKey] = value; })
      .catch(() => undefined)
      .finally(() => window.clearTimeout(timeout));
  }

  trendValues(trend: Trend): string {
    return trend.points.map((point) => point.value).join(' · ');
  }

  private authHeaders(): Record<string, string> {
    return this.accessToken
      ? { Accept: 'application/json', Authorization: `Bearer ${this.accessToken}` }
      : { Accept: 'application/json' };
  }
}

function decodeRequestOptions(source: Record<string, unknown>): PublicKeyCredentialRequestOptions {
  const options = { ...source } as unknown as PublicKeyCredentialRequestOptions & {
    challenge: string;
    allowCredentials?: Array<Record<string, unknown>>;
  };
  if (typeof options.challenge !== 'string') throw new Error('passkey challenge is invalid');
  const decoded: PublicKeyCredentialRequestOptions = {
    ...options,
    challenge: decodeBase64Url(options.challenge),
  };
  if (Array.isArray(options.allowCredentials)) {
    decoded.allowCredentials = options.allowCredentials.map((credential) => ({
      type: (credential.type ?? 'public-key') as PublicKeyCredentialType,
      id: decodeBase64Url(String(credential.id ?? '')),
      ...(Array.isArray(credential.transports) ? { transports: credential.transports as AuthenticatorTransport[] } : {}),
    }));
  }
  return decoded;
}

function decodeCreationOptions(source: Record<string, unknown>): PublicKeyCredentialCreationOptions {
  const sourceOptions = { ...source } as unknown as PublicKeyCredentialCreationOptions & {
    challenge: string;
    user: Record<string, unknown>;
    excludeCredentials?: Array<Record<string, unknown>>;
  };
  if (typeof sourceOptions.challenge !== 'string' || !sourceOptions.user || typeof sourceOptions.user.id !== 'string') {
    throw new Error('passkey creation options are invalid');
  }
  const options: PublicKeyCredentialCreationOptions = {
    ...sourceOptions,
    challenge: decodeBase64Url(sourceOptions.challenge),
    user: {
      ...sourceOptions.user,
      id: decodeBase64Url(sourceOptions.user.id),
    } as PublicKeyCredentialUserEntity,
  };
  if (Array.isArray(sourceOptions.excludeCredentials)) {
    options.excludeCredentials = sourceOptions.excludeCredentials.map((credential) => ({
      type: (credential.type ?? 'public-key') as PublicKeyCredentialType,
      id: decodeBase64Url(String(credential.id ?? '')),
      ...(Array.isArray(credential.transports) ? { transports: credential.transports as AuthenticatorTransport[] } : {}),
    }));
  }
  return options;
}

function decodeBase64Url(value: string): ArrayBuffer {
  const normalized = value.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(value.length / 4) * 4, '=');
  const binary = atob(normalized);
  return Uint8Array.from(binary, (character) => character.charCodeAt(0)).buffer;
}

function encodeBase64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((byte) => { binary += String.fromCharCode(byte); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

bootstrapApplication(DashboardComponent).catch(() => {
  document.body.textContent = 'LifeOS dashboard could not start.';
});
