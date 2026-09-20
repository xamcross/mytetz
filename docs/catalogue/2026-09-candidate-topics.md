# Candidate topics for the dashboard, ranked by search demand

This document supports issue #44. It ranks candidate subject topics for the
mytetz dashboard by search demand and search difficulty. Issue #18 can read this
file before it edits `topics.json`, but issue #18 does not wait on this file.

## 1. Method and its limits

The method follows the issue's own steps, with two exceptions that a hard rule
of this task blocks. Each exception is stated plainly below.

**Step 1 of the issue is blocked.** The issue asks for a read-only `mongosh`
query against the `topicRequests` collection, using `MONGODB_URI` from the
git-ignored `.env` file. This task's own rules forbid a command that reads a
`.env` file, and forbid a connection to a live database. No `.env` file even
exists in this worktree. This document therefore holds **zero** rows of real
visitor demand from `topicRequests`. Every `topicRequests count` cell in the
candidate table below reads "not read (blocked)". This is the single largest
gap in this document. A later session, run by the owner or by an agent with
database access, must read that collection and merge its counts into this
table.

**The web search step hit a hard, shared limit.** This session's `WebSearch`
tool has one budget for the whole session: 200 calls, shared across every
agent this task ran in parallel. The candidate list below holds 122 topics,
and the issue's method needs two searches per topic, so a full run needed 244
calls. The budget ran out after 142 calls, part-way through several
categories. The candidate table below holds **71 topics with a full, real
search check**. The other 51 topics have no search data. Section 5 lists them
by name, with the reason. No domain or difficulty in this document is
invented; a topic with no check has no row in the candidate table, and appears
only in the unchecked list.

**The search method itself.** For each of the 71 checked topics, this session
ran two web searches: `<topic> explained simply` and `<topic> for beginners`.
It recorded the domain of each of the first five organic results of each
search, in order, and skipped an ad result. It marked one difficulty with the
issue's own scale:

- **Easy**: two or more small sites hold a place in the first five results of
  at least one of the two searches.
- **Moderate**: exactly one small site holds a place in the better of the two
  searches.
- **Hard**: no small site holds a place in either search.

A small site is a personal blog, a newsletter, a Substack, a forum, or a small
company's own blog. A university page, Wikipedia, Britannica, a large news
site, and a government page are not a small site.

A "yes" verdict needs an unmet search intent, a fit with the product (a
reader studies a short passage on a general subject, then asks for an
explanation of one phrase), and no clash with an intent that an existing topic
or an existing guide of the site already owns. A candidate that repeats an
owned intent gets a "no" verdict, and the table names the topic or guide that
owns it.

**Date of every check in this document: 2026-09-20.**

**No comment was posted on issue #44.** This task's own rules forbid a
`gh issue comment` command. The issue asks for a comment that records the
`topicRequests` row count and the date. Since this session read zero rows
(step 1 is blocked), no such comment exists, and none should exist until a
session with database access reads the real collection.

## 2. The present 29 topics

`backend/catalog/src/main/resources/topics.json` holds 29 published topics in
12 categories. The table below is for context; it needed no search, since it
lists what the dashboard already carries.

| Topic | Category | Summary |
|---|---|---|
| Quantum Physics | Physics | How matter and light behave at and below the scale of atoms. |
| General Relativity | Physics | Gravity as the curvature of spacetime by mass and energy. |
| Special Relativity | Physics | Why two people moving differently can disagree about time and distance and both be right. |
| Thermodynamics | Physics | The rules governing heat and energy, and why some changes can never run backwards. |
| Microbiology | Biology | Life too small to see, and how much of the world it quietly runs. |
| Evolution by Natural Selection | Biology | How living things change across generations. |
| Genetics | Biology | How the instructions for building a living thing are stored in DNA. |
| Neuroscience | Biology | How the brain and nervous system turn electrical signals into thought. |
| Immunology | Biology | How the body learns to fight off an invader it has never met before. |
| Cryptography | Computer Science | Keeping information secret and verifiable in the presence of adversaries. |
| Machine Learning | Computer Science | Getting computers to learn patterns from examples. |
| Algorithms | Computer Science | Step-by-step recipes for solving problems, and what makes one faster than another. |
| Game Theory | Economics | What your best move becomes once everyone else is choosing theirs at the same time. |
| Supply and Demand | Economics | How buyers and sellers settle on a price. |
| Inflation | Economics | Why the same money buys less over time. |
| Chemical Bonding | Chemistry | Why atoms hold on to each other. |
| The Periodic Table | Chemistry | Why the elements fall into a repeating pattern. |
| Plate Tectonics | Earth Science | How the Earth's outer shell drifts and causes earthquakes. |
| Climate Systems | Earth Science | How air, oceans, land and ice set the long-run weather of the planet. |
| Black Holes | Astronomy | Places where gravity is so strong that not even light can get back out. |
| Cosmology | Astronomy | The story of the universe as a whole. |
| Probability | Mathematics | Measuring how likely something is. |
| Stoicism | Philosophy | An ancient practice of separating what you control from what you do not. |
| Epistemology | Philosophy | How to tell the difference between knowing something and merely being sure of it. |
| The Scientific Revolution | History | The period when Europe began settling questions of nature by experiment. |
| The Industrial Revolution | History | The shift to machines, factories and fossil fuel. |
| Cognitive Biases | Psychology | The predictable ways human judgement goes wrong. |
| Human Memory | Psychology | How the brain stores experience. |
| Historical Linguistics | Linguistics | How languages drift over centuries. |

