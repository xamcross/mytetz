# Design review — mytetz (`xamcross/mytetz`, https://mytetz.com)

Reviewed at `origin/main` = `9c965cb`. No file changed. The skill `frontend-design:frontend-design` loaded and guided the review.

## Status on 2026-09-19

- An Opus subagent wrote this review with the `frontend-design` skill. It read the code at commit `9c965cb`, and it changed no file.
- No browser ran. Each contrast ratio is computed from the token values. Each control height and the header overflow of F5 are arithmetic from the CSS.
- The main session confirmed five findings against `main` at `10a66e6`: F2, F3, F4 and F6 in full, and the CSS of F5. It did not confirm the other findings. Each issue below says which part of its evidence is confirmed.
- #88, #89 and #91 changed the account files after commit `9c965cb`, so some line numbers of F5, F7, F14 and F15 are old.
- #103 found two wrong values in this review. Section 3.2 names `#8ab6aa` for `--mt-edge`. That value measures 2.25:1 on `--mt-surface`, not "near 3:1". #103 uses `#4a9d84` instead, at 3.26:1.
- Animation L names the pair `--mt-amber-deep` on `--mt-chip` for the progress band. That pair measures 1.45:1, not 3:1. #103 uses `--mt-amber-ink-2` on `--mt-chip` instead, at 4.33:1.

| Issue | Holds | Kind |
|---|---|---|
| #97 | F4, the contrast of the guide page buttons (work package 1) | bug |
| #98 | F2, the press of a ghost pill | bug |
| #99 | F3, the live region of the stream | bug |
| #100 | F5, the header with the allowance meter | bug |
| #101 | F6, the sign-in landing page after a failed link | bug |
| #102 | Work package 2: F1, F16, the motion tokens, the focus ring, `.mt-sr-only` | enhancement |
| #103 | Work package 3: section 3.2, F10, the `::selection` rule | enhancement |
| #104 | Work package 5: animations A, B, C, D, E, F, L and M | enhancement |
| #105 | Work package 6: F8, F9, F11, F13, F17, F18 and animation G | enhancement |
| #106 | Work package 7, plus F7 of work package 4: F12, F14, F15, animations H, I, J and N | enhancement |

Work package 4 has no issue of its own. #99, #100 and #101 hold three of its findings, and #106 holds F7.

---

---

## THE THREE BEST SUGGESTIONS, FOR THE LEAST EFFORT

1. Fix the guide CTA contrast. White on `--mt-coral` measures **3.01:1** and fails AA. `guides.css:162-175` and `:421-436`; one file repairs 14 controls on 7 pages.
2. Give `.mt-pill` a hover state, and repair the ghost press. `styles.css:209-260`. Today 72 controls have no hover, and a ghost pill **gains** a shadow when a learner presses it.
3. Stop the per-token live region on the stream. `focus-card.component.ts:88` announces every streamed token to a screen reader.
All three are CSS or a one-attribute template change. No test asserts an `opacity`, a `transform`, an `animation` or a `box-shadow` anywhere in the suite, so all three are safe.

---

## 1. THE SYSTEM TODAY

### 1.1 What is distinctive in Candy, and must stay

- **The offset lift.** `0 4px 0 <colour>` is a hard shadow with no blur, directly below the card. It makes a card read as a physical object above the page.
- **The press that conserves the edge.** `.mt-pill:active` moves the control down 2px and shrinks the shadow to 2px. The bottom of the shadow stays in place. This is the best detail in the system. It must drive every motion.
- **Two rounded faces.** Fredoka carries a heading, a card title, a trail label and a verb name. Figtree carries prose and a control.
- **The colour rule of spec §3.3.** Coral is the one primary action. Teal is the current position. Amber means "look at this" and never "click this".
- **The uppercase eyebrow.** It is not chrome here. It carries `Step 3 · Explain` and `Your trail · 4 steps`. It is content. Keep it.
- **The honest progress track.** The card shows an indeterminate band and never an invented percentage. Keep this rule.

### 1.2 The tokens

`frontend/src/styles.css` is the token file. `frontend/angular.json` names it, and it is the only global stylesheet.

| Group | Tokens | State |
|---|---|---|
| Surface | `--mt-page --mt-surface --mt-sunk --mt-border --mt-rule --mt-chip --mt-skeleton --mt-skeleton-2` | Complete |
| Ink | `--mt-ink --mt-prose --mt-muted --mt-faint` | Complete |
| Coral | `--mt-coral --mt-coral-deep --mt-coral-pale --mt-coral-text --mt-coral-press` | `--mt-coral-pale` is dead |
| Teal | `--mt-teal --mt-teal-deep --mt-teal-pale` | `--mt-teal-pale` is dead |
| Amber | `--mt-amber --mt-amber-deep --mt-amber-bg --mt-amber-ink --mt-amber-ink-2` | Complete |
| Error | `--mt-err-bg --mt-err-border --mt-err-ink --mt-err-ink-2` | Complete |
| Radius | `--mt-r-card:26px --mt-r-panel:22px --mt-r-row:16px` | Complete |
| Shadow | `--mt-lift --mt-lift-card --mt-lift-coral --mt-lift-teal --mt-float` | Complete |
| Type | `--mt-display --mt-body` | **No size, weight or line-height token exists** |
| Space | none | **No spacing token exists** |
| Motion | none | **No duration or easing token exists** |

Three gaps follow from the table.

- **There is no type scale token.** Spec §3.6 defines eight roles. Each component repeats a raw `px` value. The audit found 56 `font-size` declarations across 14 components, and they use 12 distinct values: 10, 11, 12, 13, 14, 15, 16, 17, 19, 20, 22, 23, 26, 28, 32, 34. Three of them (`.legal-page p` 15px, `guides.css p` 17px, `.focus__body` 19px) set the size of long-form reading text, and no rule connects them.
- **There is no spacing token.** The gap values 6, 8, 9, 10, 12, 14, 16, 20, 24, 28 appear as raw numbers.
- **There is no motion token.** Section 4 of this report supplies one.

### 1.3 Where a component uses a raw value in place of a token

No component under `frontend/src/app/` holds a raw hex colour. That rule holds, and it is well kept. The failures are elsewhere.

| Place | Raw value today | The token that names it |
|---|---|---|
| `guides.css:168` | `color: #fff` | `var(--mt-surface)` |
| `guides.css:428` | `color: #fff` | `var(--mt-surface)` |
| `guides.css:167` | `box-shadow: 0 4px 0 var(--mt-coral-deep)` | `--mt-lift-coral` (not declared in `guides.css`) |
| `guides.css:174` | `box-shadow: 0 2px 0 var(--mt-coral-deep)` | The press shadow, un-named anywhere |
| `guides.css:265` | `box-shadow: 0 4px 0 var(--mt-amber-deep)` | The amber lift, un-named anywhere |
| `guides.css:386` | `box-shadow: 0 5px 0 var(--mt-border)` | `--mt-lift-card` (not declared in `guides.css`) |
| `guides.css:435` | `box-shadow: 0 2px 0 var(--mt-coral-deep)` | The press shadow |
| `catalog-page.component.ts:281` | `box-shadow: 0 6px 0 var(--mt-border)` | The hover lift, un-named anywhere |
| `catalog-page.component.ts:285` | `box-shadow: 0 2px 0 var(--mt-border)` | The press shadow |
| `styles.css:229` | `box-shadow: 0 2px 0 var(--mt-border)` | The press shadow |
| `styles.css:240` | `box-shadow: 0 2px 0 var(--mt-coral-deep)` | The press shadow |
| `styles.css:249` | `box-shadow: 0 2px 0 var(--mt-teal-deep)` | The press shadow |

The press shadow `0 2px 0 <colour>` appears six times as a raw value. The hover shadow `0 6px 0` appears once. Add `--mt-press`, `--mt-press-coral`, `--mt-press-teal` and `--mt-lift-hover`, and the system then owns its own press.

### 1.4 Where two components solve one thing in two ways

1. **The visually-hidden helper, four copies, three files.** `catalog-page.component.ts:202-209` (named `.catalog__label`), `catalog-page.component.ts:210-217`, `reader-page.component.ts:234-241`, `account-page.component.ts:230-237`. All four declare the same six properties. Move one `.mt-sr-only` to `styles.css`.
2. **The pressable card, two ways.** `.mt-pill` presses with `:active` and a transition (`styles.css:223-230`). `.topic__button` re-declares the same transition and adds a hover (`catalog-page.component.ts:275-286`). Two components own the same idea.
3. **The teal "current" row, two copies.** `.mt-pill--teal` (`styles.css:242-247`) and `.trail__item--current` (`trail-rail.component.ts:109-114`) declare the same four properties. The comment at `trail-rail.component.ts:103-108` states the reason, and the reason is sound. The fix is a `.mt-tone-teal` tone class with no press, which both can use.
4. **The coral primary, two copies.** `.mt-pill--coral` (`styles.css:233-238`) and `.picker__verb--primary` (`verb-picker.component.ts:153-158`). The same four properties, the same stated reason.
5. **The amber "chosen" state, hand-rolled.** `quiz-panel.component.ts:129-133` re-declares `--mt-amber-bg`, `--mt-amber` and `--mt-amber-ink`. `.mt-chip--amber` (`styles.css:280-284`) already draws exactly that, and nothing uses it.
6. **The legal page layout, three copies.** `privacy-page.component.ts:100-131`, `terms-page.component.ts:60-80`, `imprint-page.component.ts`. Each declares the same `.legal-page` block. `not-found-page.component.ts:25-42` is a fourth variant of the same idea.
7. **The card, twice over.** `guides.css:243-250` re-implements `.mt-card`, and `guides.css:382-389` re-implements `.mt-card--raised`. The two files cannot share a stylesheet, which the header comment explains. A token-only duplication is acceptable. A whole class duplication drifts.
8. **The retry wait formatter, two copies.** `catalog-page.component.ts:676-687` and `reader-page.component.ts:564-573`. Both comments already admit it.

