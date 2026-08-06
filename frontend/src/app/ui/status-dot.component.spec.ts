import { ComponentFixture, TestBed } from '@angular/core/testing';
import { BackendState, StatusDotComponent } from './status-dot.component';

describe('StatusDotComponent', () => {
  let fixture: ComponentFixture<StatusDotComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [StatusDotComponent] });
    fixture = TestBed.createComponent(StatusDotComponent);
  });

  const dot = (state: BackendState): HTMLElement => {
    fixture.componentRef.setInput('state', state);
    fixture.detectChanges();
    return fixture.nativeElement.querySelector('.dot');
  };

  it('states the result in words as well as in colour', () => {
    // The colour alone is not the signal. A reader who cannot tell teal from red still needs the
    // answer, so the words carry it and the colour repeats it.
    expect(dot('ok').getAttribute('aria-label')).toBe('Backend ok');
    expect(dot('degraded').getAttribute('aria-label')).toBe('Backend degraded');
    expect(dot('unreachable').getAttribute('aria-label')).toBe('Backend unreachable');
    expect(dot('checking').getAttribute('aria-label')).toBe('Backend: checking');
  });

  it('gives each result its own modifier class', () => {
    expect(dot('ok').classList.contains('dot--ok')).toBe(true);
    expect(dot('degraded').classList.contains('dot--degraded')).toBe(true);
    expect(dot('unreachable').classList.contains('dot--unreachable')).toBe(true);
    expect(dot('checking').classList.contains('dot--checking')).toBe(true);
  });

  it('repeats the label in the title, so a pointer reaches it too', () => {
    expect(dot('degraded').getAttribute('title')).toBe('Backend degraded');
  });
});
