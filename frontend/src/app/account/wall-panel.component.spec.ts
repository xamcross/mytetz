import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { WallCode, WallPanelComponent } from './wall-panel.component';

describe('WallPanelComponent', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [WallPanelComponent],
      providers: [provideRouter([])],
    });
  });

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

  /**
   * Issue #137 moves the checkout call out of this panel and onto the plan screen. This test
   * replaces `the subscribe panel fetches a checkout url`, `marks Subscribe busy, with a label
   * that names the work, while the request runs`, and `the subscribe panel reports a failed
   * checkout request` — every one of which asserted a `POST /api/billing/checkout` call this
   * panel no longer makes. What is left to assert here is that the control is a plain link to
   * `/subscribe`, read off a real `Router` through `provideRouter` — `RouterLink` computes `href`
   * from the router's own route table, so this is not a value this test invented itself.
   */
  it('the Subscribe control is a link to /subscribe, and not a checkout button', () => {
    const fixture = create('SUBSCRIPTION_REQUIRED');

    const link = fixture.nativeElement.querySelector('.wall-panel__subscribe') as HTMLAnchorElement;
    expect(link.tagName.toLowerCase()).toBe('a');
    expect(link.getAttribute('href')).toBe('/subscribe');
    expect(fixture.nativeElement.querySelector('button')).toBeNull();
  });
});