### 1.5 Dead tokens and dead classes

- `--mt-coral-pale` (`styles.css:82`) and `--mt-teal-pale` (`styles.css:92`) are used by no file. `palette.spec.ts` asserts `--mt-teal-pale` only to prove that it fails.
- `.mt-card--amber`, `.mt-chip--amber`, `.mt-chip--error` and `.mt-eyebrow--amber` are declared and never applied.
- The whole amber language of spec §3.3 is therefore unused. Amber survives only as a decorative 22px dot, the progress band, and the selection highlight. Meanwhile the quiz panel hand-rolls amber for a real state. Apply `.mt-chip--amber`, or delete the class.

---

## 2. UI AND UX FINDINGS, IN ORDER OF VALUE

### F1. Every control except the tile has no hover state. Effort: small.

**The problem.** A learner cannot tell what is pressable before they press it. On a desktop pointer, hover is the first answer the interface gives.

**The evidence.** The whole application declares `:hover` on exactly three selectors: `a:hover` (`styles.css:154`), `.topic__button:hover` (`catalog-page.component.ts:279`) and `.foot__link:hover` (`app-shell.component.ts:151`). `.mt-pill` has 72 uses and no hover. `.picker__verb`, `.trail__item` and `.crumb__button` have no hover, no active and no transition. The product's single most important control, "Explain it", therefore answers a pointer with nothing at all.

**The change.** Add to `styles.css`, after line 230:
```css
.mt-pill:hover:not(:disabled) { transform: translateY(-1px); box-shadow: 0 5px 0 var(--mt-border); }
.mt-pill--coral:hover:not(:disabled) { box-shadow: 0 5px 0 var(--mt-coral-deep); }
.mt-pill--teal:hover:not(:disabled)  { box-shadow: 0 5px 0 var(--mt-teal-deep); }
.mt-pill--ghost:hover:not(:disabled) { transform: none; box-shadow: none; background: var(--mt-sunk); }
```
Add the same pair to `.picker__verb` and `.trail__item`, with their own transition.

### F2. A ghost pill grows a shadow when a learner presses it. Effort: small.

**The problem.** The press reverses the physical rule. A ghost pill rests flat. On press it moves down 2px **and gains** a 2px shadow. The control appears to grow instead of compress.

**The evidence.** `.mt-pill--ghost { box-shadow: none }` at `styles.css:253-256` has specificity (0,1,0). `.mt-pill:active:not(:disabled)` at `styles.css:227-230` has specificity (0,3,0), so it wins the `box-shadow`. This affects 20 controls: Exam, Test me, Mark this session complete, Dismiss, Back to topics, Clear the filters, Show trail, Sign out, Delete account, Terms, Continue with Google, Close and Leave.

**The change.**
```css
.mt-pill--ghost:active:not(:disabled) { transform: translateY(1px); box-shadow: none; background: var(--mt-chip); }
```

### F3. The streamed answer announces every token to a screen reader. Effort: small.

**The problem.** A blind learner hears a flood, or hears nothing useful. `session.store.ts:385-387` appends one `delta` per token to `streamingText`. `focus-card.component.ts:88` wraps that text in `role="status" aria-live="polite"`. A polite region that mutates many times a second is not a usable announcement.

**The change.** Keep the region, and move it off the text.
```html
<p class="focus__streaming" aria-live="off">{{ streamingText() }}<!-- caret --></p>
<p class="mt-sr-only" role="status">{{ streamStatus() }}</p>
```
`streamStatus()` returns `'The explanation is on its way.'` while `isStreaming()` is true, and `'The explanation is ready.'` for one tick after it ends. Two announcements replace several hundred.

### F4. The guide CTA fails AA, on all seven public pages. Effort: small.

**The problem.** The conversion control of the public, search-indexed pages is unreadable for a learner with low vision.

**The evidence.** `guides.css:162-175` fills `.bar__cta` with `--mt-coral` (`#ff5d5d`) and sets `color: #fff`. It inherits 15px from `.bar__nav` (`guides.css:156-161`) at weight 700. White on `#ff5d5d` measures **3.01:1**. AA needs 4.5:1, because 15px bold is below the 18.66px bold threshold for large text. `guides.css:421-436` repeats the fault at 17px bold, which is also below the threshold. The application itself avoided this exact fault with `--mt-coral-press` (`styles.css:231-238`). The static pages did not follow.

**The change.** In `guides.css`, add `--mt-coral-press: #c23636;` to the `:root` block, then:
```css
.bar__cta, .start__cta { background: var(--mt-coral-press); border-color: var(--mt-coral-press); color: var(--mt-surface); }
```
White on `#c23636` measures **5.42:1**.

### F5. The header overflows sideways for a signed-in learner. Effort: small.

**The problem.** The allowance meter cannot wrap and cannot shrink. On a phone it pushes the page sideways.

**The evidence.** `allowance-meter.component.ts:66-72` sets `white-space: nowrap` on both `.allowance-meter__count` and `.allowance-meter__detail`. The second renders a string such as `Resets September 20, 2026 at 3:00 PM.`, which needs about 215px at 13px. The count needs about 120px. The bar also holds the 28px mark, the wordmark, the `Account` link and the dot, inside 40px of padding. The total passes 400px. **No test catches it**: `support.ts` stubs no `/api/account` route, so `AccountStore.view()` stays `null` in every Playwright run, and the meter renders nothing. The two guards — `layout.spec.ts:195` and `layout.spec.ts:562` — therefore measure a header the signed-in learner never sees.

**The change.** Below 768px, hide the detail and keep the count.
```css
@media (max-width: 767px) { .allowance-meter__detail { display: none; } }
```
Then add an account stub to `support.ts`, and extend `layout.spec.ts:562` to the signed-in case. **That test changes.**

### F6. A learner whose sign-in link expired reaches a dead end. Effort: small.

**The problem.** The most common sign-in failure gives no way forward.

**The evidence.** `auth-landing.component.ts:26-36`. When `message()` holds a value, the page renders the error card **alone**. The `@else if (hasNoReason())` branch that renders `<app-sign-in-panel />` never runs. A learner who reads "That link has expired or was already used." has no field, no button and no link.

**The change.** Render both. Keep the banner, and put the panel under it.
```html
<main class="auth-landing">
  @if (message(); as text) { <div class="mt-card mt-card--error banner banner--error" role="alert"><p class="banner__message">{{ text }}</p></div> }
  @if (message() !== null || hasNoReason()) { <app-sign-in-panel /> }
</main>
```

### F7. Three busy controls give no feedback except transparency. Effort: small.

**The problem.** A learner presses Subscribe, or asks for a sign-in link, and the only answer is a faded button. A slow network then reads as a dead control.

**The evidence.**
- `sign-in-panel.component.ts:87-89` — `[disabled]="submitting()"`, label unchanged.
- `wall-panel.component.ts:43-50` — `[disabled]="subscribing()"`, label unchanged.
- `account-page.component.ts:143-150` — `[disabled]="deleting()"`, label unchanged.
Each one then takes `.mt-pill:disabled { opacity: 0.55 }` from `styles.css:257-260`. Section 4, animation J supplies the busy state.

**The change.** Add `[attr.aria-busy]` and a label that names the work: `Sending…`, `Opening checkout…`, `Deleting…`.

### F8. The catalogue puts a 100-word paragraph between the question and the action. Effort: small.

**The problem.** The page asks "What do you want to understand?", and then makes the learner read nine sentences before the search field.

**The evidence.** `catalog-page.component.ts:26-35`. One `<p>` holds nine sentences and about 100 words, including a full list of twelve subject areas that the filter pills below already draw. A second interruption follows at `catalog-page.component.ts:74-76`. The design reference draws no such paragraph; it goes title, search, tiles.

**The change.** Keep two sentences above the filter row. Move the rest below the tile grid, where a crawler still reads it and a learner does not have to. `catalog-page.component.spec.ts` asserts that `.catalog__intro` is the immediate sibling of `.catalog__filter`. **That spec changes.**

### F9. The whole catalogue fades while one session starts. Effort: small.

**The problem.** A learner clicks one tile. Every tile on the page drops to 55% opacity. The feedback says "the page broke", and not "your topic opens".

**The evidence.** `catalog-page.component.ts:127` disables every tile on `tilesLocked()`. `catalog-page.component.ts:287-290` then applies `opacity: 0.55`. Computed: the summary text falls to **2.32:1** and the title to **3.44:1**. WCAG exempts an inactive control from 1.4.3, so this is not a violation. It is still the wrong signal. The clicked tile already carries a `Starting…` chip (`catalog-page.component.ts:138-142`).

**The change.** Keep the clicked tile at full strength. Dim the others by a small amount only.
```css
.topic__button:disabled { opacity: 1; cursor: not-allowed; filter: saturate(0.6); }
.topic__button:disabled:not(.topic__button--pending) { opacity: 0.8; }
```
No test reads the opacity.

### F10. A long quiz option collides with itself. Effort: small.

**The problem.** A model writes a quiz option. A long option wraps. The wrapped lines touch.

