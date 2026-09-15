import { ComponentFixture, TestBed } from '@angular/core/testing';
import { TermsPageComponent } from './terms-page.component';

describe('TermsPageComponent', () => {
  let fixture: ComponentFixture<TermsPageComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TermsPageComponent] });
    fixture = TestBed.createComponent(TermsPageComponent);
    fixture.detectChanges();
  });

  it('renders a title', () => {
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Terms');
  });

  it("marks every place that still waits for the owner's legal text", () => {
    const text = fixture.nativeElement.textContent as string;
    const markers = text.match(/\[owner text\]/g) ?? [];
    expect(markers.length).toBeGreaterThan(0);
  });
});
