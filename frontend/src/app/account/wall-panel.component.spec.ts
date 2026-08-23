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

  it('the subscribe panel fetches a checkout url', async () => {
    const fixture = create('SUBSCRIPTION_REQUIRED');
    const redirect = vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});

    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    expect(button.getAttribute('type')).toBe('button');
    button.click();

    const req = http.expectOne('/api/billing/checkout');
    expect(req.request.method).toBe('POST');
    req.flush({
      url: 'https://checkout.freemius.com/product/1/plan/2/?user_email=a%40b.com&readonly_user=true',
    });
    await fixture.whenStable();

    // The server built the URL. This component only follows the URL. It never builds one itself.
    expect(redirect).toHaveBeenCalledWith(
      'https://checkout.freemius.com/product/1/plan/2/?user_email=a%40b.com&readonly_user=true',
    );
  });

  it('the subscribe panel reports a failed checkout request', async () => {
    const fixture = create('SUBSCRIPTION_REQUIRED');
    const redirect = vi.spyOn(fixture.componentInstance, 'redirect').mockImplementation(() => {});

    const button = fixture.nativeElement.querySelector('button') as HTMLButtonElement;
    button.click();

    http
      .expectOne('/api/billing/checkout')
      .flush(null, { status: 500, statusText: 'Server Error' });
    await fixture.whenStable();
    fixture.detectChanges();

    expect(redirect).not.toHaveBeenCalled();
    expect(fixture.nativeElement.textContent as string).toContain('Could not start checkout');
  });
});