**The evidence.** `quiz-panel.component.ts:62` applies `.mt-pill` to each option. `.mt-pill` sets `line-height: 1` (`styles.css:221`) and `border-radius: 999px` (`styles.css:215`). A pill is a one-line primitive. The design reference draws a quiz option as an 18px-radius rectangle at 15px, and never as a pill.

**The change.** Stop the reuse. Give the option its own class, built from `.mt-card`.
```css
.quiz-panel__option { display: block; width: 100%; text-align: left; padding: 13px 15px;
  border: var(--mt-border-w) solid var(--mt-border); border-radius: var(--mt-r-row);
  background: var(--mt-surface); color: var(--mt-ink); font-size: 15px; font-weight: 600; line-height: 1.45; }
```

### F11. The reader's hierarchy is flat, and the topic name appears three times. Effort: medium.

**The problem.** Nothing on the reader page tells a learner which control matters. The real action — a highlight — has no control at all, only a 13px hint.

**The evidence.**
- Three ghost pills of equal weight surround the card: Exam (`reader-page.component.ts:110-117`), Test me (`focus-card.component.ts:98-105`), Mark this session complete (`reader-page.component.ts:202-209`).
- The topic name renders three times at once: as the card's `<h1>` (`focus-card.component.ts:66`), as the root crumb (`breadcrumb.component.ts:30`), and as the root trail row (`trail-rail.component.ts:59`).
- The hint that carries the product's core instruction is 13px and `--mt-muted` (`focus-card.component.ts:217-222`). It is the smallest text in the card.

**The change.**
1. Drop the `<h1>` from the focus card past step 1. The breadcrumb already states the topic. Show the `<h1>` only when `step() === 1`.
2. Raise the hint to 15px, and give it the `--mt-sunk` surface and the `--mt-r-row` radius, so it reads as an instruction and not as a footnote.
3. Group Test me and Mark this session complete into one row below the card. Leave Exam in the rail.

### F12. The quiz panel claims to be modal, and is not. Effort: medium.

**The problem.** A screen reader hides the page behind an `aria-modal="true"` dialog. A sighted learner still sees the whole reader, because the panel renders inline below the focus card.

**The evidence.** `quiz-panel.component.ts:37-46` sets `role="dialog" aria-modal="true"`. `reader-page.component.ts:213-220` places `<app-quiz-panel>` inside the normal flow of `.reader__main`. There is no backdrop, and nothing outside carries `inert`.

**The change.** Choose one. Either drop `aria-modal="true"` and keep the panel inline, or add a `::backdrop` and `inert` on the siblings. `::backdrop` is widely available (Chrome 37, Edge 79, Firefox 47, Safari 15.4 — https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/::backdrop). The inline form is the smaller change and matches the product.

### F13. The verb picker has no visible way to close. Effort: small.

**The problem.** On a phone the picker is a bottom sheet. A phone has no Escape key. The only exit is a tap outside, which a learner must guess.

**The evidence.** `verb-picker.component.ts:68-98`. The template holds a lead line and four verb buttons. There is no cancel control. `verb-picker.component.ts:76` binds `keydown.escape` only.

**The change.** Add a Cancel button after the grid, and show it below 768px only.
**This changes a test.** `layout.spec.ts:494` (`Tab and Shift+Tab cycle inside the picker and never leave it`) asserts four distinct verbs and a wrap on the fifth Tab. A fifth focusable control changes `forward[4]` and the `Shift+Tab` target `forward[3]`. Update that test in the same change.

### F14. The account page has no visible loading state. Effort: small.

**The problem.** A learner opens `/account` and sees an empty page until the request answers.

**The evidence.** `account-page.component.ts:51-53` renders a `.visually-hidden` status line and nothing else. The catalogue and the reader both draw a skeleton. The account page draws nothing.

**The change.** Draw one `.mt-card--raised` with four `.mt-skeleton` rows, the same shape the loaded card takes.

### F15. Two account controls are inert, and issues #88, #90 and #91 fix the row. Effort: medium.

**The evidence, read from the issues.**
- #90 makes `Manage subscription` (`account-page.component.ts:96-104`) open the Freemius customer portal, and show only for `ACTIVE`, `PAST_DUE` and `CANCELLED`.
- #91 makes the meter's `Subscribe` (`allowance-meter.component.ts:44-49`) start the checkout.
- #88 removes `Sign out everywhere` (`account-page.component.ts:114-121`).

**The design finding that the three issues leave open.** After #88 and #90, the action row holds four controls of one weight: `Manage subscription`, `Terms`, `Sign out`, `Delete account`. Three are `.mt-pill--ghost`, and `Terms` is a link dressed as a button. A destructive control sits beside a navigation link at the same weight.

**The change.**
1. Split the row. Put `Manage subscription` and `Sign out` in a primary row. Put `Delete account` in a separate block below a `--mt-rule` divider, under the heading `Close your account`.
2. Make `Terms` a plain link, and not a pill.
3. `Status` renders the raw enum today (`account-page.component.ts:69` prints `account.status`, so a learner reads `PAST_DUE`). Map each status to a sentence.

### F16. Four anchors dressed as pills carry an underline. Effort: small.

**The evidence.** `.mt-pill` on an `<a>` does not clear the user-agent underline. `reader-page.component.ts:291-293` clears it for `.banner__back`. Three others do not: `account-page.component.ts:105` (Terms), `sign-in-panel.component.ts:102` (Continue with Google), `not-found-page.component.ts:17` (Back to topics).

**The change.** Add `a.mt-pill { text-decoration: none; }` to `styles.css`, and delete the local rule at `reader-page.component.ts:292`.

### F17. Behaviour at phone width: the reading starts below three controls. Effort: medium.

**The evidence.** Below 768px `.reader__grid` collapses to one column (`reader-page.component.ts:245-251`) and the children keep source order. A learner at 390px therefore meets, in order: the 64px bar, an `Exam` pill pushed to the right edge (`reader-page.component.ts:260-262`), a left-aligned `Show trail (1)` pill, a breadcrumb chip row, and only then the card.

**The change.** Below 768px, put `.reader__rail` after `.reader__main` with `order: 2`, and keep `Exam` inside the rail block. The trail is a review tool, and the text is the product. **This touches `layout.spec.ts:326`** (`the reader does not move down when the loaded session replaces the skeleton`), because the skeleton rail must move with it.

### F18. The search field has no clear control. Effort: small.

**The evidence.** `catalog-page.component.ts:40-47` uses `type="search"`, which gives a native clear button in Chrome and Safari but not in Firefox. The empty state offers `Clear the filters` (`catalog-page.component.ts:153-155`), but only when the result is empty. A learner with one match has no reset.

**The change.** Show `Clear the filters` whenever `query()` or `category()` is set, next to the pill row.

---

## 3. ACCESSIBILITY OF THE VISUAL DESIGN

Every ratio below is computed with the WCAG 2.2 formula, and not estimated. The method matches `palette.spec.ts:54-67`.

### 3.1 Text on background — all pairs the product renders

| Pair | Ratio | AA verdict |
|---|---|---|
| `--mt-prose` on `--mt-surface` | 11.88 | Pass |
| `--mt-prose` on `--mt-page` | 11.13 | Pass |
| `--mt-prose` on `--mt-sunk` | 11.32 | Pass |
| `--mt-ink` on `--mt-surface` | 14.01 | Pass |
| `--mt-ink` on `--mt-page` | 13.13 | Pass |
| `--mt-ink` on `--mt-chip` | 12.16 | Pass |
| `--mt-muted` on `--mt-surface` | 5.84 | Pass |
| `--mt-muted` on `--mt-page` | 5.47 | Pass |
| `--mt-muted` on `--mt-sunk` | 5.56 | Pass |
| `--mt-muted` on `--mt-chip` | 5.07 | Pass |
| `--mt-coral-text` on `--mt-surface` | 4.93 | Pass |
| `--mt-coral-text` on `--mt-page` | 4.62 | Pass, with 0.12 of margin |
| `--mt-coral-text` on `--mt-sunk` | 4.70 | Pass |
| `--mt-teal` on `--mt-surface` | 5.47 | Pass |
| `--mt-teal` on `--mt-page` | 5.13 | Pass |
| `--mt-teal` on `--mt-chip` | 4.75 | Pass |
| white on `--mt-coral-press` | 5.42 | Pass |
| white on `--mt-teal` | 5.47 | Pass |
| `--mt-chip` on `--mt-teal` (current trail eyebrow) | 4.75 | Pass |
| `--mt-amber-ink` on `--mt-amber-bg` | 7.45 | Pass |
| `--mt-amber-ink-2` on `--mt-amber-bg` | 4.71 | Pass |
| `--mt-amber-ink` on `--mt-amber` (selection) | 5.48 | Pass |
| `--mt-err-ink` on `--mt-err-bg` | 5.39 | Pass |
| `--mt-err-ink-2` on `--mt-err-bg` | 6.01 | Pass |
| `--mt-coral` on `--mt-surface`, 24px/600 wordmark | 3.01 | Pass as large text only |
| **white on `--mt-coral`, `guides.css` `.bar__cta`, 15px/700** | **3.01** | **FAIL** |
| **white on `--mt-coral`, `guides.css` `.start__cta`, 17px/700** | **3.01** | **FAIL** |
| `--mt-faint` on `--mt-page` | 2.58 | Fails, and the code never uses it as text |
| `--mt-faint` on `--mt-surface` | 2.75 | Fails, and the code never uses it as text |

