import { TURNSTILE_SCRIPT_URL, TurnstileApi, loadTurnstileScript } from './turnstile';

function fakeApi(): TurnstileApi {
  return { render: vi.fn(() => 'widget-1'), reset: vi.fn() };
}

/** The script tag `loadTurnstileScript` appended, or `null` when it appended none. */
function findScriptTag(): HTMLScriptElement | null {
  return document.head.querySelector<HTMLScriptElement>(`script[src="${TURNSTILE_SCRIPT_URL}"]`);
}

describe('loadTurnstileScript', () => {
  afterEach(() => {
    delete window.turnstile;
    findScriptTag()?.remove();
  });

  it('resolves at once, and appends no script, when window.turnstile is already set', async () => {
    const api = fakeApi();
    window.turnstile = api;

    const resolved = await loadTurnstileScript();

    expect(resolved).toBe(api);
    expect(findScriptTag()).toBeNull();
  });

  it('appends exactly one script tag at the confirmed Cloudflare URL', () => {
    void loadTurnstileScript();

    const tags = document.head.querySelectorAll(`script[src="${TURNSTILE_SCRIPT_URL}"]`);
    expect(tags.length).toBe(1);
  });

  it('resolves with window.turnstile once the script reports it has loaded', async () => {
    const promise = loadTurnstileScript();
    const script = findScriptTag();
    if (!script) throw new Error('fixture error: no script tag was appended');

    const api = fakeApi();
    // The real script defines `window.turnstile` as a side effect of running, before it fires its
    // own `load` event. This reproduces that ordering, and does not assume it.
    window.turnstile = api;
    script.dispatchEvent(new Event('load'));

    await expect(promise).resolves.toBe(api);
  });

  it('rejects when load fires but window.turnstile was never defined', async () => {
    const promise = loadTurnstileScript();
    const script = findScriptTag();
    if (!script) throw new Error('fixture error: no script tag was appended');

    script.dispatchEvent(new Event('load'));

    await expect(promise).rejects.toThrow();
  });

  it('rejects when the script fails to load', async () => {
    const promise = loadTurnstileScript();
    const script = findScriptTag();
    if (!script) throw new Error('fixture error: no script tag was appended');

    script.dispatchEvent(new Event('error'));

    await expect(promise).rejects.toThrow();
  });
});
