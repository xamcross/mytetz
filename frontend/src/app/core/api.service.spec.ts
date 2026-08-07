import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { ApiService } from './api.service';
import { AccountView, SessionView, TopicSummary } from './models';

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

  it('fetches one topic by slug', async () => {
    const topic = {
      slug: 'quantum-physics',
      title: 'Quantum Physics',
      category: 'Physics',
      summary: 'A summary.',
    };

    const promise = service.topic('quantum-physics');
    const req = http.expectOne('/api/catalog/topics/quantum-physics');
    expect(req.request.method).toBe('GET');
    req.flush(topic);

    await expect(promise).resolves.toEqual(topic);
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

  it('fetches the account view', async () => {
    const account: AccountView = {
      email: 'learner@example.com',
      status: 'TRIALING',
      allowance: 20,
      remaining: 17,
      resetsAtEpochMillis: 1723027200000,
    };

    const promise = service.account();
    const req = http.expectOne('/api/account');
    expect(req.request.method).toBe('GET');
    req.flush(account);

    await expect(promise).resolves.toEqual(account);
  });

  it('requests a magic link for the given email', async () => {
    const promise = service.requestMagicLink('learner@example.com');
    const req = http.expectOne('/api/auth/magic-link');
    expect(req.request.method).toBe('POST');
    expect(req.request.body).toEqual({ email: 'learner@example.com' });
    req.flush(null, { status: 204, statusText: 'No Content' });

    await expect(promise).resolves.toBeNull();
  });

  it('signs out of the current session', async () => {
    const promise = service.signOut();
    const req = http.expectOne('/api/auth/sign-out');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });

    await expect(promise).resolves.toBeNull();
  });

  it('signs out of every session', async () => {
    const promise = service.signOutAll();
    const req = http.expectOne('/api/auth/sign-out-all');
    expect(req.request.method).toBe('POST');
    req.flush(null, { status: 204, statusText: 'No Content' });

    await expect(promise).resolves.toBeNull();
  });
});