The in-application palette is sound. The three corrections of spec §3.4 hold. The two failures are both in `guides.css`, and both are finding F4.

One note on the wordmark. `--mt-coral` at 24px/600 measures 3.01:1, which clears the 3:1 large-text threshold by 0.01. `layout.spec.ts:288` pins the size and the weight for exactly that reason. Keep that test.

### 3.2 Non-text contrast — WCAG 2.2 SC 1.4.11, Level AA, needs 3:1

This is the weakest part of the visual design. The border that draws a control measures far below the threshold.

| Boundary or state | Ratio | Verdict |
|---|---|---|
| `--mt-border` on `--mt-surface` (every pill, field, card edge) | **1.28** | **Fail** |
| `--mt-border` on `--mt-page` | **1.20** | **Fail** |
| Quiz chosen surface against unchosen surface | **1.06** | **Fail** |
| Quiz chosen border against unchosen border | **1.13** | **Fail** |
| Progress band `--mt-amber` on the `--mt-rule` track | **1.20** | **Fail** |
| Status dot, degraded: `--mt-amber` on `--mt-surface` | **1.44** | **Fail** |
| Status dot, checking: `--mt-faint` on `--mt-surface` | **2.75** | **Fail** |
| Error card border `--mt-err-border` on `--mt-surface` | **1.51** | **Fail** |
| Skeleton bar on `--mt-surface` | 1.20 | Exempt; a skeleton is not a control |
| Status dot, ok: `--mt-teal` on `--mt-surface` | 5.47 | Pass |
| Status dot, unreachable: `--mt-err-ink` on `--mt-surface` | 5.93 | Pass |
| `.mt-pill--coral` fill on `--mt-surface` | 5.42 | Pass |
| `.mt-pill--teal` fill on `--mt-surface` | 5.47 | Pass |
| Focus ring `--mt-teal` on `--mt-page` | 5.13 | Pass |
| Focus ring `--mt-teal` on `--mt-surface` | 5.47 | Pass |

Three consequences follow.

1. **A ghost pill on a white card is close to invisible.** `.mt-pill` fills with `--mt-surface` and draws a `--mt-border` edge. "Test me" sits inside the white focus card. Its only boundary measures 1.28:1. The learner sees a label, and not a button. This is the root cause of finding F11's flat hierarchy.
2. **The quiz's chosen state is a colour change of 1.06:1.** `aria-pressed` serves a screen reader. A sighted learner with low vision cannot see which answer they chose. This is both a 1.4.11 failure and a colour-only signal.
3. **The amber status dot is invisible.** A degraded backend is the state that most needs to be seen.

**The change.** Add one darker boundary token, and use it on a control only.
```css
:root { --mt-edge: #9dc9bd; }   /* 2.03:1 on white — still not 3:1 */
```
A value that reaches 3:1 on `--mt-surface` is near `#8ab6aa`. Test the exact value, and assert it in `palette.spec.ts`. Apply it to `.mt-pill`, `.picker__verb`, `.catalog__search`, `.sign-in-panel__input` and `.quiz-panel__option`. Leave `--mt-border` on a decorative card edge, where 1.4.11 does not apply.

For the quiz state, add a second, non-colour signal: a 3px `--mt-teal` left edge plus a check glyph, and keep the amber.
For the status dot, add a shape: a ring for `checking`, a solid disc for `ok`, a triangle for `degraded`, a cross for `unreachable`.

### 3.3 The focus indicator

The focus indicator is the strongest part of the accessibility work here, and one test already guards it.

- `styles.css:161-164` sets `outline: 3px solid var(--mt-teal); outline-offset: 2px` on every `:focus-visible`.
- `layout.spec.ts:475-492` asserts the colour, the width and the offset on a real control.
- One rule removes a ring: `.focus__body:focus-visible { outline: none }` (`focus-card.component.ts:179-181`). The reason is documented, the element is not a control, and `layout.spec.ts:445-473` asserts the exact behaviour. This is correct, and it is the right way to take a ring away.

**One defect.** The ring is invisible on a teal or a coral fill.
- `--mt-teal` ring on a `.mt-pill--teal` fill measures **1.00:1**.
- `--mt-teal` ring on a `.mt-pill--coral` fill measures **1.01:1**.
The 2px offset places the ring on the surface behind the control, so a ring on a card still reads. A teal pill that sits on a teal or a dark surface loses it. WCAG 2.2 SC 2.4.11 (Focus Not Obscured) and SC 1.4.11 both apply.

**The change.** Use a two-tone ring, which works on every surface.
```css
:focus-visible { outline: 3px solid var(--mt-teal); outline-offset: 2px; box-shadow: 0 0 0 2px var(--mt-surface); }
```
Confirm that `layout.spec.ts:485-491` still passes. It reads `outlineColor`, `outlineWidth` and `outlineOffset`, and not `boxShadow`, so it should pass unchanged.

### 3.4 Target size — WCAG 2.2 SC 2.5.8, Level AA, needs 24×24 CSS px

Each height below is computed from the declared CSS. A measurement in a real browser is the acceptance criterion.

| Control | Computed height | Verdict |
|---|---|---|
| `.mt-pill` (10+10 padding, 14px at line-height 1, 2+2 border) | 38px | Pass |
| `.mt-chip` / `.crumb__button` (7+7, 13px, 2+2) | 31px | Pass |
| `.catalog__search` (14+14, 15px, 2+2) | about 47px | Pass |
| `.sign-in-panel__input` (12+12, inherited, 2+2) | about 46px | Pass |
| `.trail__item` | about 55px | Pass |
| `.picker__verb` | about 60px | Pass |
| `.bar__link` / `.bar__account` (no line-height, 3px padding, 3px border) | **about 24px** | At the limit |
| `.foot__link` (13px, no padding) | **about 16px** | Below the limit |
| `.dot` | 14px | Exempt; `role="img"`, not a target |

Two points.

1. `.bar__link` (`app-shell.component.ts:114-121`) lands on the 24px minimum with no margin. A font-metric change takes it below. Add `padding: 6px 2px 3px` and keep the bar at 64px. `layout.spec.ts:562` asserts `bar.height === 64`; the bar sets `height: 64px`, so it will not grow.
2. `.foot__link` (`app-shell.component.ts:145-150`) is about 16px tall. It passes SC 2.5.8 through the **spacing exception** only, because the 20px gap plus the label widths keeps each 24px circle clear of the next. Add `padding: 5px 4px` and the exception is no longer needed.

### 3.5 Colour as the only signal

| Place | Second signal today | Verdict |
|---|---|---|
| Status dot | `aria-label` and `title` name the result in words | Sufficient for a screen reader; **not sufficient for a sighted learner with low vision** — see 3.2 |
| Coral primary against an error | Fill against text, and the surface | Correct, and `palette.spec.ts:131-144` proves it |
| Current trail row | `aria-current="true"` plus the eyebrow text `· here` | Correct |
| Current breadcrumb | `aria-current="page"` plus `disabled` | Correct |
| Selected category pill | `aria-pressed` plus a teal fill (5.47:1) | Correct |
| **Chosen quiz option** | `aria-pressed` only; the visual change is 1.06:1 | **Insufficient** |
| Disabled tile | `title` states the reason; `disabled` | Correct |

The colour rule of §3.3 is well kept. The one real failure is the quiz option.

---

## 4. MOTION

### 4.1 The principles for this product

Motion here serves a learner who reads a hard passage and waits for a machine to write an answer. Five rules follow.

1. **Motion explains where a thing came from.** A new node joins the trail because the learner chose a verb. The animation must connect the two.
2. **Motion never delays the reading.** The first token of an answer appears at the instant it arrives. No entrance animation holds prose back.
3. **Motion never moves the text under a pointer.** Nothing animates `.focus__body` while a learner drags a selection across it.
4. **Motion is short and it is anchored.** Candy's 4px lift sets the scale. A travel of more than 8px is wrong for this system.
5. **Motion answers an action.** A press, an open, an arrival. The page does not animate on its own, except for the one indeterminate band that says "the machine is at work".

What motion must never delay: the stream, the focus ring, the press feedback, and the removal of an error.

### 4.2 The motion token set

Add to `styles.css`, after the type tokens at line 121.

```css
  /* motion — the durations step with the 4px lift, and nothing travels further than 8px */
  --mt-dur-press: 90ms;   /* a press, a hover */
  --mt-dur-state: 160ms;  /* a chip, a row, a swap of state */
  --mt-dur-panel: 240ms;  /* a sheet, a panel, a card */
  --mt-dur-route: 320ms;  /* a change of route */

  --mt-ease-out: cubic-bezier(0.22, 0.85, 0.3, 1);    /* a thing arrives */
  --mt-ease-in: cubic-bezier(0.55, 0, 0.9, 0.3);      /* a thing leaves */
  --mt-ease-press: cubic-bezier(0.2, 0, 0, 1);        /* a control answers a finger */
  --mt-ease-settle: cubic-bezier(0.34, 1.4, 0.5, 1);  /* a small overshoot, for an arrival */

  --mt-move-press: 2px;
  --mt-move-near: 4px;
  --mt-move-far: 8px;
```

Then **replace** the blanket rule at `styles.css:324-332` with a token override.

