import { ComponentFixture, TestBed } from '@angular/core/testing';
import { NodeView } from '../core/models';
import { TrailRailComponent } from './trail-rail.component';

/** One node, the minimum a trail can hold. Depth and verb match what the store gives the root
 * node of a fresh session. */
const NODE_A: NodeView = {
  nodeId: 'n0',
  parentNodeId: null,
  explanationKey: 'k0',
  span: '',
  verb: 'SEED',
  variant: 0,
  depth: 0,
};

const NODE_B: NodeView = {
  nodeId: 'n1',
  parentNodeId: 'n0',
  explanationKey: 'k1',
  span: 'pillars',
  verb: 'EXPLAIN',
  variant: 0,
  depth: 1,
};

describe('TrailRailComponent', () => {
  let fixture: ComponentFixture<TrailRailComponent>;

  beforeEach(() => {
    TestBed.configureTestingModule({ imports: [TrailRailComponent] });
    fixture = TestBed.createComponent(TrailRailComponent);
    fixture.componentRef.setInput('currentNodeId', 'n0');
    fixture.componentRef.setInput('topicLabel', 'Quantum Physics');
  });

  const head = (): HTMLElement => fixture.nativeElement.querySelector('.trail__head');

  it('reads "1 step" for a trail of one node, not the plural', () => {
    fixture.componentRef.setInput('nodes', [NODE_A]);
    fixture.detectChanges();

    expect(head().textContent?.trim()).toBe('Your trail · 1 step');
  });

  it('reads "2 steps" for a trail of two nodes', () => {
    fixture.componentRef.setInput('nodes', [NODE_A, NODE_B]);
    fixture.detectChanges();

    expect(head().textContent?.trim()).toBe('Your trail · 2 steps');
  });
});
