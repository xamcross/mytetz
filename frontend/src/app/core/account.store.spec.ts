import { TestBed } from '@angular/core/testing';
import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting, HttpTestingController } from '@angular/common/http/testing';
import { AccountStore } from './account.store';
import { AccountView } from './models';

const view: AccountView = {
  email: 'learner@example.com',
  status: 'TRIALING',
  trialEndsAtEpochMillis: null,
  currentPeriodEndsAtEpochMillis: null,
  allowance: 20,
  remaining: 17,
  resetsAtEpochMillis: 1723027200000,
};

describe('AccountStore', () => {
  let store: AccountStore;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [AccountStore, provideHttpClient(), provideHttpClientTesting()],
    });
    store = TestBed.inject(AccountStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('signedIn is false before a load', () => {
    expect(store.signedIn()).toBe(false);
    expect(store.view()).toBeNull();
  });

  it('the store treats a 401 as signed out', async () => {
    const loaded = store.load();
    http
      .expectOne('/api/account')
      .flush(
        { code: 'SIGN_IN_REQUIRED', message: 'sign in to continue' },
        { status: 401, statusText: '' },
      );
    await loaded;

    expect(store.view()).toBeNull();
    expect(store.signedIn()).toBe(false);
    // A 401 is not a fault — no error may surface for the commonest outcome of all.
    expect(store.error()).toBeNull();
  });

  it('the store holds the view when signed in', async () => {
    const loaded = store.load();
    http.expectOne('/api/account').flush(view);
    await loaded;

    expect(store.view()).toEqual(view);
    expect(store.signedIn()).toBe(true);
    expect(store.error()).toBeNull();
  });

  it('the store reports a non-401 failure as an error', async () => {
    const loaded = store.load();
    http.expectOne('/api/account').flush(null, { status: 500, statusText: 'Server Error' });
    await loaded;

    expect(store.view()).toBeNull();
    // The property that matters: a 500 must not read the same as a 401. Both leave the view
    // null, so the error signal is what tells them apart.
    expect(store.error()).not.toBeNull();
  });
});
