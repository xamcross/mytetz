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
});