```css
/* A learner who asks for less motion still needs every change of state. The distance goes to zero
   and the duration goes to 1ms. The state stays, and so does the opacity cross-fade.
   1ms and not 0: `animate.leave` waits for `animationend` before it removes an element, and an
   animation of zero duration can leave that element in the DOM. */
@media (prefers-reduced-motion: reduce) {
  :root {
    --mt-dur-press: 1ms; --mt-dur-state: 1ms; --mt-dur-panel: 1ms; --mt-dur-route: 1ms;
    --mt-move-press: 0px; --mt-move-near: 0px; --mt-move-far: 0px;
  }
  .mt-skeleton { animation: none; }
  .focus__caret { animation: none; opacity: 1; }
  .focus__band  { animation: none; transform: none; }
}
```

Why this replaces the blanket rule. MDN states the preference asks a browser for an interface that "removes, reduces, **or replaces** motion-based animations", and its own example replaces a scale animation with an opacity animation rather than deleting it (https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@media/prefers-reduced-motion). MDN names the risk plainly: "Animations such as scaling or panning large objects can be vestibular motion triggers." An opacity cross-fade is not such a trigger, and it still tells a learner that a thing changed. The current `*{ animation-duration: 0.01ms !important }` deletes every animation, including one that carries meaning. MDN also states the reduce rule needs the same specificity and a later source order to win; the token override meets both, because it changes `:root`.

Support for the media feature: Chrome 74, Edge 79, Firefox 63, Safari 10.1; Chrome Android 74, Firefox Android 64, Safari iOS 10.3. It is Baseline widely available since 2022-07-15. A browser that lacks it never matches the query, so every base rule must be safe on its own.

### 4.3 The animations

Each one names its trigger, its properties, its duration, its easing, whether it needs JavaScript, its reduced-motion form, and its risk.

---

#### A. The answer lands in the focus card — the most important moment in the product

**What happens today.** `session.store.ts:598-604` sets the session, the node and then clears `streamingText`, all in one tick. The sunk stream box disappears and the white body text changes, in one frame. The card height changes twice. Nothing explains the swap.

**Trigger.** `refresh()` completes.
**Properties.** `opacity`, `transform`. Never `height`.
**Duration.** `--mt-dur-state` out, `--mt-dur-panel` in.
**Easing.** `--mt-ease-in` out, `--mt-ease-settle` in.
**JavaScript.** One Angular attribute each. No package, and no TypeScript.

`animate.enter` and `animate.leave` are compiler features of Angular. The documentation states they "are not directives. They are special API supported directly by the Angular compiler." Angular removes the class when the animation completes, and it removes the element itself after `animate.leave`.

```html
<!-- focus-card.component.ts:87-94 -->
@if (isStreaming() || streamingText().length > 0) {
  <p class="focus__streaming" animate.leave="focus__streaming--out" aria-live="off">…</p>
}
```

```css
.focus__streaming--out { animation: focus-hand-off var(--mt-dur-state) var(--mt-ease-in) both; }
@keyframes focus-hand-off {
  to { opacity: 0; transform: translateY(calc(-1 * var(--mt-move-near))); }
}
/* The body settles into place as the stream box hands it over. */
.focus__body--landed { animation: focus-land var(--mt-dur-panel) var(--mt-ease-settle) both; }
@keyframes focus-land {
  from { opacity: 0.25; transform: translateY(var(--mt-move-near)); }
  to   { opacity: 1;    transform: none; }
}
```

The card carries `.focus__body--landed` for one render. Drive it from the existing `afterRenderEffect` at `focus-card.component.ts:312-327`, which already detects a change of `body()`:

```ts
protected readonly landed = signal(false);
// inside the existing read: () => { … } after this.checkedBody = body;
this.landed.set(true);
setTimeout(() => this.landed.set(false), 400);
```
Bind it with `[class.focus__body--landed]="landed()"`.

**Reduced-motion form.** The tokens take the distance to 0 and the duration to 1ms. The opacity still steps from 0.25 to 1, which is a change of state and not a vestibular trigger.

**Risk to the layout tests.** `layout.spec.ts:326-355` measures `.reader__main` before and after the swap, and allows a 1px move. The animation uses `transform` only, and `transform` does not change the layout box that `boundingBox()` reports for `.reader__main`. The test is safe. Do **not** animate the card's `height`: `interpolate-size: allow-keywords` and `calc-size()` are both Limited availability, Chrome 129 and Edge 129 only, with no Firefox and no Safari version (https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/interpolate-size).

**Risk to the streamed text.** None. The animation runs on the element that leaves, after the stream has ended.

---

#### B. The tokens arrive — do not animate them

**The finding.** A per-word or per-token animation is wrong here, and it is the obvious mistake to avoid.

`session.store.ts:385-387` appends one `{t: string}` per token. A per-word animation needs a wrapper element for each word. Three costs follow.
1. A wrapper inside the streamed paragraph breaks text selection across a word boundary.
2. The wrappers rebuild on every token, which is hundreds of layouts per answer.
3. It delays the reading, which breaks principle 2.

**What to do instead.** Animate the box once, when the stream starts, and let the caret carry the rest.

```css
.focus__streaming { animation: focus-open var(--mt-dur-state) var(--mt-ease-out) both; }
@keyframes focus-open {
  from { opacity: 0; transform: translateY(var(--mt-move-near)) scaleY(0.98); transform-origin: top; }
  to   { opacity: 1; transform: none; }
}
```
**JavaScript.** None. The element enters the DOM once, so the CSS animation runs once.
**Reduced-motion form.** Handled by the tokens.
**Risk to the streamed text.** The box is `user-select: none` today (`focus-card.component.ts:201`), by a documented rule: a selection across streamed prose indexes a string the server has never stored, and every explain then returns `SPAN_MISMATCH`. **The brief asks that the streamed text stay selectable. It is deliberately not selectable today, and this review does not propose to change that.** The invariant at `focus-card.component.ts:23-46` is correct, and the loss is real but smaller than the defect it prevents. A `transform` on the container does not change selection behaviour either way.

---

#### C. The caret

**What happens today.** `focus-card.component.ts:203-216`: `focus-blink 1s step-end infinite` on `opacity`. This is correct and it is the design's own animation. Keep it, and move it onto the tokens.

```css
.focus__caret { color: var(--mt-coral-text); animation: focus-blink 1s step-end infinite; }
```
**Reduced-motion form.** `animation: none; opacity: 1;` — supplied in 4.2. The caret then holds steady, which spec §7.5 already requires and which the blanket rule reaches only by accident.

---

#### D. The verb picker opens and closes

**What happens today.** `verb-picker.component.ts:175-195` animates `picker-rise 200ms ease-out` on `transform: translateY(100%)`, **below 768px only**. At desktop width the popover appears with no animation at all. There is no close animation at either width.

**Trigger.** A span exists and an anchor exists (`focus-card.component.ts:288-290`).
**Properties.** `opacity`, `transform`.
**Duration.** `--mt-dur-panel` in, `--mt-dur-state` out.
**Easing.** `--mt-ease-out` in, `--mt-ease-in` out.
**JavaScript.** One `animate.leave` attribute on the host in `focus-card.component.ts:107-114`.

```css
/* Desktop: the popover grows from the phrase it explains. The origin is the anchor. */
.picker {
  transform-origin: top left;
  animation: picker-open var(--mt-dur-panel) var(--mt-ease-out) both;
}
@keyframes picker-open {
  from { opacity: 0; transform: translateY(calc(-1 * var(--mt-move-near))) scale(0.96); }
  to   { opacity: 1; transform: none; }
}
.picker--out { animation: picker-close var(--mt-dur-state) var(--mt-ease-in) both; }
@keyframes picker-close {
  to { opacity: 0; transform: translateY(calc(-1 * var(--mt-move-press))) scale(0.98); }
}

@media (max-width: 767px) {
  /* Phone: the sheet rises from the edge it is attached to. */
  .picker { transform-origin: bottom center; animation: picker-rise var(--mt-dur-panel) var(--mt-ease-out) both; }
  @keyframes picker-rise { from { transform: translateY(100%); } to { transform: none; } }
  .picker--out { animation: picker-fall var(--mt-dur-state) var(--mt-ease-in) both; }
  @keyframes picker-fall { to { transform: translateY(100%); } }
}
```

**Reduced-motion form.** The `--mt-move-*` tokens do not reach the `100%` translate of the sheet. Add an explicit form:
```css
@media (prefers-reduced-motion: reduce) {
  .picker { animation: picker-fade 1ms linear both; }
  .picker--out { animation: picker-fade 1ms linear reverse both; }
  @keyframes picker-fade { from { opacity: 0; } to { opacity: 1; } }
}
```
**Risk to the layout tests — this one is real.** `layout.spec.ts:374-413` calls `picker(page).waitFor()` and then `boundingBox()`. A `transform` animation moves the reported box while it runs. A 240ms animation makes that test flaky. **Mitigate it in the test, and not in the product**: add `await expect(picker(page)).toHaveCSS('opacity', '1')` before each `boundingBox()` call, which waits for the animation to settle. Name `layout.spec.ts:374` and `layout.spec.ts:357` in the change. `layout.spec.ts:415-443` reads `position` and `bottom` only, and a transform does not change either, so it is safe.

---

#### E. A new node joins the trail rail and the breadcrumb

**Trigger.** `store.tree()` gains a node, and `store.breadcrumb()` gains a crumb.
**Properties.** `opacity`, `transform`.
**Duration.** `--mt-dur-state`.
**Easing.** `--mt-ease-settle` — a small overshoot, so the row reads as a thing that lands.
**JavaScript.** One `animate.enter` attribute. Angular creates a new element for a new `track` key, so the animation runs once per node.

