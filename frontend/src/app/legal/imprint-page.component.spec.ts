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

  it('carries no owner-text marker; every section holds real text', () => {
    const text = fixture.nativeElement.textContent as string;
    const markers = text.match(/\[owner text\]/g) ?? [];
    expect(markers.length).toBe(0);
  });

  it('names mytetz.com as the provider and gives an email contact', () => {
    const text = fixture.nativeElement.textContent as string;
    expect(text).toContain('mytetz.com');
    expect(fixture.nativeElement.querySelector('a[href="mailto:support@mytetz.com"]')).toBeTruthy();
  });
});