Category counts today: Physics 4, Biology 5, Computer Science 3, Economics 3,
Chemistry 2, Earth Science 2, Astronomy 2, Mathematics 1, Philosophy 2,
History 2, Psychology 2, Linguistics 1. Issue #18 caps each category at 15, so
these are the twelve subject areas that the candidate list below must cover.

## 3. Candidate topics, ranked

122 candidates were built from the gaps in the twelve categories (`topicRequests`
gave no input; see Section 1). 71 of them have a real search check; they are
listed below, sorted "yes" first, then by difficulty (Easy, then Moderate,
then Hard), then the "no" verdicts. The `topicRequests count` column reads
"not read (blocked)" for every row, for the reason given in Section 1.

| Topic | Category | `topicRequests` count | Difficulty | Top domains (first five, `explained simply` / `for beginners`) | Question forms | Recommended | Notes |
|---|---|---|---|---|---|---|---|
| The solar system | Astronomy | not read (blocked) | Easy | nationalgeographic.com, howstuffworks.com, unacademy.com, wikipedia.org, britannica.com / amazon.com, realitypathing.com, britannica.com, sunnalsolar.com, esa.int | "How to Understand Solar System Components for Beginners?" | yes | Two small sites hold a place in the beginners search. No topic owns this subject. |
| Electromagnetism | Physics | not read (blocked) | Easy | geeksforgeeks.org, wikipedia.org, synopsys.com, wikipedia.org, energy.gov / wikiversity.org, entechonline.com, circuitbasics.com, kentdomombaby.com, damtp.cam.ac.uk | "What is Electromagnetism?"; "What does Electromagnetic mean?" | yes | A broad, classic subject. No topic owns it. |
| Nuclear physics | Physics | not read (blocked) | Easy | britannica.com, ieer.org, wikipedia.org, wikipedia.org, energy.gov / filipiknow.net, amazon.com, entechonline.com, sciencing.com, ieer.org | none found | yes | Two small-site guides hold a place in the beginners search. No topic owns it. |
| Fluid dynamics | Physics | not read (blocked) | Easy | ebsco.com, vedantu.com, livescience.com, sciencedirect.com, wikipedia.org / ansys.com, springer.com, electricsolenoidvalves.com, wikipedia.org, fyfluiddynamics.com | "What is Fluid Dynamics?" | yes | Two independent blogs hold a place in the beginners search. No topic owns it. |
| Classical mechanics | Physics | not read (blocked) | Easy | wikipedia.org, newworldencyclopedia.org, britannica.com, study.com, sciencedirect.com / udemy.com, springer.com, profoundphysics.com, entechonline.com, wikipedia.org | "What is classical mechanics?"; "What is classical mechanics in simple words?" | yes | Two personal physics blogs hold a place in the beginners search. No topic owns it. |
| Cell biology | Biology | not read (blocked) | Easy | britannica.com, nature.com, wikipedia.org, creative-diagnostics.com, sd2.org / amazon.com, researchgate.net, nature.com, oregonstate.education, umn.edu | none found | yes | Two small sites hold a place in the plain-English search. No topic owns it. |
| Ecology | Biology | not read (blocked) | Easy (flagged) | pearson.com, vaia.com, caryinstitute.org, esa.org, mtu.edu / medium.com, goodreads.com, edublogs.org, shakespeareandcompany.com, chaptersbookstore.com | "What is Ecology?" | yes | The plain-English search is clean but has no small site. The beginners search mostly sells one book. The subject still fits. |
| Photosynthesis | Biology | not read (blocked) | Easy | snexplores.org, britannica.com, irfanedu.com, a2zlessons.com, nationalgeographic.org / catchfoundation.in, littlebinsforlittlehands.com, britannica.com, centrepointschools.com, nationalgeographic.org | "What is Photosynthesis?" | yes | Genuine small blogs hold a place. The results skew toward a child/homework audience, but the mechanism still fits the product. |
| Epigenetics | Biology | not read (blocked) | Easy | wikipedia.org, whatisepigenetics.com, clevelandclinic.org, wikipedia.org, medlineplus.gov / whatisepigenetics.com, bullyproofclassroom.com, geneticsdigest.com, umich.edu, wikipedia.org | "What is Epigenetics?" | yes | Several small blogs hold a place. The subject sits close to the existing Genetics topic; keep the two distinct if built. |
| Marine biology | Biology | not read (blocked) | Easy (flagged) | marinebio.org, britannica.com, azolifesciences.com, study.com, wikipedia.org / udemy.com, sailcaribbean.com, universalclass.com, seamester.com, bioexplorer.net | "What is Marine Biology?" | yes | The beginners search is dominated by sailing-tour marketing, not explainer content. The subject itself still fits. No topic owns it. |
| How the internet works | Computer Science | not read (blocked) | Easy | explainthatstuff.com, medium.com, iotforall.com, hp.com, developer.mozilla.org / medium.com, medium.com, medium.com, explainthatstuff.com, devgenius.io | "How does the internet work?" | yes | No topic or guide owns this intent. |
| Databases | Computer Science | not read (blocked) | Easy | aws.amazon.com, rivery.io, mongodb.com, wikipedia.org, eology.net / geeksforgeeks.org, linkedin.com, freecodecamp.org, read.technically.dev, surrealdb.com | "What is a database?" | yes | No topic owns this intent. |
| Computer vision | Computer Science | not read (blocked) | Easy | databricks.com, aws.amazon.com, digitalocean.com, ibm.com, wikipedia.org / medium.com, machinelearningmastery.com, medium.com, picsellia.com, datacamp.com | "What is computer vision?"; "How to learn computer vision from scratch?" | yes | Sits near Machine Learning, but the core intent (how a machine reads an image) is distinct. |
| Blockchain | Computer Science | not read (blocked) | Easy | weforum.org, fidelity.com, aws.amazon.com, cardanofoundation.org, bitdegree.org / medium.com, blockchain-observatory.ec.europa.eu, cardanofoundation.org, updraft.cyfrin.io, 101blockchains.com | "What is blockchain?" | yes | Cryptography covers ciphers; blockchain (a shared ledger) is a distinct subject. |
| Data structures | Computer Science | not read (blocked) | Easy | medium.com, medium.com, medium.com, geeksforgeeks.org, w3schools.com / coursereport.com, medium.com, simplilearn.com, neetcode.io, mygreatlearning.com | "What is a data structure?" | yes | Sits near Algorithms, but the core intent (how data is organised) differs from Algorithms' intent (procedure efficiency). |
| Compilers | Computer Science | not read (blocked) | Easy | pvs-studio.com, medium.com, dev.to, freecodecamp.org, techtarget.com / medium.com, quora.com, guru99.com, quora.com, quora.com | "What is a compiler?"; "How do compilers work?" | yes | No topic owns this intent. |
| The water cycle | Earth Science | not read (blocked) | Easy | 11thhourracing.org, apecwater.com, wikipedia.org, usgs.gov, nasa.gov / generationgenius.com, whoi.edu, natgeokids.com, oberk.com, noaa.gov | "What is the water cycle?" | yes | Small sites rank well. No topic owns this intent. |
| Weather systems | Earth Science | not read (blocked) | Easy | oup.com, nauticed.org, ocolearnok.org, nationalgeographic.org, libretexts.org / surfertoday.com, wavestracks.com, observationhobbies.com, meteo.events, wavecrazer.com | none found | yes | The existing Climate Systems topic covers long-run climate, not day-to-day weather. Small niche blogs rank well. |
| The rock cycle | Earth Science | not read (blocked) | Easy | wikipedia.org, homesciencetools.com, berkeley.edu, opentextbc.ca, nationalgeographic.org / homesciencetools.com, learner.org, nationalgeographic.org, sciencenotes.org, thehomeschoolscientist.com | "What is the rock cycle?" | yes | Small sites rank well. No topic owns this intent. |
| Galaxies | Astronomy | not read (blocked) | Easy | wikipedia.org, study.com, britannica.com, wikipedia.org, nasa.gov / cloudynights.com, galactic-hunter.com, highpointscientific.com, optcorp.com, lovethenightsky.com | none found | yes | The beginners search shows equipment and astrophotography sites; the plain-English search shows clear subject content. No topic owns it. |
| The Moon | Astronomy | not read (blocked) | Easy | wikipedia.org, wikipedia.org, wikipedia.org, britannica.com, neksadipta.xyz / amazon.com, amazon.com, abc.net.au, milwaukeeastro.org, artsydee.com | none found | yes | The plain-English search is thin, with myth pages. The beginners search reaches Easy through an astronomy-club site and a craft blog. |
| Asteroids and comets | Astronomy | not read (blocked) | Easy | planetary.org, sciencetrek.org, unistellar.com, skyandtelescope.org, nationalgeographic.com / learnbright.org, milwaukeeastro.org, sciencetrek.org, study.com, nasa.gov | "What is the difference between a comet and an asteroid?"; "What are asteroids, comets & meteors?" | yes | Two small sites hold a place in the beginners search. Readers ask a clear question. No topic owns it. |
| Calculus | Mathematics | not read (blocked) | Easy | photomath.com, wikipedia.org, nagoya-u.ac.jp, mathsisfun.com, calcworkshop.com / mit.edu, calcworkshop.com, geeksforgeeks.org, betterexplained.com, lamar.edu | "What is Calculus?" | yes | Small, independent math sites hold a place in both searches. The existing Probability topic does not own this subject. |
| Geometry | Mathematics | not read (blocked) | Easy | snexplores.org, wikipedia.org, wikipedia.org, bhanzu.com, wikipedia.org / amazon.com, mathplanet.com, amazon.com, mathbitsnotebook.com, schoolyourself.org | none found | yes | Three small, independent math sites hold a place in the beginners search. No topic owns it. |
| Free will and determinism | Philosophy | not read (blocked) | Easy | vaia.com, tutor2u.net, pressbooks.online.ucf.edu, wikipedia.org, earlyyears.tv / tutor2u.net, simplypsychology.org, study.com, pressbooks.online.ucf.edu, earlyyears.tv | "Why Are You Reading This Article?" | yes | Small sites rank for it, so real reader demand exists. No topic owns it. |
| Logic and fallacies | Philosophy | not read (blocked) | Easy | writers.com, scribbr.com, actuary.org, kellogg.edu, iep.utm.edu / finmasters.com, logicallyfallacious.com, actuary.org, yourlogicalfallacyis.com, writingcenter.unc.edu | none found | yes | Epistemology owns "how we know things," not this. Strong small-site demand. |
| The trolley problem | Philosophy | not read (blocked) | Easy | psyche.co, ebsco.com, hks.harvard.edu, jswve.org, harvestinternationalschool.in / lorenzoelijah.substack.com, merriam-webster.com, ebsco.com, pmc.ncbi.nlm.nih.gov, sketchplanations.com | "What is the 'Trolley Problem?'"; "How the Trolley Problem Works" | yes | Reads as a focused case study, not a narrow task, with strong demand. No topic owns it. |
| Sleep and dreams | Psychology | not read (blocked) | Easy (flagged) | pressbooks.pub, sleepfoundation.org, medicalnewstoday.com, webmd.com, unacademy.com / learnbright.org, wfla.com, busyteacher.org, csun.edu, webmd.com | none found | yes | The beginners result set is strange (a lesson-plan site, a TV blog, an ESL worksheet site), so treat the Easy read with caution. |
| Emotional intelligence | Psychology | not read (blocked) | Easy | simplypsychology.org, 6seconds.org, clevelandclinic.org, webmd.com, wikipedia.org / udemy.com, ahead-app.com, dummies.com, harvard.edu, ruxandralemay.com | none found | yes | Two small sites hold a place. No topic owns this intent. |
| Particle physics | Physics | not read (blocked) | Moderate | ebsco.com, mpg.de, factmyth.com, energy.gov, wikipedia.org / skyatnightmagazine.com, amazon.com, wikipedia.org, mpg.de, fnal.gov | none found | yes | Sits near Quantum Physics, but the core intent (the Standard Model) is distinct. |
| String theory | Physics | not read (blocked) | Moderate | masterclass.com, space.com, britannica.com, byjus.com, caltech.edu / medium.com, press.princeton.edu, dummies.com, math.berkeley.edu, masterclass.com | none found | yes | Popular subject with steady demand. One small blog holds a place. No topic owns it. |
| Superconductivity | Physics | not read (blocked) | Moderate | vedantu.com, energy.gov, nationalmaglab.org, sciencedirect.com, britannica.com / physicsforums.com, quora.com, energy.gov, intechopen.com, wikipedia.org | "What is superconductivity?" | yes | A physics forum holds a place. No topic owns it. |
| Semiconductor physics | Physics | not read (blocked) | Moderate | methodist.edu.in, sciencedirect.com, fiveable.me, physics.info, electronics-tutorials.ws / physicsforums.com, coursera.org, umd.edu, electronics-tutorials.ws, methodist.edu.in | "What is a Semiconductor?" | yes | A physics forum holds a place. No topic owns it. |
| Virology | Biology | not read (blocked) | Moderate | ebsco.com, sciencedirect.com, news-medical.net, wikipedia.org, ncbi.nlm.nih.gov / centreofexcellence.com, udemy.com, virology.ws, classcentral.com, wikipedia.org | "What is Virology?" | yes | A well-known personal virology blog holds a place. Sits close to Microbiology (alias "germs"); keep the two distinct if built. |
| Operating systems | Computer Science | not read (blocked) | Moderate | microsoft.com, uow.edu.au, youtube.com, geeksforgeeks.org, ibm.com / medium.com, guru99.com, netacad.com, geeksforgeeks.org, tutorialspoint.com | "What is an operating system?" | yes | No topic owns this intent. |
| Natural language processing | Computer Science | not read (blocked) | Moderate | online.abertay.ac.uk, aws.amazon.com, coursera.org, wikipedia.org, deeplearning.ai / datacamp.com, machinelearningmastery.com, developer.ibm.com, kaggle.com, analyticsvidhya.com | "What is natural language processing?" | yes | Sits near Machine Learning, but the core intent (how a machine reads human language) stays distinct. |
| Acids and bases | Chemistry | not read (blocked) | Moderate | pearson.com, pressbooks.pub, ebsco.com, sciencelearn.org.nz, chemicals.co.uk / libretexts.org, superprof.ie, ebsco.com, sciencelearn.org.nz, pressbooks.pub | none found | yes | No topic owns this intent. |
| Electrochemistry | Chemistry | not read (blocked) | Moderate | biolinscientific.com, sciencedirect.com, study.com, libretexts.org, wikipedia.org / linkedin.com, acs.org, lumenlearning.com, wikipedia.org, bookauthority.org | "What is Electrochemistry?" | yes | No topic owns this intent. |
| Polymers and plastics | Chemistry | not read (blocked) | Moderate | britannica.com, azom.com, xometry.com, chem1.com, stahl.com / azom.com, sciencedirect.com, xometry.com, sciencehistory.org, stdpoly.com | "What Are the Differences?" (polymer vs plastic); "What Are Polymers?" | yes | No topic owns this intent. |
| Chemical equilibrium | Chemistry | not read (blocked) | Moderate | pasco.com, pearson.com, ebsco.com, vedantu.com, britannica.com / pearson.com, pasco.com, libretexts.org, pressbooks.pub, pressbooks.pub | "What Is Chemical Equilibrium?" | yes | No topic owns this intent. |
| Ocean currents | Earth Science | not read (blocked) | Hard | sciencetimes.com, wikipedia.org, nationalgeographic.org, noaa.gov, noaa.gov / nationalgeographic.org, noaa.gov, wikipedia.org, nationalgeographic.org, britannica.com | "What causes ocean currents?"; "What is a current?" | yes | No topic owns this intent, though large sites fill most results. |
| Stars and stellar evolution | Astronomy | not read (blocked) | Hard | wikipedia.org, umich.edu, chandra.si.edu, aavso.org, sfasu.edu / ebsco.com, wikipedia.org, chandra.harvard.edu, adsabs.harvard.edu, aavso.org | none found | yes | Only university and government sites appear. No topic owns this intent. |
| Exoplanets | Astronomy | not read (blocked) | Hard | uchicago.edu, skyatnightmagazine.com, space.com, esa.int, nasa.gov / physics.org, skyatnightmagazine.com, sciencetrek.org, spark.iop.org, education.nationalgeographic.org | "What are exoplanets?"; "What is an exoplanet?" | yes | Only large and institutional sites appear, but the results are clean subject content, not a purchase task. |
| Dark matter | Astronomy | not read (blocked) | Hard | uchicago.edu, energy.gov, britannica.com, wikipedia.org, nasa.gov / accuweather.com, wikipedia.org, energy.gov, nasa.gov, nasa.gov (jpl) | "What is dark matter?"; "What exactly is dark matter?"; "How do we see dark matter?" | yes | Only government, university and large media sites appear. Readers ask a clear question. |
| Number theory | Mathematics | not read (blocked) | Hard | thethinkacademy.com, wikipedia.org, geeksforgeeks.org, howstuffworks.com, brown.edu / amazon.com, amazon.com, springer.com, wikipedia.org, brown.edu | "What is number theory?" | yes | Only large and institutional sites appear. Readers ask a clear question. |
| Organic chemistry | Chemistry | not read (blocked) | Hard | acs.org, lumenlearning.com, lumenlearning.com, dummies.com, libretexts.org / amazon.com, lumenlearning.com, pearson.com, openstax.org, khanacademy.org | none found | yes | Large sites fill the results, but the subject still fits the format well. |
| Catalysts | Chemistry | not read (blocked) | Hard | snexplores.org, energy.gov, wikipedia.org, khanacademy.org, merriam-webster.com / youtube.com, khanacademy.org, wikipedia.org, study.com, libretexts.org | "What is a catalyst?" | yes | The beginners result set was thin, with one unrelated day-trading video, but no topic owns this intent. |
| Chirality in molecules | Chemistry | not read (blocked) | Hard | pearson.com, study.com, snexplores.org, wikipedia.org, libretexts.org / pearson.com, uspto.gov, study.com, wikipedia.org, arxiv.org | "What is chirality?" | yes | The beginners result set was thin, with a patent-office page and two preprint pages, but no topic owns this intent. |
| Utilitarianism | Philosophy | not read (blocked) | Hard | utilitarianism.net, utilitarianism.net, iep.utm.edu, ethicsunwrapped.utexas.edu, wikipedia.org / ethicsunwrapped.utexas.edu, utilitarianism.net, press.rebus.community, utilitarianism.net, scu.edu | "What is Utilitarianism?" | yes | A clear general subject, close in shape to Stoicism. |
| Kantian ethics | Philosophy | not read (blocked) | Hard | masterclass.com, wallstreetmojo.com, wikipedia.org, corporatefinanceinstitute.com, ebsco.com / corporatefinanceinstitute.com, wikipedia.org, perlego.com, plato.stanford.edu, iep.utm.edu | none found | yes | A strong general subject despite the hard field. No topic owns it. |
| Classical and operant conditioning | Psychology | not read (blocked) | Hard | psypost.org, indeed.com, wikipedia.org, choosingtherapy.com, psychologytoday.com / psypost.org, choosingtherapy.com, pearson.com, uspto.gov, wikipedia.org | "What Is the Difference Between Classical Vs. Operant Conditioning?" | yes | No topic owns this intent, even though large sites hold every top spot. |
| Existentialism | Philosophy | not read (blocked) | Moderate | ethics.org.au, plato.stanford.edu, tameri.com, ebsco.com, dummies.com / archive.org, amazon.com, goodreads.com, books.google.com, forbeginnersbooks.com | "What Is Existentialism?"; "What are some good books on existentialism for beginners in philosophy?" | yes | No topic owns this intent. Fits the format well, like Stoicism. |
| Nihilism | Philosophy | not read (blocked) | Moderate | ethics.org.au, iep.utm.edu, wikipedia.org, wikipedia.org, merriam-webster.com / audible.com, amazon.com, soulscape.siterubix.com, wikipedia.org, iep.utm.edu | "What Is Nihilism?" | yes | A clear general philosophy subject. No topic owns it. |
| Statistics | Mathematics | not read (blocked) | Moderate | dreambox.com, pearson.com, byjus.com, wikipedia.org, wikipedia.org / datacamp.com, umn.edu, geeksforgeeks.org, statisticshowto.com, scribd.com | "What is a Statistic?" | yes | Distinct from the existing Probability topic. No topic owns it. |
| Linear algebra | Mathematics | not read (blocked) | Moderate | wikipedia.org, geeksforgeeks.org, minireference.com, byjus.com, wikipedia.org / udemy.com, umn.edu, onlinemathtraining.com, geeksforgeeks.org, ucdavis.edu | none found | yes | No topic owns this intent. |
| Dark energy | Astronomy | not read (blocked) | Moderate | space.com, britannica.com, sciencedaily.com, earthsky.org, nasa.gov / accuweather.com, taraenergy.com, wikipedia.org, britannica.com, space.com | "What is dark energy?"; "What are dark matter and dark energy?" | yes | Sits close to Cosmology's expanding-universe angle, but explains a different mechanism. |
| Neutron stars | Astronomy | not read (blocked) | Moderate | skyatnightmagazine.com, energy.gov, wikipedia.org, space.com, britannica.com / astronomy.com, adsabs.harvard.edu, study.com, umd.edu, icouriertracking.in | "What is a neutron star? How do they form?" | yes | The one small site in the beginners search is an odd, unrelated domain, so treat this rating as weak. No topic owns it. |
| Attachment theory | Psychology | not read (blocked) | Moderate | simplypsychology.org, ebsco.com, drbecker-phelps.com, ncbi.nlm.nih.gov, illinois.edu / amazon.com, hercampus.com, simplypsychology.org, ncbi.nlm.nih.gov, positivepsychology.com | none found | yes | A personal therapist blog holds a place. No topic owns it. |
| Personality psychology and the Big Five | Psychology | not read (blocked) | Moderate | simplypsychology.org, ebsco.com, psychcentral.com, psychologytoday.com, thomas.co / hrdqstore.com, udemy.com, ebsco.com, simplypsychology.org, psychologytoday.com | "What Are The Big 5 Personality Traits?" | yes | A small training-company site holds a place. No topic owns it. |
| Social psychology, conformity and obedience | Psychology | not read (blocked) | Moderate | lumenlearning.com, ucf.edu, tesu.edu, openstax.org, baypath.edu / tesu.edu, cuny.edu, lumenlearning.com, openstax.org, medium.com | none found | yes | A personal essay on Medium holds a place. The existing Cognitive Biases topic covers individual reasoning, not group influence, so this does not repeat it. |
| Optics | Physics | not read (blocked) | Easy (flagged) | sdsu.edu, wikipedia.org, toppr.com, sciencedirect.com, edmundoptics.com / sdsu.edu, udemy.com, vision-systems.com, gideonoptics.com, firefield.com | "What is Optics?" | yes | The plain-English search is clean; the beginners search is polluted by rifle-scope brand sites, but the subject itself still fits. |
| Wave-particle duality | Physics | not read (blocked) | Moderate | ebsco.com, theconversation.com, study.com, britannica.com, andreaminini.net / medium.com, vedantu.com, spark.iop.org, ebsco.com, monash.edu | "What is wave-particle duality?"; "What is wave-particle duality in dual nature of matter and radiation?" | **no** | This is a core, defining concept of the existing Quantum Physics topic; already owned. |
| Human anatomy | Biology | not read (blocked) | Hard | kenhub.com, medicalnewstoday.com, wikipedia.org, openstax.org, wikipedia.org / wikipedia.org, kenhub.com, tutsplus.com, teachmeanatomy.info, clipstudio.net | "What is it?"; "Why is it important?" | **no** | No small site in either search; large anatomy-education brands dominate. The beginners search also mixes in art-drawing guides. |
| Quantum computing | Computer Science | not read (blocked) | Easy | malwarebytes.com, aws.amazon.com, nist.gov, bluequbit.io, ibm.com / towardsdatascience.com, bluequbit.io, nqcc.ac.uk, qureca.com, help.rc.unc.edu | "What is quantum computing?" | **no** | Explained through superposition and entanglement; the existing Quantum Physics topic already owns this intent. |
| Cybersecurity fundamentals | Computer Science | not read (blocked) | Hard | coursera.org, wgu.edu, knowledgehut.com, pingidentity.com, codecademy.com / udemy.com, wgu.edu, knowledgehut.com, coursera.org, codecademy.com | none found | **no** | The real intent is a practical safety checklist (passwords, updates, phishing), not a concept to explain. No small site holds a place. |
| Chemical reactions and stoichiometry | Chemistry | not read (blocked) | Moderate | pearson.com, pearson.com, pressbooks.pub, wikipedia.org, libretexts.org / khanacademy.org, albert.io, youtube.com, instructables.com, weebly.com | "What is Stoichiometry?" | **no** | The real intent is a calculation drill (mole ratios, practice problems), not a reading subject. |
| Thermochemistry | Chemistry | not read (blocked) | Moderate | chemistrytalk.org, wikipedia.org, ebsco.com, chemistryexplained.com, sciencedirect.com / wikipedia.org, ebsco.com, chemistrytalk.org, sciencedirect.com, scribd.com | "What is Thermochemistry?" | **no** | The existing Thermodynamics topic already owns the heat-and-energy intent. |
| Atomic structure | Chemistry | not read (blocked) | Hard | vedantu.com, pearson.com, sciencedirect.com, byjus.com, geeksforgeeks.org / vedantu.com, youtube.com, geeksforgeeks.org, khanacademy.org, khanacademy.org | none found | **no** | Large education platforms fill every slot. The existing Periodic Table and Quantum Physics topics already cover most of this. |
| Volcanoes | Earth Science | not read (blocked) | Hard | nationalgeographic.com, britannica.com, nationalgeographic.org, wikipedia.org, wikipedia.org / amazon.com, amazon.com, harvard.com, nhbs.com, goodreads.com | "What is a volcano?" | **no** | The existing Plate Tectonics topic already covers volcanic activity. The beginners results mostly sell books. |
| Space telescopes | Astronomy | not read (blocked) | Moderate | ebsco.com, wikipedia.org, sciencedirect.com, lco.global, spacecentre.co.uk / space.com, optcorp.com, planetary.org, space.com, astronomy.com | "How do telescopes work?" | **no** | The beginners search is full of backyard-telescope buying guides: a purchase task, not a subject to study. |
| Child development | Psychology | not read (blocked) | Hard | wikipedia.org, nottingham.ac.uk, ncbi.nlm.nih.gov, britannica.com, wikipedia.org / wikipedia.org, raisingchildren.net.au, wikipedia.org, clevelandclinic.org, zerotothree.org | none found | **no** | Every place goes to an encyclopedia, a university, or a government/health-system site. The subject is too broad for a small site. |