```html
<!-- trail-rail.component.ts:44-63 -->
<li class="trail__row" animate.enter="trail__row--in" [style.margin-left.px]="node.depth * 16">
<!-- breadcrumb.component.ts:19-36 -->
<li class="crumb" animate.enter="crumb--in">
```

```css
.trail__row--in { animation: trail-join var(--mt-dur-state) var(--mt-ease-settle) both; }
@keyframes trail-join {
  from { opacity: 0; transform: translateX(calc(-1 * var(--mt-move-far))); }
  to   { opacity: 1; transform: none; }
}
.crumb--in { animation: crumb-join var(--mt-dur-state) var(--mt-ease-settle) both; }
@keyframes crumb-join {
  from { opacity: 0; transform: translateY(calc(-1 * var(--mt-move-near))) scale(0.9); }
  to   { opacity: 1; transform: none; }
}
```
The row enters from the left, which is the direction the indentation grows. The motion then states the parentage.

**Reduced-motion form.** The tokens take both distances to 0. The opacity still runs, so the arrival is still visible.
**Risk.** None to `layout.spec.ts`. No test measures a trail row's position. `layout.spec.ts:305-324` reads `display` on `.trail__toggle` and `.trail__head` only.

---

#### F. The focus moves between nodes

**Trigger.** `store.goTo(nodeId)` runs (`session.store.ts:307-312`).
**Properties.** `background-color`, `box-shadow`, `color`, and `transform` on the dot.
**Duration.** `--mt-dur-state`.
**Easing.** `--mt-ease-out`.
**JavaScript.** None. A class toggle drives a `transition`.

`background-color` and `box-shadow` are not compositor-only properties. Name that plainly: this is the one animation that cannot use `transform` and `opacity` alone, because the row genuinely changes its fill from white to teal. The cost is small — one row, one paint, 160ms.

```css
.trail__item {
  transition:
    background-color var(--mt-dur-state) var(--mt-ease-out),
    border-color var(--mt-dur-state) var(--mt-ease-out),
    color var(--mt-dur-state) var(--mt-ease-out),
    box-shadow var(--mt-dur-state) var(--mt-ease-out);
}
.trail__dot { transition: transform var(--mt-dur-state) var(--mt-ease-settle); }
.trail__item--current .trail__dot { transform: scale(1.12); }
```
**Reduced-motion form.** The duration token drops to 1ms. The colour still changes, so the current row is still identified.
**Risk.** None. No test reads a colour on a trail row.

---

#### G. The catalogue tiles on load and on filter

**Trigger, load.** `topicsLoading()` goes false.
**Trigger, filter.** `filteredTopics()` changes.
**Properties.** `opacity`, `transform`.
**Duration.** `--mt-dur-state`.
**Easing.** `--mt-ease-out`.
**JavaScript.** One `animate.enter` attribute.

```html
<!-- catalog-page.component.ts:121-123 -->
@for (t of filteredTopics(); track t.slug) {
  <li class="topic" animate.enter="topic--in" [style.--i]="$index">
```
```css
.topic--in { animation: tile-in var(--mt-dur-state) var(--mt-ease-out) both;
             animation-delay: calc(min(var(--i), 5) * 24ms); }
@keyframes tile-in {
  from { opacity: 0; transform: translateY(var(--mt-move-far)); }
  to   { opacity: 1; transform: none; }
}
```
The stagger caps at six tiles, so a twelve-topic filter does not become a slow wave.

**Reduced-motion form.** Add an explicit rule, because a delay is not a token:
```css
@media (prefers-reduced-motion: reduce) { .topic--in { animation-delay: 0ms; } }
```
**Risk to the layout tests — name two.**
- `layout.spec.ts:220-246` reads `boundingBox()` on `.topic__button` and asserts `x` and `x + width`. A `translateY` does not change either. Safe.
- **Do not animate `.catalog__cat`.** `layout.spec.ts:171-193` asserts `new Set(tops).size === 1` on every pill's `getBoundingClientRect().top`, and `layout.spec.ts:266-286` asserts the opposite at 1360px. Any vertical transform on a pill makes both flaky. The filter row stays still.

---

#### H. The reveal of a quiz answer

**What happens today.** `quiz-panel.component.ts:81-99`. The whole panel swaps from the question phase to the result phase in one frame. The score, every stem, every verdict and every rationale appear together.

**Trigger.** `phase()` becomes `'result'`.
**Properties.** `opacity`, `transform`.
**Duration.** `--mt-dur-panel` for the score, `--mt-dur-state` per review row.
**Easing.** `--mt-ease-settle` for the score, `--mt-ease-out` for a row.
**JavaScript.** One `animate.enter` per element.

```html
<h2 class="quiz-panel__score" animate.enter="score--in">{{ result()?.score }} / {{ result()?.total }}</h2>
<ul class="quiz-panel__review">
  @for (question of questions(); track question.questionId; let i = $index) {
    <li animate.enter="review--in" [style.--i]="i">
```
```css
.score--in { animation: score-in var(--mt-dur-panel) var(--mt-ease-settle) both; }
@keyframes score-in { from { opacity: 0; transform: scale(0.8); } to { opacity: 1; transform: none; } }
.review--in { animation: review-in var(--mt-dur-state) var(--mt-ease-out) both;
              animation-delay: calc(120ms + var(--i) * 70ms); }
@keyframes review-in {
  from { opacity: 0; transform: translateY(var(--mt-move-near)); }
  to   { opacity: 1; transform: none; }
}
```
The score lands first. Each verdict follows in the order the questions were asked. The learner reads the result in the order they answered.

**Reduced-motion form.** `@media (prefers-reduced-motion: reduce) { .review--in { animation-delay: 0ms; } }` plus the tokens.
**Risk.** `quiz.spec.ts` asserts on text. A delayed row could time a `toBeVisible()` out. The maximum delay for three questions is 120 + 140 = 260ms, which is well inside Playwright's default timeout. Re-run `quiz.spec.ts` and confirm.

---

#### I. The allowance meter when the count changes

**Trigger.** `AccountStore.view().remaining` changes. `session.store.ts:474-476` calls `account.load()` after every generation, so this fires on each spent explanation.
**Properties.** `transform`, `color`.
**Duration.** `--mt-dur-state`.
**Easing.** `--mt-ease-settle`.
**JavaScript.** A small amount. The component must know that the number changed, and the DOM node is not replaced. Use a `computed` that tracks the previous value.

```ts
private previous: number | null = null;
protected readonly ticked = signal(false);
constructor() {
  effect(() => {
    const n = this.view()?.remaining ?? null;
    if (n !== null && this.previous !== null && n !== this.previous) {
      this.ticked.set(true);
      setTimeout(() => this.ticked.set(false), 400);
    }
    this.previous = n;
  });
}
```
```html
<span class="allowance-meter__count" [class.allowance-meter__count--tick]="ticked()">
```
```css
.allowance-meter__count--tick { animation: meter-tick var(--mt-dur-state) var(--mt-ease-settle) both; }
@keyframes meter-tick {
  0%   { transform: none; }
  40%  { transform: translateY(calc(-1 * var(--mt-move-press))) scale(1.06); }
  100% { transform: none; }
}
```
The meter must never draw attention away from the answer that just arrived. 160ms and 2px is the whole budget.

**Reduced-motion form.** The tokens take the distance to 0. The scale step remains at 1.06, which is a 6% change on a 13px label — well under a vestibular threshold. If the reviewer prefers, remove it too: `@media (prefers-reduced-motion: reduce) { .allowance-meter__count--tick { animation: none; } }`.
**Risk.** None to `layout.spec.ts`, which never renders a meter. See finding F5.

---

#### J. A button that is busy

**Trigger.** `submitting()`, `subscribing()` or `deleting()` becomes true. See finding F7.
**Properties.** `opacity`, `transform` on a pseudo-element.
**Duration.** A 900ms loop.
**Easing.** `linear`.
**JavaScript.** None beyond the existing signal.

```css
.mt-pill[aria-busy='true'] { position: relative; cursor: progress; opacity: 1; }
.mt-pill[aria-busy='true']::after {
  content: ''; position: absolute; inset: auto 0 -2px 0; height: 2px; border-radius: 2px;
  background: currentColor; opacity: 0.6; transform-origin: left;
  animation: pill-busy 900ms linear infinite;
}
@keyframes pill-busy {
  0%   { transform: scaleX(0); }
  50%  { transform: scaleX(1); transform-origin: left; }
  50.1%{ transform: scaleX(1); transform-origin: right; }
  100% { transform: scaleX(0); transform-origin: right; }
}
```
The bar sweeps along the bottom edge, which is where Candy's lift already lives. The control keeps its full colour, so the label stays readable.

**Reduced-motion form.**
```css
@media (prefers-reduced-motion: reduce) {
  .mt-pill[aria-busy='true']::after { animation: none; transform: scaleX(1); opacity: 0.35; }
}
```
A static bar still says "this control is at work".
**Risk.** None. No test reads a pseudo-element.

---

#### K. The skeleton for a load

**What happens today.** `styles.css:306-320` animates `background` between `--mt-skeleton` and `--mt-skeleton-2` over 1.4s. `background` is not a compositor property, so each frame repaints. With six catalogue tiles of four bars each, that is 24 elements repainting continuously.

