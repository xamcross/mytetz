import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { TermsPageComponent } from './terms-page.component';

describe('TermsPageComponent', () => {
  let fixture: ComponentFixture<TermsPageComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TermsPageComponent],
      providers: [provideRouter([])],
    });
    fixture = TestBed.createComponent(TermsPageComponent);
    fixture.detectChanges();
  });

  it('renders a title', () => {
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Terms');
  });

  it('carries no owner-text marker; every section holds real text', () => {
    const text = fixture.nativeElement.textContent as string;
    const markers = text.match(/\[owner text\]/g) ?? [];
    expect(markers.length).toBe(0);
  });

  it('states the price, the trial, the daily allowance and the right of withdrawal', () => {
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('$12');
    expect(text).toMatch(/40 tokens/);
    expect(text).toMatch(/25 tokens/);
    expect(text).toMatch(/14 days/);
    expect(text).toMatch(/withdrawal/i);
  });
});
