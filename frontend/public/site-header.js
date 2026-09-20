/**
 * The account control of the shared header
 * (backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt's siteHeaderBar, and the header of
 * each static guide page under frontend/public/guides).
 *
 * A Ktor page and a static guide page are both a pure read: the server never calls
 * Principals.resolve and it sets no cookie, so the server HTML can only ever render a visitor
 * with no account. This script fills in the true state after the page loads, the same order
 * AppShellComponent itself renders in, before its own first account answer arrives.
 *
 * A plain, external file, with no build step and no dependency, in the way of
 * frontend/public/topic-start.js. It sends one GET /api/account for each page view, with
 * credentials of the same origin only, and never a cookie of its own. On an answer that is not
 * 200 it changes nothing, so a visitor with no account keeps the "Sign in" header a crawler and a
 * browser with no JavaScript both already see. It reads only the status and the numbers of the
 * answer, and it never writes the learner's email address into the page: every write below uses
 * textContent or setAttribute, never innerHTML, insertAdjacentHTML or document.write.
 */
(function () {
  'use strict';

  var accountLink = document.getElementById('site-header-account');
  var countSpan = document.getElementById('site-header-count');
  if (!accountLink) {
    return;
  }

  /** The statuses that carry a live count. The same set allowance-meter.component.ts reads as
   * METERED_STATUSES: every other status shows the account link alone, and no invented count. */
  var METERED_STATUSES = { TRIALING: true, ACTIVE: true, CANCELLED: true, PAST_DUE: true };

  /** The word after the count, the same rule allowance-meter.component.ts's periodWords states. */
  function periodWords(status) {
    return status === 'TRIALING' ? 'in your trial' : 'today';
  }

  /**
   * Fills `countSpan` with the same three-part structure allowance-meter.component.ts renders
   * for its own `.allowance-meter__count`: the digits, visible at every width; the period word,
   * next to them, hidden below 768px by guides.css's own `.bar__count-period` rule, because the
   * full sentence once ran into the account link at a phone width (issue #133); and one more
   * element that carries the full sentence for a screen reader only, at every width, through
   * guides.css's own `.sr-only` rule. Every element is built with document.createElement and
   * filled with textContent, never a template string parsed as markup.
   */
  function renderCount(remaining, allowance, status) {
    if (!countSpan) {
      return;
    }
    while (countSpan.firstChild) {
      countSpan.removeChild(countSpan.firstChild);
    }

    var digits = document.createElement('span');
    digits.className = 'bar__count-digits';
    digits.setAttribute('aria-hidden', 'true');
    digits.textContent = remaining + ' of ' + allowance + ' left';

    var period = document.createElement('span');
    period.className = 'bar__count-period';
    period.setAttribute('aria-hidden', 'true');
    period.textContent = ' ' + periodWords(status);

    var full = document.createElement('span');
    full.className = 'sr-only';
    full.textContent = remaining + ' of ' + allowance + ' left ' + periodWords(status);

    countSpan.appendChild(digits);
    countSpan.appendChild(period);
    countSpan.appendChild(full);
  }

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
    })
    .catch(function () {
      // A network failure changes nothing: the page keeps the signed-out header.
    });
})();
