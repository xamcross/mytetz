import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { NotFoundPageComponent } from './not-found-page.component';

describe('NotFoundPageComponent', () => {
  let fixture: ComponentFixture<NotFoundPageComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [NotFoundPageComponent],
      providers: [provideRouter([])],
    });
    fixture = TestBed.createComponent(NotFoundPageComponent);
    fixture.detectChanges();
  });

  it('renders a title', () => {
    expect(fixture.nativeElement.querySelector('h1').textContent).toContain('Page not found');
  });

  it('links back to the dashboard', () => {
    const link = fixture.nativeElement.querySelector('a[href="/"]');
    expect(link).toBeTruthy();
    expect(link.textContent).toContain('Back to the dashboard');
  });
});
