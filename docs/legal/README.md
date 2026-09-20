# The facts behind the legal texts

This file supports issue #24. It lists the facts the three legal texts state, each fact
with its source in the code. It lists the official legal sources this work reads. It
lists each gap that Rule 3 of issue #24 leaves open, with the article that asks for
more.

This file is not a legal opinion. It is a fact table for a reviewer to check against
the code.

## 1. Account data

| Fact | Source |
| --- | --- |
| The account document stores: an id, an email address, an optional Google subject id, a created-at time, and a last-seen-at time. | `backend/account/src/main/kotlin/com/mytetz/account/Account.kt:54-61` |
| A learner signs in with an email magic link or with Google. There is no password field and no password route anywhere in the account module. | `backend/account/src/main/kotlin/com/mytetz/account/Account.kt` (whole file); `backend/account/src/main/kotlin/com/mytetz/account/AccountService.kt` (whole file, no password method) |
| The account document stores no name, no phone number, and no postal address. | `backend/account/src/main/kotlin/com/mytetz/account/Account.kt:54-61` |
| A Google sign-in reads three claims only: `sub`, `email`, `email_verified`. It stores `sub` (as `googleSub`) and the email address. It asks Google for the `openid email` scope only. | `backend/account/src/main/kotlin/com/mytetz/account/GoogleOAuth.kt:107-118,250-256` |
| A magic-link token stores the SHA-256 hash of the token, an email address, and two times. The raw token itself never reaches storage. | `backend/account/src/main/kotlin/com/mytetz/account/Account.kt:64-79` |
| A session document stores a session id, a user id, and three times. | `backend/account/src/main/kotlin/com/mytetz/account/Account.kt:89-97` |

## 2. Cookies

| Cookie | Purpose | Lifetime | Flags | Strictly necessary? | Source |
| --- | --- | --- | --- | --- | --- |
| `mytetz_pid` | Identifies a browser before sign-in, for the daily allowance and the rate limit. | 1 year in the browser (`maxAge`); the value is a random id with no further meaning on its own. | `HttpOnly`, `Secure` (default; off only with `MYTETZ_COOKIE_SECURE=false`), `SameSite=Lax` | Yes. The site cannot meter the free trial or the rate limit without it. | `backend/api/src/main/kotlin/com/mytetz/api/Principal.kt:71,104,148-165` |
| `mytetz_sid` | Keeps a learner signed in. | 1 year in the browser (`maxAge`), but the server treats the session as expired after 30 days, sliding forward on use. | `HttpOnly`, `Secure`, `SameSite=Lax` | Yes. Sign-in cannot work without it. | `backend/api/src/main/kotlin/com/mytetz/api/Principal.kt:125,193-206`; `backend/account/src/main/kotlin/com/mytetz/account/AccountService.kt:178-182` |
| `mytetz_g_state` | Protects the Google sign-in step against a forged request. | 600 seconds (10 minutes). | `HttpOnly`, `Secure`, `SameSite=Lax` | Yes. It exists only to secure one sign-in attempt. | `backend/api/src/main/kotlin/com/mytetz/api/AuthRoutes.kt:51,54,576-585` |
| `mytetz_g_verifier` | Carries the PKCE proof for the Google sign-in step. | 600 seconds (10 minutes). | `HttpOnly`, `Secure`, `SameSite=Lax` | Yes. Same reason as `mytetz_g_state`. | `backend/api/src/main/kotlin/com/mytetz/api/AuthRoutes.kt:51,57,576-585` |

The code sets no analytics cookie and no advertising cookie. A search across the
frontend and the backend for "analytics", "gtag", "pixel", "doubleclick", "hotjar",
"mixpanel" and "segment" finds no match. Confirmed by a repository-wide search on
2026-09-20.

## 3. Third parties