**The change.** Move the pulse onto `opacity` with a pseudo-element, which the compositor can handle.
```css
.mt-skeleton { position: relative; border-radius: 6px; background: var(--mt-skeleton); overflow: hidden; }
.mt-skeleton::after {
  content: ''; position: absolute; inset: 0; background: var(--mt-skeleton-2);
  animation: mt-pulse 1.4s var(--mt-ease-out) infinite;
}
@keyframes mt-pulse { 0%, 100% { opacity: 0; } 50% { opacity: 1; } }
```
**Reduced-motion form.** `.mt-skeleton::after { animation: none; opacity: 0; }` — supplied in 4.2 as `.mt-skeleton { animation: none }`; extend it to the pseudo-element. The bars stay, which spec §6.4 requires.
**Risk.** None. No test reads a skeleton's style. `layout.spec.ts:343` only waits for `.focus-skeleton` to exist.

---

#### L. The indeterminate progress band

**What happens today.** `focus-card.component.ts:144-159` animates `transform: translateX` from `-100%` to `250%` over 1.6s. This is already correct, and it is the one property choice in the codebase that is compositor-friendly.

**The one defect.** Spec §7.3 promises that `prefers-reduced-motion` "replaces the travel with a static half-width band". The blanket rule at `styles.css:324-332` reaches that outcome only by accident: the animation ends in 0.01ms, `animation-fill-mode` is `none`, and the element falls back to its base style, which is a 40% band at the left edge. That is close to the promise, and it is not written down anywhere. Make it explicit — the 4.2 block does this with `.focus__band { animation: none; transform: none; }`.

**Also fix the contrast.** The band is `--mt-amber` on a `--mt-rule` track, which measures **1.20:1** and fails SC 1.4.11 (see 3.2). Change the track to `--mt-chip` and the band to `--mt-amber-deep`, then re-measure and assert the pair in `palette.spec.ts`.

---

#### M. The wall panel

**Trigger.** `subscribeRequired()` returns a code, and `<app-wall-panel>` replaces the focus card (`reader-page.component.ts:175-176`).
**Properties.** `opacity`, `transform`.
**Duration.** `--mt-dur-panel`.
**Easing.** `--mt-ease-out`.
**JavaScript.** One `animate.enter` attribute.

The wall stops a learner mid-task. The motion must be calm, and it must not feel like a punishment. A gentle settle from below, and no bounce.

```html
<app-wall-panel [code]="code" animate.enter="wall--in" />
```
```css
.wall--in { animation: wall-in var(--mt-dur-panel) var(--mt-ease-out) both; }
@keyframes wall-in {
  from { opacity: 0; transform: translateY(var(--mt-move-far)); }
  to   { opacity: 1; transform: none; }
}
```
Apply the same class to `<app-sign-in-panel />` at `reader-page.component.ts:174`. The two panels appear in the same slot, for the same reason.

**Reduced-motion form.** The tokens take the distance to 0.
**Risk.** None. No layout test renders a wall.

---

#### N. The change between routes

**Trigger.** Any router navigation.
**Properties.** `opacity`, `transform`, through the View Transitions API.
**Duration.** `--mt-dur-route`.
**Easing.** `--mt-ease-out`.
**JavaScript.** One line in `app.config.ts`.

```ts
// app.config.ts:16
provideRouter(routes, withViewTransitions({ skipInitialTransition: true })),
```

```css
@media (prefers-reduced-motion: no-preference) {
  ::view-transition-old(root) { animation: vt-out var(--mt-dur-route) var(--mt-ease-in) both; }
  ::view-transition-new(root) { animation: vt-in  var(--mt-dur-route) var(--mt-ease-out) both; }
  @keyframes vt-out { to   { opacity: 0; transform: translateY(calc(-1 * var(--mt-move-near))); } }
  @keyframes vt-in  { from { opacity: 0; transform: translateY(var(--mt-move-near)); } }
}
```

