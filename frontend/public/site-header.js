/**
 * The account control, the "Subscribe" link and the status dot of the shared header
 * (backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt's siteHeaderBar, and the header of
 * each static guide page under frontend/public/guides).
 *
 * A Ktor page and a static guide page are both a pure read: the server never calls
 * Principals.resolve and it sets no cookie, so the server HTML can only ever render a visitor
 * with no account and a dot in the "checking" state. This script fills in the true state after
 * the page loads, the same order AppShellComponent itself renders in, before its own first
 * account answer and its own first health answer arrive.
 *
 * A plain, external file, with no build step and no dependency, in the way of
 * frontend/public/topic-start.js. It sends one GET /api/account and one GET /api/health for each
 * page view, with credentials of the same origin only, and never a cookie of its own. The two
 * requests run side by side, and a failure of one never stops the other, the same rule app.ts
 * states for its own two start-up reads. On an account answer that is not 200 the account
 * control changes nothing, so a visitor with no account keeps the "Sign in" header a crawler and
 * a browser with no JavaScript both already see. This script reads only the status and the
 * numbers of the account answer, and it never writes the learner's email address into the page:
 * every write below uses textContent, className, setAttribute or the hidden property, never
 * innerHTML, insertAdjacentHTML or document.write.
 */
(function () {
  'use strict';

  var accountLink = document.getElementById('site-header-account');
  var countSpan = document.getElementById('site-header-count');
  var subscribeLink = document.getElementById('site-header-subscribe');
  var dot = document.getElementById('site-header-dot');

  /** The statuses that carry a live count. The same set allowance-meter.component.ts reads as
   * METERED_STATUSES: every other status shows the "Subscribe" link, and no invented count. */
  var METERED_STATUSES = { TRIALING: true, ACTIVE: true, CANCELLED: true, PAST_DUE: true };

  /** The word after the count, the same rule allowance-meter.component.ts's periodWords states. */
  function periodWords(status) {
    return status === 'TRIALING' ? 'in your trial' : 'today';
  }

  /**
   * Fills `countSpan` with the same four-part structure allowance-meter.component.ts renders for
   * its own `.allowance-meter__count`: the full text ("N of M tokens left"), visible at 768px and
   * above; the short form ("N / M tokens"), visible below 768px instead, through guides.css's own
   * `.bar__count-full` and `.bar__count-short` rules (issue #139: the full text is too wide for a
   * narrow phone once it names the unit); the period word, next to the full text, hidden below
   * 768px by guides.css's own `.bar__count-period` rule, because the full sentence once ran into
   * the account link at a phone width (issue #133); and one more element that carries the full
   * sentence for a screen reader only, at every width, through guides.css's own `.sr-only` rule.
   * Every element is built with document.createElement and filled with textContent, never a
   * template string parsed as markup.
   *
   * "tokens" stays plural even when `remaining` is 1: issue #139's own decision on the
   * application header, so that a pool of many tokens with one left is never misnamed as "1
   * token". This function keeps that same rule, and not a singular form.
   */
  function renderCount(remaining, allowance, status) {
    if (!countSpan) {
      return;
    }
    while (countSpan.firstChild) {
      countSpan.removeChild(countSpan.firstChild);
    }

    var full = document.createElement('span');
    full.className = 'bar__count-full';
    full.setAttribute('aria-hidden', 'true');
    full.textContent = remaining + ' of ' + allowance + ' tokens left';

    var short = document.createElement('span');
    short.className = 'bar__count-short';
    short.setAttribute('aria-hidden', 'true');
    short.textContent = remaining + ' / ' + allowance + ' tokens';

    var period = document.createElement('span');
    period.className = 'bar__count-period';
    period.setAttribute('aria-hidden', 'true');
    period.textContent = ' ' + periodWords(status);

    var screenReaderText = document.createElement('span');
    screenReaderText.className = 'sr-only';
    screenReaderText.textContent = remaining + ' of ' + allowance + ' tokens left ' + periodWords(status);

    countSpan.appendChild(full);
    countSpan.appendChild(short);
    countSpan.appendChild(period);
    countSpan.appendChild(screenReaderText);
  }

  /**
   * Shows or hides the "Subscribe" link for one account status, the same two rules
   * allowance-meter.component.ts states for the application header: a learner in trial gets the
   * link next to the count, at 768px and above only (the `bar__subscribe--trial` class, and
   * guides.css's own media rule for it); a status with no live count gets the link at every
   * width, in place of the count, with no `--trial` class.
   *
   * A status with no live count also hides `countSpan`. `countSpan` stays in the row, empty, for
   * a visitor with no account yet and for a metered status, so that a later fetch answer never
   * shifts the row (see this function's own caller). Once the answer names a status with no live
   * count at all, no reflow can still happen, and `allowance-meter.component.ts`'s own template
   * renders no count element at all for this case — only the Subscribe link. A real run at 360px
   * found the public header's own always-present empty `countSpan` costing two flex gaps the
   * application header never spends here, which pushed the account link left of the wordmark by
   * under 1px. Hiding `countSpan` in this one branch matches the application header's own layout.
   */
  function renderSubscribeLink(status) {
    if (!subscribeLink) {
      return;
    }
    if (status === 'TRIALING') {
      subscribeLink.className = 'bar__subscribe bar__subscribe--trial';
      subscribeLink.hidden = false;
    } else if (METERED_STATUSES[status] === true) {
      // ACTIVE, CANCELLED or PAST_DUE: the count already names the learner's own allowance, and
      // the application header shows no Subscribe link next to it for these statuses either.
      subscribeLink.hidden = true;
    } else {
      // NONE, EXPIRED, or a status this script does not yet know: no live count to show, so the
      // one path forward is Subscribe, at every width, and countSpan carries no content this or
      // any later step could show.
      subscribeLink.className = 'bar__subscribe';
      subscribeLink.hidden = false;
      if (countSpan) {
        countSpan.hidden = true;
      }
    }
  }

  if (accountLink) {
    fetch('/api/account', { credentials: 'same-origin' })
      .then(function (response) {
        if (response.status !== 200) {
          return null;
        }
        return response.json();
      })
      .then(function (account) {
        if (!account) {
          return;
        }

        accountLink.textContent = 'Account';
        accountLink.setAttribute('href', '/account');

        if (METERED_STATUSES[account.status] === true) {
          renderCount(account.remaining, account.allowance, account.status);
        }
        renderSubscribeLink(account.status);
      })
      .catch(function () {
        // A network failure changes nothing: the page keeps the signed-out header.
      });
  }

  /** The same four labels status-dot.component.ts uses, so a screen reader announces the same
   * words on every page. */
  var DOT_LABELS = {
    checking: 'Backend: checking',
    ok: 'Backend ok',
    degraded: 'Backend degraded',
    unreachable: 'Backend unreachable',
  };

  /** Sets the dot's class, aria-label and title from one state, with setAttribute and className
   * only — the same two properties the server sets for the "checking" state it ships with. */
  function setDotState(state) {
    if (!dot) {
      return;
    }
    dot.className = 'dot dot--' + state;
    dot.setAttribute('aria-label', DOT_LABELS[state]);
    dot.setAttribute('title', DOT_LABELS[state]);
  }

  if (dot) {
    fetch('/api/health', { credentials: 'same-origin' })
      .then(function (response) {
        // The same rule app.ts's own try/catch states: an answer that is not ok is a failure,
        // and not a partial success. The real backend answers 503, and never 200, when Mongo
        // does not ping — see HealthRoutesTest — so this branch and the ok branch below never
        // both fire for one answer.
        if (!response.ok) {
          throw new Error('health check answered ' + response.status);
        }
        return response.json();
      })
      .then(function (health) {
        setDotState(health.mongo ? 'ok' : 'degraded');
      })
      .catch(function () {
        setDotState('unreachable');
      });
  }
})();
