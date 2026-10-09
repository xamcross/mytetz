# mytetz listing kit

Each listing issue (#183, #184, #185, #186, #189) copies its texts and images from this file. The product then has the same words on each site. The texts are for the public.

## 1. Name

mytetz

Site: https://mytetz.com

## 2. Tagline candidates

Each tagline has a maximum of 60 characters.

1. Understand hard topics one sentence at a time (45)
2. Highlight a phrase. Get an explanation that fits. (49)
3. The same phrase, explained for your topic (41)

The owner picks one tagline. The decision goes in a comment on issue #182.

## 3. Short description

Maximum 260 characters.

> Pick a topic like quantum physics or game theory. Highlight any phrase you do not understand. mytetz explains it so that it fits your topic. A language model writes each explanation. (182)

## 4. Long description

Maximum 1 000 characters.

> mytetz helps you understand hard topics one sentence at a time.
>
> Pick a topic from a list of 29, for example quantum physics or game theory. Read a short text. Highlight any phrase that you do not understand, and press Explain. You get an explanation that fits where you are. Highlight a phrase in that explanation to go one level deeper. A trail of crumbs shows the way back.
>
> The same phrase gets a different explanation under a different topic. A word can mean different things in different fields. mytetz explains the meaning that your topic needs.
>
> Test Me asks you questions about what you read, so you can check what you learned.
>
> A language model writes each explanation. It can make a mistake. No person checks a new explanation before the site shows it. Read the known limits at https://mytetz.com/how-it-works.
>
> A new reader gets a free 7-day trial with no credit card.

(The long description has 880 characters.)

## 5. Pricing text

> A new reader gets a free 7-day trial, worth 40 tokens, with no credit card. One token pays for one new explanation or one quiz. After the trial, a subscription costs $12 each month. A subscriber gets 25 tokens each day. The count resets every day. The trial needs no card, so no payment starts when the trial ends.

Source of each number (code on `main`, checked on 2026-10-09):

| Number | Value | File and line |
|---|---|---|
| Price | $12 each month | `backend/api/src/main/kotlin/com/mytetz/api/BillingRoutes.kt` line 61 (`SUBSCRIPTION_PRICE_USD_PER_MONTH`) |
| Trial length | 7 days | `backend/billing/src/main/kotlin/com/mytetz/billing/Entitlement.kt` line 48 (`DEFAULT_TRIAL_DAYS`) |
| Trial tokens | 40 | `backend/billing/src/main/kotlin/com/mytetz/billing/Entitlement.kt` line 47 (`DEFAULT_TRIAL_GENERATIONS`) |
| Tokens each day for a subscriber | 25 | `backend/billing/src/main/kotlin/com/mytetz/billing/Entitlement.kt` line 50 (`DEFAULT_SUBSCRIBER_DAILY_EXPLAINS`) |

`fly.toml` sets none of the environment overrides (`MYTETZ_TRIAL_DAYS`, `MYTETZ_TRIAL_GENERATIONS`, `MYTETZ_SUBSCRIBER_DAILY_EXPLAINS`), so the defaults apply. The owner confirms that the production secrets set none of them before a listing goes live.

## 6. Categories and tags

Categories:

- Education
- Learning
- Artificial intelligence
- Self-improvement
- Productivity

Tags: learning, education, AI, explainer, study, self-study, quiz, knowledge, reading, tutor.

## 7. Products that a learner compares with mytetz

Each product was opened on its own website on 2026-10-09. The URL is the page that was read.

| Product | URL checked (2026-10-09) | The difference from mytetz |
|---|---|---|
| Khanmigo | https://www.khanmigo.ai/ | Khanmigo is an AI tutor from Khan Academy that guides a learner with questions. mytetz explains the exact phrase that you highlight, in the frame of your topic. |
| Gemini Notebook (the address of NotebookLM redirects here) | https://notebook.google/ | It is an AI research tool that works on your own material. mytetz needs no upload. You pick a topic and highlight a phrase. |
| Brilliant | https://brilliant.org/ | Brilliant teaches with interactive lessons and an AI tutor for math and coding. mytetz covers 29 topics and starts from the text that you read. |
| Anki | https://apps.ankiweb.net/ | Anki is a flashcard app that schedules your reviews. mytetz helps you understand a phrase first. It does not schedule reviews. |
| Wikipedia | https://www.wikipedia.org/ | Wikipedia is a free encyclopedia that people write. mytetz gives a short explanation of one phrase for your topic. A language model writes it. |
| Coursera | https://www.coursera.org/ | Coursera offers full courses, certificates, and degrees. mytetz gives a quick explanation of one phrase while you read. |

Not listed, because the product site did not load for the check (HTTP 403 for an automated fetch): Perplexity, Quizlet, ChatGPT study mode, Encyclopaedia Britannica.

## 8. Draft of the first maker comment

> Hi, I am [maker name], the maker of mytetz. (The owner fills in the name before posting.)
>
> I made mytetz because I often get stuck on one phrase in a hard text. A general search gives the same answer for each reader. I wanted an explanation that fits the topic that I am reading.
>
> How it works: you pick a topic, for example quantum physics. You highlight a phrase and press Explain. You can highlight a phrase in the answer to go deeper. The same phrase gets a different explanation under a different topic. Test Me then asks questions about what you read.
>
> What it does not do:
>
> - A language model writes each explanation. It can make a mistake, and it can state a claim with no source.
> - No person checks a new explanation before the site shows it.
> - It is not a course. It does not schedule reviews, and it does not give a certificate.
>
> The limits are written at https://mytetz.com/how-it-works. A new reader gets a free 7-day trial with no credit card. After that, a subscription costs $12 each month.
>
> I want your feedback. Which phrase did mytetz explain badly? Which topic is missing? You can report a problem with an explanation on GitHub.

## 9. Images

| File | What it shows | Status |
|---|---|---|
| `images/logo-240.png` | The logo, 240 x 240 px, rendered from `frontend/public/icon.svg` | Done |
| `images/dashboard.png` | The dashboard | Waits for the owner run |
| `images/topic.png` | A topic page | Waits for the owner run |
| `images/explanation.png` | A new explanation | Waits for the owner run |
| `images/breadcrumb.png` | A second level, with the breadcrumb | Waits for the owner run |
| `images/test-me.png` | The Test Me view | Waits for the owner run |
| `images/demo.webm` | A silent video of 30 to 60 seconds | Waits for the owner run |

The owner makes the screenshots and the video with the runbook "Listing capture (issue #182)" in `docs/deploy.md`. A test learner takes them on the live site. The owner checks that no image shows personal data before the commit.

## 10. Length check

Counts on 2026-10-09: taglines 45, 49, and 41 characters; short description 182 characters; long description 880 characters.
