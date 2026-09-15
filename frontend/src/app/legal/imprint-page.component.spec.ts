import { ComponentFixture, TestBed } from '@angular/core/testing';
import { ImprintPageComponent } from './imprint-page.component';

describe('ImprintPageComponent', () => {
  let fixture: ComponentFixture<ImprintPageComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [ImprintPageComponent] });
    fixture = TestBed.createComponent(ImprintPageComponent);
    fixture.detectChanges();
  });

  it('renders a title', () => {
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Imprint');
  });

  it("marks every place that still waits for the owner's legal text", () => {
    const text = fixture.nativeElement.textContent as string;
    const markers = text.match(/\[owner text\]/g) ?? [];
    expect(markers.length).toBeGreaterThan(0);
  });
});
