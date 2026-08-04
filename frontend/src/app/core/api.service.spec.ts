import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { ApiService } from './api.service';
import { SessionView, TopicSummary } from './models';

describe('ApiService', () => {
  let service: ApiService;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [ApiService, provideHttpClient(), provideHttpClientTesting()],
    });
    service = TestBed.inject(ApiService);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('fetches health from /api/health', async () => {
    const promise = service.health();
    http.expectOne('/api/health').flush({ status: 'ok', mongo: true });
    await expect(promise).resolves.toEqual({ status: 'ok', mongo: true });
  });

  it('fetches topics with no query parameter when none is given', async () => {
    const topics: TopicSummary[] = [
      { slug: 'quantum-physics', title: 'Quantum Physics', category: 'science', summary: '...' },
    ];

    const promise = service.topics();
    const req = http.expectOne((r) => r.url === '/api/catalog/topics');
    expect(req.request.params.has('q')).toBe(false);
    req.flush(topics);

    await expect(promise).resolves.toEqual(topics);
  });

  it('fetches topics filtered by q when one is given', async () => {
    const topics: TopicSummary[] = [
      { slug: 'quantum-physics', title: 'Quantum Physics', category: 'science', summary: '...' },
    ];

    const promise = service.topics('quan');
    const req = http.expectOne(
      (r) => r.url === '/api/catalog/topics' && r.params.get('q') === 'quan',
    );
    req.flush(topics);

    await expect(promise).resolves.toEqual(topics);
  });

  it('creates a session by posting the topic slug', async () => {
    const session: SessionView = {
      sessionId: 's1',
      topicSlug: 'quantum-physics',
      rootNodeId: 'n1',
      currentNodeId: 'n1',
      nodes: [],
      explanations: {},
    };

    const promise = service.createSession('quantum-physics');
    const req = http.expectOne('/api/sessions');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ topicSlug: 'quantum-physics' });
    req.flush(session);

    await expect(promise).resolves.toEqual(session);
  });

  it('fetches a session by id', async () => {
    const session: SessionView = {
      sessionId: 's1',
      topicSlug: 'quantum-physics',
      rootNodeId: 'n1',
      currentNodeId: 'n1',
      nodes: [],
      explanations: {},
    };

    const promise = service.session('s1');
    const req = http.expectOne('/api/sessions/s1');
    expect(req.request.method).toBe('GET');
    req.flush(session);

    await expect(promise).resolves.toEqual(session);
  });
});
