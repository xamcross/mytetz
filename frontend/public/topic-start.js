/**
 * The behaviour of the "Start with this topic" button on a topic page
 * (backend/api/src/main/kotlin/com/mytetz/api/TopicPageHtml.kt).
 *
 * A plain, external file, with no build step and no dependency. The topic page is Ktor HTML, not
 * an Angular page, so this file cannot import ApiService. It sends the same request and reads the
 * same response ApiService.createSession sends today (frontend/src/app/core/api.service.ts), and
 * it reuses the wording of describeSessionError and formatRetryAfter from
 * frontend/src/app/catalog/catalog-page.component.ts, so a learner reads the same words on both
 * pages.
 *
 * A network response never carries HTML from a model or from a learner, so no value here goes near
 * innerHTML: every write to the page uses textContent.
 */
(function () {
  'use strict';

  var button = document.getElementById('topic-start-button');
  var errorParagraph = document.getElementById('topic-start-error');
  if (!button || !errorParagraph) {
    return;
  }

  var idleLabel = button.textContent;
  var busy = false;

  /** The same rule as formatRetryAfter in catalog-page.component.ts. */
  function formatRetryAfter(seconds) {
    if (seconds < 60) {
      var wholeSeconds = Math.max(1, Math.ceil(seconds));
      return wholeSeconds + ' second' + (wholeSeconds === 1 ? '' : 's');
    }
    var minutes = Math.ceil(seconds / 60);
    if (minutes < 60) {
      return minutes + ' minute' + (minutes === 1 ? '' : 's');
    }
    var hours = Math.ceil(minutes / 60);
    return hours + ' hour' + (hours === 1 ? '' : 's');
  }

  /**
   * The same rule as describeSessionError in catalog-page.component.ts: the backend's own message
   * is shown verbatim, and a retryAfter value adds a wait sentence. A 429 answer carries
   * retryAfter, so a learner reads how long to wait. A 503 answer carries none, so a learner reads
   * only the backend's own "the service is busy" style message, with no invented wait.
   */
  function describeFailure(body) {
    if (body && typeof body.message === 'string') {
      if (body.retryAfter) {
        return body.message + ' Try again in ' + formatRetryAfter(body.retryAfter) + '.';
      }
      return body.message;
    }
    return 'Could not start that topic. Please try again.';
  }

  function setBusy(isBusy) {
    busy = isBusy;
    button.disabled = isBusy;
    button.textContent = isBusy ? 'Starting…' : idleLabel;
  }

  // A browser can keep this page in its back/forward cache, with its script state. A learner who
  // goes to the reader and then presses Back gets the page as it was at the moment of the
  // navigation: a disabled button with the label "Starting…". The `pageshow` event with
  // `persisted` set to true marks that case. The button then goes back to its idle state. No test
  // covers this: an automated browser does not use the back/forward cache.
  window.addEventListener('pageshow', function (event) {
    if (event.persisted) {
      setBusy(false);
    }
  });

  button.addEventListener('click', function () {
    // A second click while the first request is still in flight sends no second request.
    if (busy) {
      return;
    }

    errorParagraph.textContent = '';
    setBusy(true);

    var topicSlug = button.getAttribute('data-topic-slug') || '';

    fetch('/api/sessions', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ topicSlug: topicSlug }),
    })
      .then(function (response) {
        return response
          .json()
          .catch(function () {
            return null;
          })
          .then(function (body) {
            return { ok: response.ok, body: body };
          });
      })
      .then(function (result) {
        if (!result.ok) {
          setBusy(false);
          errorParagraph.textContent = describeFailure(result.body);
          return;
        }

        var sessionId = result.body && result.body.sessionId;
        // A defence in depth check, and not the only guard: SessionRoutesTest and
        // TopicPageRoutesTest already pin the server's own id shape. A value that does not match
        // never reaches the address bar.
        if (typeof sessionId !== 'string' || !/^[A-Za-z0-9_-]+$/.test(sessionId)) {
          setBusy(false);
          errorParagraph.textContent = 'Could not start that topic. Please try again.';
          return;
        }

        window.location.href = '/learn/' + sessionId;
      })
      .catch(function () {
        setBusy(false);
        errorParagraph.textContent = 'Could not start that topic. Please try again.';
      });
  });
})();