**Row count: 71.** **Recommended ("yes"): 61.** **Not recommended ("no"): 10.**
Date of every check above: 2026-09-20.

## 4. The next ten topics to write

These ten sit across nine of the twelve categories, each with a clean "Easy" or
"Moderate" read and no clash with an owned intent. Economics, History and
Linguistics have no checked candidate (Section 5), so none of their topics can
be in this list yet.

1. **The solar system** (Astronomy). Two small sites hold a place, and no
   topic owns this general, high-appeal subject.
2. **Electromagnetism** (Physics). A broad, foundational subject with small
   sites in the beginners search and no owned overlap.
3. **Cell biology** (Biology). Small sites hold a place and no topic owns
   this core subject.
4. **How the internet works** (Computer Science). No topic or guide owns this
   intent, and small sites hold most of the results.
5. **The water cycle** (Earth Science). Small sites rank well and no topic
   owns this subject.
6. **Calculus** (Mathematics). Small, independent math sites hold a place,
   distinct from the existing Probability topic.
7. **Free will and determinism** (Philosophy). Small sites rank for it, so
   real reader demand exists, with no owned overlap.
8. **Emotional intelligence** (Psychology). Two small sites hold a place and
   no topic owns this intent.
9. **Nuclear physics** (Physics). A second strong Physics pick: two
   small-site guides hold a place, with no owned overlap.