| Recipient | What it receives, and when | Source |
| --- | --- | --- |
| Anthropic | The highlighted phrase, its sentence, and the chain of earlier phrases and texts in the same session, for one explanation or diagram. Never a name, an email address, a user id, or an IP address. | `backend/graph/src/main/kotlin/com/mytetz/graph/PromptBuilder.kt` (builds the prompt from `Ancestor`/`span`/`spanSentence` only); `frontend/src/app/legal/privacy-page.component.ts:51-54` |
| Google | The standard OAuth request (client id, redirect address, a code challenge) when a learner starts a Google sign-in. Google answers with an ID token; mytetz reads only `sub`, `email` and `email_verified` from it. | `backend/account/src/main/kotlin/com/mytetz/account/GoogleOAuth.kt:107-118,135-175,250-256` |
| Freemius | The learner's email address, on the checkout link (`user_email`, `readonly_user=true`) and on a customer-portal request (`{"email": …}`). A subscription id, on a reconciliation read. Freemius also sends mytetz a signed webhook. Freemius is the merchant of record (see section 5). | `backend/api/src/main/kotlin/com/mytetz/api/BillingRoutes.kt:61,93-97,166-199`; `backend/api/src/main/kotlin/com/mytetz/api/FreemiusApiClient.kt:152-327` |
| Cloudflare | Runs the network edge and, when a widget is solved, the Turnstile bot check, for every visit to `mytetz.com`. | `docs/deploy.md:1-10,451-548` |
| fly.io | Hosts the application container, in Frankfurt. | `docs/deploy.md:16-26` |
| MongoDB Atlas | Holds the database, tier M0, on AWS in `eu-central-1` (Frankfurt). | `docs/deploy.md:24` |
| Cloudflare Turnstile | Only when `MYTETZ_TURNSTILE_SECRET` is set: the site sends the widget token (and, when known, the caller's IP address) to `challenges.cloudflare.com` in front of a magic-link request and the start of a Google sign-in. When the key is not set, the check always passes and the site never contacts Cloudflare for it. | `backend/api/src/main/kotlin/com/mytetz/api/Turnstile.kt:81-146`; `docs/deploy.md:719-751` |
| Wikimedia | Only when `MYTETZ_COMMONS_IMAGES=true` (off by default since 2026-09-19): (1) the server sends one search request to `commons.wikimedia.org`, built from the highlighted phrase or the model's own search terms, with a header that names the site and no learner IP address and no account data; (2) the learner's own browser then loads the picture directly from `upload.wikimedia.org`, with `referrerpolicy="no-referrer"`, so Wikimedia's server does not learn which mytetz page asked for it, but it does receive the browser's IP address, the same as any direct image request on the web. | `backend/api/src/main/kotlin/com/mytetz/api/CommonsClient.kt:344-367,404-426`; `frontend/src/app/reader/media-renderer.component.ts:65-70,123-131`; `docs/deploy.md:859-891` |
| A log or alert provider | Not confirmed. No such provider is set up today. `docs/deploy.md` states plainly: "No drain, alert, or uptime check exists yet," and that once a provider is chosen, it will receive a user id (in a `BILLING_DRIFT` line) and an email address (in an `ACCOUNT_LINK_CONFLICT` line). | `docs/deploy.md:342-345,385,389,432-447` |

## 4. Retention

| Store | Rule | Source |
| --- | --- | --- |
| Sign-in session | The server treats a session as expired 30 days after its last use, and slides that time forward on use, at most once an hour. | `backend/account/src/main/kotlin/com/mytetz/account/AccountService.kt:118-130,178-182` |
| Magic-link token | Expires 15 minutes after it is sent, and is deleted the instant it is used. | `backend/account/src/main/kotlin/com/mytetz/account/MagicLinkService.kt:72`; `backend/account/src/main/kotlin/com/mytetz/account/AccountRepository.kt:97-117` |
| Billing event record | Expires 90 days after it is received. | `backend/billing/src/main/kotlin/com/mytetz/billing/BillingRepository.kt:37,50-53` |
| An explanation (the shared text a topic shows) | May be removed once it is old (more than 90 days by default), has never been read again, and no learner's own open session still points at it. A seed explanation is never removed this way. This store holds no personal data, so this rule is a storage rule, not a privacy rule. | `backend/api/src/main/kotlin/com/mytetz/api/EvictionConfig.kt`; `docs/deploy.md:212-238` |
| Account deletion | Removes: the account row and every sign-in session; every reading session; every quiz attempt; the daily allowance counter. It never removes an explanation — an explanation belongs to no single learner, is shared with other learners, and holds no personal data. | `backend/api/src/main/kotlin/com/mytetz/api/AuthRoutes.kt:388-403,420-450`; `backend/account/src/main/kotlin/com/mytetz/account/AccountService.kt:171-174` |
| The billing/subscription row after account deletion | Not confirmed. The account-deletion route does not call a delete method on the subscription store. | `backend/api/src/main/kotlin/com/mytetz/api/AuthRoutes.kt:420-450` (no call found) |
| Logs, in general | Not confirmed. No log retention is set up today; see the log-provider row in section 3. | `docs/deploy.md:342-347` |
| A database backup | Not confirmed. `docs/deploy.md` states no backup policy for the Atlas cluster beyond the free tier's own auto-pause after about 60 days of no use. | `docs/deploy.md:579` |

## 5. Public explanation pages

A public explanation page shows the learner's own highlighted phrase as the page's
heading, together with the explanation text, at a fixed web address. This page is
addressable once any learner reaches that exact phrase in that exact place, before
any review, marked so that a search engine does not index it. Once an operator
reviews and approves the page, it also appears on the `/glossary` page and a search
engine may index it. The page carries no name, no email address and no other detail
that names the learner who first highlighted the phrase — but the phrase itself, in
the learner's own words, can stay visible to the public, including a person who has
never signed in.

Source: `docs/superpowers/specs/2026-09-19-public-surface-design.md`, section 7
(lines 356-448), on the page's address, the `noindex` state before a review, and the
review and the glossary; `backend/api/src/main/kotlin/com/mytetz/api/FaqRoutes.kt:69-70,130-132`.

## 6. Price, trial and the token allowance

| Fact | Value | Source |
| --- | --- | --- |
| Monthly price | $12 | `backend/api/src/main/kotlin/com/mytetz/api/BillingRoutes.kt:61` |
| Trial length | 7 days | `docs/deploy.md:191` |
| Trial tokens | 40 | `docs/deploy.md:190` |
| Subscriber daily tokens | 25 | `docs/deploy.md:193` |
| What one token buys | One new explanation, or one quiz | `backend/api/src/main/kotlin/com/mytetz/api/FaqRoutes.kt:86,106` |
| Grace period after a missed renewal webhook | 3 extra days | `docs/deploy.md:192` |
| Cancellation | The account page, "Manage subscription," opens the Freemius customer portal. | `frontend/src/app/account/account-page.component.ts:76-81,168-185`; `backend/api/src/main/kotlin/com/mytetz/api/FreemiusApiClient.kt:257-327` |

These numbers agree with `/faq` (`backend/api/src/main/kotlin/com/mytetz/api/FaqRoutes.kt:84-113`),
`/subscribe` (`frontend/src/app/account/subscribe-page.component.ts:76-119`), and
`llms.txt` (`frontend/public/llms.txt:18-21`), read on 2026-09-20.

## Official sources read for this work

| Source | Read on | What it gave |
| --- | --- | --- |
| `https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX:32002L0058` | 2026-09-20 | The full text of Article 5(3) of the ePrivacy Directive: a cookie needs consent, unless it is strictly necessary for a service the user asked for. |
| `https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32011L0083` | 2026-09-20 | A partial read of Directive 2011/83/EU: Article 9's 14-day withdrawal period; Article 16(m)'s exception for digital content; the existence and purpose of the Annex I(B) model withdrawal form. |
| `https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32019L0770` | 2026-09-20 | A summary-level read of Directive (EU) 2019/770 on the conformity of a digital service with the contract, and the trader's duty to keep supplying it. |
| `https://freemius.com/blog/merchant-of-record/` | 2026-09-20 | Confirms Freemius names itself the merchant of record: "the legal entity responsible for selling your product... handles taxes, global compliance, payments, and chargebacks." |
| `https://privacy.claude.com/en/articles/7996866-how-long-do-you-retain-my-data` | 2026-09-20 | Anthropic states it deletes API inputs and outputs "within 30 days of receipt or generation." |
| `https://www.anthropic.com/legal/commercial-terms` | 2026-09-20 | States "Anthropic may not train models on Customer Content from Services." |
| `https://developers.google.com/terms/api-services-user-data-policy` | 2026-09-20 | States a developer's privacy policy must disclose how the application uses Google user data, and the Limited Use rules. |
| `https://support.google.com/accounts/answer/1350409` | 2026-09-20 | States 13 years as the baseline minimum age for a Google Account, with several countries set higher, up to 16. |

### A source this work could not read in full

The GDPR itself, `https://eur-lex.europa.eu/legal-content/EN/TXT/?uri=CELEX%3A32016R0679`
(and its `/eli/reg/2016/679/oj` form, and its PDF form), did not load past the
recitals through this session's fetch tool: the tool returned only part of the
document, ending inside the "whereas" clauses, before the numbered articles. So this
work never read the exact wording of Articles 6, 8, 12, 13, 15 to 22 or 77. The
privacy text below names each right by its well-known name and its article number
(access, rectification, erasure, and so on) rather than quoting the article, and
never states a word of the regulation as a verbatim quote. `docs/legal/privacy.md`
should be checked against the article text directly once a full copy is at hand.

## Gaps under Rule 3

The owner gave two values: "mytetz.com" and `support@mytetz.com`. No other name, no
address, no register number, no VAT number, no supervisory authority and no
governing-law country is stated anywhere in the three texts. Each row below names a
place the law asks for more than these two values.

| Gap | What the law asks for | Article |
| --- | --- | --- |
| The controller's identity and address | "The identity and the contact details of the controller" | GDPR, Article 13(1)(a) |
| A legal entity name, a postal address, and (where the operator's country asks for one) a register number, on the imprint | Most imprint laws in the EU ask for the trader's name, legal form and geographic address | Directive 2000/31/EC (the e-Commerce Directive), Article 5(1) |
| A supervisory authority to name for a complaint | "The right to lodge a complaint with a supervisory authority" | GDPR, Article 77 |
| A named representative and register entry, on the imprint | The existing page headings "Represented by" and "Register entry" | (page headings from issue #5, not a specific law cited here) |
| The country whose law governs the contract | A governing-law clause ordinarily names one country | (no specific article; Rule 3 of issue #24 asks for a neutral clause instead) |

The three texts below use a neutral governing-law clause instead of a named
country, as Rule 3 of issue #24 instructs.
