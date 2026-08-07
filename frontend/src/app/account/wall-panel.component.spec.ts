import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { WallCode, WallPanelComponent } from './wall-panel.component';

describe('WallPanelComponent', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [WallPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  function create(code: WallCode): ComponentFixture<WallPanelComponent> {
    const fixture = TestBed.createComponent(WallPanelComponent);
    fixture.componentRef.setInput('code', code);
    fixture.detectChanges();
    return fixture;
  }

  it('the two wall codes do not read the same', () => {
    const exhausted = create('TRIAL_EXHAUSTED').nativeElement.textContent as string;
    const required = create('SUBSCRIPTION_REQUIRED').nativeElement.textContent as string;

    expect(exhausted).not.toBe(required);
  });

  it('TRIAL_EXHAUSTED names the trial and SUBSCRIPTION_REQUIRED does not', () => {
    // A learner whose trial worked and ran out reads a different first line than a learner whose
    // access lapsed or never started. Naming the trial for the second learner would be false: they
    // may never have had one.
    const exhausted = create('TRIAL_EXHAUSTED').nativeElement.textContent as string;
    const required = create('SUBSCRIPTION_REQUIRED').nativeElement.textContent as string;

    expect(exhausted.toLowerCase()).toContain('trial');
    expect(required.toLowerCase()).not.toContain('trial');
  });

  it('holds a subscribe button that is present and inert', () => {
    // Task 13 wires this button to `POST /api/billing/checkout`. That route does not exist yet,
    // so the button must call nothing on its own — proven here by clicking it and checking that
    // no request went out, not only by inspecting its markup.
    const fixture = create('SUBSCRIPTION_REQUIRED');

    const button = fixture.nativeElement.querySelector('button');
    expect(button).toBeTruthy();
    expect(button.getAttribute('type')).toBe('button');

    button.click();

    http.expectNone('/api/billing/checkout');
  });
});