10. **Acids and bases** (Chemistry). The strongest Chemistry candidate this
    session could check; no topic owns this intent, and it fits the format
    well.

## 5. What this session could not check

**The demand signal.** `topicRequests` was never read. Step 1 of the issue,
and the issue's fourth acceptance criterion (a comment with the row count and
the date), both need that read. This task's rules forbid a `.env` read and a
live database connection, so neither happened. A session with the right
access must run `mongosh` against `MONGODB_URI`, record the row count and the
date, and post that as a comment on issue #44.

**51 candidates with no search check**, because the session-wide `WebSearch`
budget (200 calls) ran out before their turn:

- Economics (10, none checked): Behavioral economics; Comparative advantage;
  Gross domestic product; Monetary policy; Stock market basics; Opportunity
  cost; Externalities; Monopoly and market power; Cryptocurrency economics;
  Income inequality.
- History (10, none checked): The Roman Empire; World War II; The Cold War;
  Ancient Egypt; The French Revolution; The Renaissance; The Silk Road;
  Colonialism and imperialism; The Age of Exploration; Ancient Greece and
  Athenian democracy.
- Linguistics (11, none checked): Phonetics and phonology; Syntax and grammar
  structure; Semantics and meaning; Sociolinguistics; Language acquisition in
  children; Sign language linguistics; Bilingualism; Pragmatics; The origin of
  writing systems; Language and the brain; Endangered languages.
