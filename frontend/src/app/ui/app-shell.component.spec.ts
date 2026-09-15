import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { provideRouter } from '@angular/router';
import { AppShellComponent } from './app-shell.component';
import { AccountStore } from '../core/account.store';
import { AccountView } from '../core/models';

/**
 * No spec file existed for this component before this task. Task 10 is the first to give
 * `AppShellComponent` a dependency worth a test of its own — the allowance meter, which reads
 * `AccountStore` — so this file is new rather than modified, unlike every other file this task
 * touches.
 */
describe('AppShellComponent', () => {
  let fixture: ComponentFixture<AppShellComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [AppShellComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([])],
    });
    fixture = TestBed.createComponent(AppShellComponent);
    fixture.componentRef.setInput('backend', 'ok');
  });

  it('places the allowance meter beside the status dot', () => {
    fixture.detectChanges();

    expect(fixture.nativeElement.querySelector('app-allowance-meter')).toBeTruthy();
    expect(fixture.nativeElement.querySelector('app-status-dot')).toBeTruthy();
  });

  it("shows the signed-in learner's count once the account store holds a view", () => {
    const view: AccountView = {
      email: 'learner@example.com',
      status: 'ACTIVE',
      trialEndsAtEpochMillis: null,
      currentPeriodEndsAtEpochMillis: null,
      allowance: 25,
      remaining: 20,
      resetsAtEpochMillis: null,
    };
    TestBed.inject(AccountStore).view.set(view);

    fixture.detectChanges();

    // Asserted as one relation, not as two separate `toContain` calls, which would pass just as
    // well against the two numbers swapped in the template.
    expect(fixture.nativeElement.textContent).toContain('20 of 25');
  });

  it('puts the mark left of the wordmark, inside the one link home', () => {
    fixture.detectChanges();

    const home = fixture.nativeElement.querySelector('.bar__mark');
    // The mark must be the first child, not merely present somewhere in the bar. A mark that
    // follows the word, or that sits outside the link, passes a `toBeTruthy` check and fails the
    // design.
    expect(home.firstElementChild?.tagName.toLowerCase()).toBe('app-logo-mark');
    expect(home.textContent.trim()).toBe('mytetz');
  });

  it('shows a Sign in link to /auth while no account is signed in', () => {
    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('a.bar__account') as HTMLAnchorElement;
    expect(link.textContent?.trim()).toBe('Sign in');
    expect(link.getAttribute('href')).toBe('/auth');
  });

  it('shows an Account link to /account once a sign-in fills the account view', () => {
    const view: AccountView = {
      email: 'learner@example.com',
      status: 'ACTIVE',
      trialEndsAtEpochMillis: null,
      currentPeriodEndsAtEpochMillis: null,
      allowance: 25,
      remaining: 20,
      resetsAtEpochMillis: null,
    };
    TestBed.inject(AccountStore).view.set(view);

    fixture.detectChanges();

    const link = fixture.nativeElement.querySelector('a.bar__account') as HTMLAnchorElement;
    expect(link.textContent?.trim()).toBe('Account');
    expect(link.getAttribute('href')).toBe('/account');
  });

  it('carries a footer with links to the privacy, terms and imprint pages', () => {
    fixture.detectChanges();

    const links = Array.from(
      fixture.nativeElement.querySelectorAll('footer a'),
    ) as HTMLAnchorElement[];
    const hrefs = links.map((a) => a.getAttribute('href'));
    expect(hrefs).toContain('/privacy');
    expect(hrefs).toContain('/terms');
    expect(hrefs).toContain('/imprint');
  });
});
