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

  it('carries no owner-text marker; every section holds real text', () => {
    const markers = text().match(/\[owner text\]/g) ?? [];
    expect(markers.length).toBe(0);
  });

  it('names the controller and gives an email contact', () => {
    expect(text()).toContain('mytetz.com');
    expect(fixture.nativeElement.querySelector('a[href="mailto:support@mytetz.com"]')).toBeTruthy();
  });

  it('names Turnstile and Wikimedia as conditional processors', () => {
    expect(text()).toContain('Turnstile');
    expect(text()).toContain('Wikimedia');
  });

  it('states the rights of the data subject and the minimum age', () => {
    expect(text()).toMatch(/right of access|Article 15/);
    expect(text()).toMatch(/Article 77/);
    expect(text()).toMatch(/16 years/);
  });
});
