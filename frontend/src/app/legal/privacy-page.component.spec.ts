import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PrivacyPageComponent } from './privacy-page.component';

describe('PrivacyPageComponent', () => {
  let fixture: ComponentFixture<PrivacyPageComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [PrivacyPageComponent],
      providers: [provideRouter([])],
    });
    fixture = TestBed.createComponent(PrivacyPageComponent);
    fixture.detectChanges();
  });

  const text = (): string => fixture.nativeElement.textContent as string;

  it('names every cookie mytetz sets', () => {
    expect(text()).toContain('mytetz_pid');
    expect(text()).toContain('mytetz_sid');
    expect(text()).toContain('mytetz_g_state');
    expect(text()).toContain('mytetz_g_verifier');
  });

  it('states mytetz sets no tracking cookie, so no banner asks for consent', () => {
    expect(text()).toMatch(/no tracking cookie/i);
    expect(text()).toMatch(/consent/i);
  });

  it('names every processor', () => {
    for (const processor of [
      'fly.io',
      'Frankfurt',
      'MongoDB Atlas',
      'eu-central-1',
      'Cloudflare',
      'Anthropic',
      'Resend',
      'Google',
      'Freemius',
    ]) {
      expect(text()).toContain(processor);
    }
  });

  it('states the two retention windows', () => {
    expect(text()).toMatch(/30 days?/);
    expect(text()).toMatch(/90 days?/);
  });

  it('describes account deletion and links to the account page', () => {
    expect(text()).toMatch(/delete/i);
    expect(fixture.nativeElement.querySelector('a[href="/account"]')).toBeTruthy();
  });

  it("marks every place that still waits for the owner's legal text", () => {
    const markers = text().match(/\[owner text\]/g) ?? [];
    expect(markers.length).toBeGreaterThan(0);
  });
});