- Biology (3 of 10 not checked): Botany; The endocrine system; CRISPR gene
  editing.
- Earth Science (5 of 10 not checked): Glaciers and ice ages; Groundwater and
  aquifers; Soil formation; The carbon cycle; Hurricanes and storm formation.
- Mathematics (6 of 11 not checked): Set theory; Topology; Fractals; Graph
  theory; The Fibonacci sequence and the golden ratio; Mathematical logic.
- Philosophy (3 of 10 not checked): Plato's theory of forms; Philosophy of
  mind; Social contract theory.
- Psychology (3 of 10 not checked): Motivation theory; Anxiety and depression
  explained; The placebo effect.

**No search volume tool.** No Ahrefs or Semrush connection exists in this
session. No search-volume column appears anywhere in this document; every
ranking here rests on a real result-page check alone, as the issue allows when
no such tool is connected.

**A few result sets were thin or strange**, and are flagged inside the table
above: Optics, Ecology, Marine biology, and Sleep and dreams each had a
beginners search polluted by an unrelated market (rifle scopes, a single
book's sellers, sailing tours, or lesson-plan sites). Their "Easy" or
"Moderate" read should be treated as weaker than a clean result set.

## 6. Status against the issue's acceptance criteria

- **At least 120 candidates, every column filled.** Not met. 122 candidates
  were built, but only 71 have a real search check with every column filled.
  The `WebSearch` session budget (200 calls, shared across every agent this
  task ran) is the cause; see Section 1 and Section 5.
- **At least 71 rows marked "recommended".** Not met. 61 of the 71 checked
  rows are "yes".
- **The file holds only normalised request text, no raw visitor input.** Met.
  This document holds no `topicRequests` text at all, raw or normalised,
  because that collection was never read (Section 1).
- **A comment on issue #44 records the `topicRequests` row count and the
  date.** Not met, and not attempted. This task's rules forbid a
  `gh issue comment` command, and zero rows were read.