**The facts, and the fall-back.**
- `document.startViewTransition` is Baseline newly available since 2025-10-14. Support: Chrome 111, Edge 111, Firefox 144, Safari 18 (https://developer.mozilla.org/en-US/docs/Web/API/Document/startViewTransition).
- The `::view-transition-*` pseudo-elements carry the same baseline. Support: Chrome 109, Edge 109, Firefox 144, Safari 18 (https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/::view-transition).
- **The fall-back needs no code.** Angular's own API documentation states: "The View Transitions API is not available in all browsers. If the browser does not support view transitions, the Router will not attempt to start a view transition and continue processing the navigation as usual." (https://angular.dev/api/router/withViewTransitions). A browser that lacks the API drops the `::view-transition-*` rules as an unknown selector, and the route changes with no animation.
- `skipInitialTransition` is documented as "Skips the very first call to `startViewTransition`" (https://angular.dev/api/router/ViewTransitionsFeatureOptions). Pass it, so the first paint of the application is not an animation.
- **Do not use `@view-transition { navigation: auto }`.** It is Limited availability, Chrome 126, Edge 126, Safari 18.2, and **Firefox has no support and MDN gives no version** (https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/At-rules/@view-transition). It is for a cross-document navigation, and this application is a single-page application. It is the wrong tool here.

**Reduced-motion form.** The whole block sits inside `@media (prefers-reduced-motion: no-preference)`. A learner who asks for less motion then gets the browser's default cross-fade, which carries no movement.
**Risk to the layout tests.** Every test in `layout.spec.ts` calls `page.goto()` and then waits for an element. A 320ms root transition adds up to 320ms before the new page settles. Each test already waits on a locator, so this should pass. Run the whole Playwright suite once and confirm the timings. This is the one item in section 4 that needs a full suite run before it merges.

---

### 4.4 The `::selection` rule — one defect, one line

`focus-card.component.ts:185-188` uses the `background` shorthand:
```css
.focus__body::selection { background: var(--mt-amber); color: var(--mt-amber-ink); }
```
MDN states the allowed properties inside `::selection` are `color`, `background-color`, `text-decoration` and its associated properties, `text-shadow`, and three `-webkit-text-*` properties, and it adds "In particular, `background-image` is ignored" (https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Selectors/::selection). The `background` shorthand is not on the list. The CSS Pseudo-Elements Module Level 4 specification agrees, and its section 3.2 "Styling Highlights" lists the longhand only (https://drafts.csswg.org/css-pseudo-4/#highlight-styling). It adds a note: "Historically (and at the time of writing) only `color` and `background-color` have been interoperably supported."

**The change.** Use the longhand.
```css
.focus__body::selection { background-color: var(--mt-amber); color: var(--mt-amber-ink); }
```
The contrast is already correct: `--mt-amber-ink` on `--mt-amber` measures 5.48:1, and MDN's own accessibility note for this pseudo-element requires 4.5:1.

Support for `::selection`: Chrome 1, Edge 12, Firefox 62, Safari 1.1. A browser that drops the rule keeps its own selection colours, which is a safe fall-back.

---

## 5. WHAT NOT TO DO

1. **Do not replace the verb picker with the `popover` attribute.** It is well supported (Chrome 114, Edge 114, Firefox 125, Safari 17 — https://developer.mozilla.org/en-US/docs/Web/HTML/Reference/Global_attributes/popover), so the temptation is real. It moves the element to the top layer, which changes its computed `position` and its containing block. `layout.spec.ts:415-443` asserts `position: absolute` at 1360px and `position: fixed` with `bottom: 0` at 390px, and `layout.spec.ts:374-413` asserts that all four edges stay inside the card. A top-layer element has no such relationship to the card. The current component already computes its anchor inside an event handler, which keeps the reader server-renderable. That design is correct.

2. **Do not adopt CSS anchor positioning for the picker.** `position-anchor` reached Baseline newly available on **2026-09-14**, five days before this review, and it needs Chrome 151, Edge 151, Firefox 151 and Safari 27 (https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/position-anchor). MDN also records an earlier period with a wrong initial value in Chrome 125 to 151 and Firefox 147 to 151. A learner on a browser from last year gets a picker in the wrong place. The existing TypeScript clamp at `focus-card.component.ts:382-403` works everywhere, and a test proves it.

3. **Do not animate the focus card's height when the answer lands.** The two tools for it, `interpolate-size: allow-keywords` and `calc-size()`, are both Limited availability and Chromium-only (Chrome 129, Edge 129; no Firefox, no Safari). A JavaScript height animation reflows 62ch of prose on every frame, which is exactly the text the learner is about to highlight. Animate `opacity` and `transform`, and let the height jump.

4. **Do not make the streamed text selectable.** It is the obvious "improvement" and it breaks the product. `selectionToSpan` returns offsets into `root.textContent`, and the server checks `storedBody.substring(start, end) == text`. Streamed prose is in no stored body. Every explain from such a selection returns `SPAN_MISMATCH`. `focus-card.component.ts:23-46` documents this, and a test guards it.

5. **Do not put a highlight wrapper inside `.focus__body`.** The design draws an amber highlight, and a `<span>` is the obvious way to draw it. It shifts every offset and breaks every explain. `.focus__body::selection` already reaches the same look with no node.

6. **Do not remove the uppercase eyebrow.** It is a common tell of a templated design, and here it is not decoration. It carries `Step 3 · Explain`, `Your trail · 4 steps` and `Explain · here`. Each one is the only place that states a number or a verb.

7. **Do not remove the middle dot separator.** It is in the design source, and it separates a count from a name. It is a punctuation mark here, and not a style.

8. **Do not add a fade-and-slide entrance to every section.** The report proposes an entrance in six places, and each one answers a learner's own action or a data arrival. A page that animates on scroll, or on load, in every block reads as generated. The catalogue introduction, the legal pages and the guide pages get no entrance at all.

9. **Do not use scroll-driven animations.** `animation-timeline`, `scroll()` and `view()` are all Limited availability, and MDN prints "Preview" for Firefox with no released version (https://developer.mozilla.org/en-US/docs/Web/CSS/Reference/Properties/animation-timeline). There is no place in this product that needs one.

10. **Do not invent a percentage for the progress track.** Spec §7.3 already refused this, with the right reason. The band says "the machine is at work" and nothing more. A number the application cannot honour is a promise it will break.

11. **Do not delete the blanket reduced-motion rule until `animate.leave` is checked.** Angular's documentation states "You **must** call the `animationComplete()` function when using `animate.leave` for Angular to remove the element", and for the CSS form Angular waits for the animation to finish. A reduced-motion rule of `animation: none` on an `animate.leave` element can leave that element in the DOM. Keep 1ms, and never 0.

12. **Do not add a `transition` to `.focus__body`.** A transition on the paragraph the learner is reading moves the text under their pointer mid-drag.

---

## 6. THE PLAN — SEVEN WORK PACKAGES

Packages 1, 2 and 3 change CSS only. Start with them.

### Package 1 — Repair the contrast of the guide pages **(CSS only)**
- **Files.** `frontend/public/guides/guides.css`.
- **Holds.** F4. Plus the two raw `#fff` values, and the three un-named lift shadows (§1.3).
- **Acceptance criteria.**
  1. `.bar__cta` and `.start__cta` measure at or above 4.5:1 between their text and their fill. A unit test in `palette.spec.ts` asserts the pair.
  2. `grep -n "#fff\|#[0-9a-f]\{6\}" guides.css` finds a hex only inside the `:root` block.
  3. A Playwright check reads the computed `background-color` and `color` of `.bar__cta` on `/guides/` and asserts the ratio.
- **Order.** First. It is the only launch-facing accessibility failure, and it touches one file.

### Package 2 — Give the control system its states and its motion tokens **(CSS only)**
- **Files.** `frontend/src/styles.css`.
- **Holds.** F1, F2, F16, the motion token set of §4.2, the new reduced-motion block, the press and hover shadow tokens of §1.3, the `.mt-sr-only` helper of §1.4, and the two-tone focus ring of §3.3.
- **Acceptance criteria.**
  1. Every `.mt-pill` variant has a `:hover`, an `:active` and a `:disabled` rule. A style test asserts each selector exists.
  2. A ghost pill has `box-shadow: none` in every state. A Playwright test presses one and reads the computed `box-shadow`.
  3. `layout.spec.ts:475-492` passes unchanged, so the ring keeps its colour, width and offset.
  4. Under `prefers-reduced-motion: reduce`, `--mt-move-near` computes to `0px` and `--mt-dur-state` to `1ms`. A Playwright test with `reducedMotion: 'reduce'` asserts both.
  5. `npm test -- --watch=false` and `npx playwright test` both pass.
- **Order.** Second. Every later package uses these tokens.

### Package 3 — Raise the non-text contrast **(CSS only, plus one unit test)**
- **Files.** `frontend/src/styles.css`, `frontend/src/app/ui/palette.spec.ts`, `frontend/src/app/ui/status-dot.component.ts`, `frontend/src/app/assess/quiz-panel.component.ts`, `frontend/src/app/reader/focus-card.component.ts`.
- **Holds.** §3.2 in full — the new `--mt-edge` token, the quiz chosen state, the status dot shapes, the progress band pair. Plus F10 and the `::selection` longhand of §4.4.
- **Acceptance criteria.**
  1. `--mt-edge` on `--mt-surface` measures at or above 3:1. `palette.spec.ts` asserts it.
  2. The chosen quiz option differs from an unchosen one by a boundary of 3:1 or more, and by a second, non-colour signal. `palette.spec.ts` asserts the ratio; a unit test asserts the second signal in the DOM.
  3. The progress band against its track measures 3:1 or more.
  4. Each of the four status-dot states carries a distinct shape. A unit test asserts the class and the shape for each state.
  5. A long quiz option wraps to two lines with no overlap. A Playwright test measures the rendered height against a one-line option.
- **Order.** Third.

### Package 4 — Fix the four defects that lock a learner out
- **Files.** `frontend/src/app/auth/auth-landing.component.ts`, `frontend/src/app/account/allowance-meter.component.ts`, `frontend/src/app/reader/focus-card.component.ts`, `frontend/e2e/support.ts`, `frontend/e2e/layout.spec.ts`.
- **Holds.** F3, F5, F6, F7.
- **Acceptance criteria.**
  1. `/auth?auth=expired` renders both the banner and the sign-in panel. A unit test asserts both.
  2. `support.ts` stubs `GET /api/account`. `layout.spec.ts:562` runs once signed out and once signed in with a reset date, and the page scrolls sideways in neither.
  3. The streamed text carries `aria-live="off"`. A separate `role="status"` element changes exactly twice per stream. A unit test counts the changes.
  4. Each of the three busy controls carries `aria-busy="true"` and a label that names the work while its request runs. A unit test asserts each.
- **Order.** Fourth. It needs Package 2's busy style.
- **Tests that change.** `layout.spec.ts:562` (`the header fits on one line at 400px`).

### Package 5 — Animate the reader
- **Files.** `frontend/src/app/reader/focus-card.component.ts`, `frontend/src/app/ui/verb-picker.component.ts`, `frontend/src/app/reader/trail-rail.component.ts`, `frontend/src/app/reader/breadcrumb.component.ts`, `frontend/src/app/reader/reader-page.component.ts`, `frontend/e2e/layout.spec.ts`.
- **Holds.** Animations A, B, C, D, E, F, L and M of §4.3.
- **Acceptance criteria.**
  1. `.reader__main` moves 1px or less when the loaded session replaces the skeleton, at 390px, 768px and 1360px. `layout.spec.ts:326` passes unchanged.
  2. The picker's four edges stay inside the card at 768px and 1360px, once its animation settles.
  3. `.focus__body` has no `transition` and no `animation` while `isStreaming()` is true. A style test asserts it.
  4. Under `prefers-reduced-motion: reduce`, every animation in this package completes in 5ms or less, and no element stays in the DOM after its `animate.leave`. A Playwright test with `reducedMotion: 'reduce'` asserts the DOM is clean.
  5. `npx playwright test` passes, with no new flake over three consecutive runs.
- **Order.** Fifth.
- **Tests that change.** `layout.spec.ts:357` and `layout.spec.ts:374` each gain a wait for the picker's animation to settle before `boundingBox()`.

### Package 6 — Rebuild the hierarchy of the catalogue and the reader
- **Files.** `frontend/src/app/catalog/catalog-page.component.ts`, `frontend/src/app/catalog/catalog-page.component.spec.ts`, `frontend/src/app/reader/reader-page.component.ts`, `frontend/src/app/reader/focus-card.component.ts`, `frontend/src/app/ui/verb-picker.component.ts`, `frontend/e2e/layout.spec.ts`.
- **Holds.** F8, F9, F11, F13, F17, F18, and animation G of §4.3.
- **Acceptance criteria.**
  1. At 412px, the filter row's top edge sits within 200px of the page top. `layout.spec.ts:220` is extended with that assertion.
  2. Below 768px, the focus card renders above the trail rail in the visual order. A Playwright test compares the two `boundingBox().y` values at 390px.
  3. The topic name renders once on the reader at step 2 and beyond. A unit test asserts it.
  4. `.catalog__cat` pills have no `transform` animation. `layout.spec.ts:171` and `layout.spec.ts:266` pass unchanged.
  5. The picker holds five focusable controls below 768px and four above it.
- **Order.** Sixth.
- **Tests that change.** `catalog-page.component.spec.ts` (the `.catalog__intro` sibling assertion), `layout.spec.ts:494` (`Tab and Shift+Tab cycle inside the picker`), `layout.spec.ts:220`, `layout.spec.ts:326`.

### Package 7 — Finish the account page, and add the route transition
- **Files.** `frontend/src/app/account/account-page.component.ts`, `frontend/src/app/account/allowance-meter.component.ts`, `frontend/src/app/assess/quiz-panel.component.ts`, `frontend/src/app/app.config.ts`, plus the three legal components.
- **Holds.** F12, F14, F15, animations H, I, J and N of §4.3, and the legal-layout de-duplication of §1.4.
- **Depends on.** Issues #88, #90 and #91. Do this package after they merge, or the action row changes twice.
- **Acceptance criteria.**
  1. The account page draws a skeleton card before the first load answers. A unit test asserts it.
  2. `Delete account` sits in its own block, below a divider, and not in the row with `Sign out`. A unit test asserts the DOM order.
  3. The `Status` row renders a sentence, and never a raw enum. A unit test covers each status.
  4. The quiz panel either drops `aria-modal="true"` or applies `inert` to its siblings. A unit test asserts which.
  5. One `.legal-page` style block serves all three legal pages.
  6. `provideRouter` passes `withViewTransitions({ skipInitialTransition: true })`. The full Playwright suite passes three times with no new flake.
- **Order.** Last.

---

### One closing note on the review method

I could not run a browser: another agent holds the port, and the brief forbids `ng serve` and Playwright. Every ratio in section 3 is computed from the declared token values with the WCAG 2.2 formula, and every ratio is reproducible. Every control height in section 3.4 is arithmetic from the declared padding, font size, line height and border width; each one needs a measurement in a real browser before it becomes a fixed acceptance criterion. Every browser-support claim in section 4 carries the MDN URL it came from, and the two Angular API claims carry their angular.dev URLs.
