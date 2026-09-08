# Desk research: what studio lighting is

**Status:** research note, not a decision. Nothing here changes a default, a PRD section, or an ADR.
It exists so that the light-related decisions this product already makes — the two scenarios and six
Kelvin presets of PRD §6.4, the "add light" warning of §6.3, the flicker-safe shutter of §6.2, and
the open PWM question in #37 — are made against what the practice actually is rather than against a
guess.

Read alongside [ADR-0005](../adr/0005-exposure-control-own-metering-loop.md) (the metering loop and
the flicker-safe shutter), [ADR-0011](../adr/0011-per-lens-capability-gating.md) (Kelvin to gains),
and [ADR-0023](../adr/0023-lock-exposure-for-the-take.md) (why a light change mid-take is the
accepted risk).

This note is about the **practice** — the craft of deciding what light falls on a face. Equipment and
the rooms built around it are deliberately out of scope.

---

## 1. The practice

Studio lighting, as a practice, is the craft of deciding for a given face **how much** light falls on
it, **how soft** that light is, **where** it comes from, and **what colour** it is. Those four
variables are the whole subject, and they are independent: any of them can be right while the others
are wrong.

A fifth property — **flicker** — is not a creative variable at all. Nobody chooses it. But it is the
one that ruins a take outright rather than making it worse, and it is the one this product was built
around.

Our user is not practising this deliberately. They have a desk, a window, a ceiling fixture, and
possibly one bought light. The product's job is not to teach the craft; it is to *not fight* it when
someone is doing it, and to fail legibly when nobody is.

---

## 2. Direction

### Three-point lighting

The default vocabulary for a talking head, and the thing almost every creator tutorial teaches.

| Light | Where | What it does |
|---|---|---|
| **Key** | 30–45° off the camera axis, slightly above the eyeline | The dominant source. Sets the exposure and the shadow shape. |
| **Fill** | Opposite side, lower, softer or weaker | Lifts the shadow side so detail survives. |
| **Back / rim / hair** | Behind the subject, off-axis | Separates the head from the background; restores depth the first two flatten. |

A fourth position, aimed at the background rather than the subject, is common enough to be part of
the standard grammar.

### Ratio

The relationship between the lit and shadow sides is described as a ratio, and it is the single
number that describes the mood:

- **1:1** — flat, shadowless. Beauty, comedy, commercials, and most webcam software's idea of good.
- **2:1** — a gentle, natural falloff. The de facto default for interviews and talking-head video.
- **4:1 and beyond** — dramatic, moody, and unusual for a piece delivered to camera.

### The background belongs a stop or two below the face

The ratio above describes the face. There is a second brightness relationship, between the subject
and everything behind them, and it is the one that separates footage that looks lit from footage
that looks recorded. **The background should sit roughly one to two stops under the face.** If the
two are the same brightness the frame reads flat, no matter how well the key is placed, because
nothing tells the eye which plane to attend to.

Three things produce that separation, and they compound:

- **Distance.** Move the speaker away from the wall — a couple of metres is the usual figure — and
  the falloff of §3 does the work: light that reaches the face has spread further by the time it
  reaches the wall behind, so the background darkens without a single extra decision. This is the
  cheapest control in the entire practice, and it costs nothing but floor space.
- **Aim.** Keeping the key off the wall, or flagging it so its spill does not reach, keeps the
  background's exposure independent of the subject's.
- **A rim.** The back light exists precisely for the case where separation by brightness fails —
  dark hair and dark clothing against a dark background — and draws an edge instead.

The failure mode this guards against is not darkness but the reverse: a subject sitting **in front
of** the brightest thing in the room. A window behind the speaker inverts the relationship, and no
amount of key fixes it, because the background is not something the key controls.

### Why off-axis is the whole point

Light that arrives along the camera axis fills its own shadows. It is even and it is flat: it removes
the highlight-to-shadow gradient that reads as depth, and it reflects straight back off glasses. Every
pattern below is an argument about how far off-axis to go, and the answer for a seated speaker is
almost never zero.

### Portrait patterns

Named by where the key puts the nose shadow. Useful mostly as evidence of how small an angle change
matters: the difference between "loop" and "Rembrandt" is a few degrees of key position.

- **Butterfly / Paramount** — key high and on-axis; a small shadow directly under the nose.
- **Loop** — key ~45°; a short shadow looping off the side of the nose. The most generally flattering,
  and the closest to what a light beside a monitor does naturally.
- **Rembrandt** — key further round and higher; the nose and cheek shadows meet, leaving a triangle
  of light on the far cheek.
- **Split** — key at 90°; half the face lit. Dramatic.
- **Short vs broad** — whether the *far* or the *near* cheek is the lit one. Short lighting slims,
  broad lighting widens.
- **Clamshell** — butterfly plus a soft source from below. Very common in beauty and creator work; a
  desk with a light above the monitor and a pale desktop below already is one, by accident.

---

## 3. Quality: hard and soft

**Soft is a function of apparent size.** The property that decides whether a shadow edge is a hard
line or a gradient — cinematographers call it the *shadow edge transfer* — is the size of the source
**relative to its distance from the subject**. A source one metre across at one metre is soft; the
same source at ten metres is hard. This is why "make it bigger" and "move it closer" are the same
instruction.

**Anything can be made softer** two ways: put diffusion in front of it, or bounce it off something
big. In a bounce, the bounce surface *becomes* the source — light fired into a white ceiling is an
overhead soft source the size of the ceiling patch it lights.

**Falloff.** The inverse-square law — double the distance, quarter the light — is what makes "move
the key 30 cm closer" a real exposure change rather than a nuance. The caveat matters for a desk: it
holds for point sources. A large soft source close to the subject falls off noticeably *less*
steeply, which is part of why a big close source is forgiving of a speaker who leans in and out.

---

## 4. Quantity: how much light is actually needed

This is where the practice meets our exposure model, so it is worth doing the arithmetic rather than
repeating a rule of thumb.

At the defaults the PRD fixes — **1/50 s, f/1.8** — a correct exposure at **ISO 100** needs roughly
**400 lux** incident on the subject (`EV ≈ 7.3`; this is the number ADR-0005 already cites, and it is
where the "too much light" reasoning starts). One consequence falls out of it:

**The "add light" warning is a ~50 lux warning.** PRD §6.3's default too-dark threshold is ISO 800 —
three stops above ISO 100 — so the warning fires below roughly **50 lux** on the face. For
calibration: an ordinary office is 300–500 lux, a domestic living room in the evening is often
50–150 lux, and a desk lit only by a monitor is well under 50.

The practical reading: **for a seated speaker, the product will rarely be short of light when the
person has lit themselves at all.** The dark case is the one where they have not thought about light
whatsoever. That is an argument for the §6.3 warning's copy being instructional ("add light") rather
than diagnostic, which is what it already is.

---

## 5. Colour

### Colour temperature

The two anchors of the trade are **tungsten ≈ 3200 K** and **daylight ≈ 5600 K**; overcast sky and
shade run 6500 K and higher. Domestic "warm white" sits lower still, around 2700 K.

Our preset table maps onto this cleanly, with one gap worth noting: the "Lamps only" scenario's
3200 K bottom rung is the *tungsten* anchor, and tungsten is now a historical reference rather than
what is in the room. A creator lighting themselves deliberately is likely to be at 4500–5600 K, and
the warm domestic bulb they are lighting themselves with accidentally is below our lowest preset.
Whether that gap matters is a question for the grey-card work in
[#24](https://github.com/davideme/scenaristo-camera/issues/24), not for this note.

### Tint, and why mixing is the real problem

Colour temperature is one axis; the **green–magenta** axis is the other, and it is the one that makes
mixed lighting look wrong in a way a Kelvin control cannot fix. Fluorescent sources and cheap LEDs
push green; the corrective gels are literally named *plus green* and *minus green*. PRD §6.4 fixes
tint at 0 in v1, which is a defensible scope decision, but it means a room lit by a green-biased
ceiling fixture reads green at every preset.

**Mixed sources are the normal domestic case, not the exception:** a window at 6500 K, a desk lamp at
2700 K, and a ceiling fixture somewhere between. One locked white balance can only ever be correct
for one of them. The craft answer is to eliminate the mix — correct one source to match the other,
close the blinds, or switch something off — and that, rather than a smarter auto white balance, is
what a warning could eventually say.

### Spectrum, which is neither axis

A light can be the right temperature and the right tint and still render skin wrong, because its
spectrum has gaps rather than an offset. The trade measures this with a ladder of metrics: **CRI**
(the incandescent-era standard, 8–15 pastel samples, easy for an LED to be tuned against), **TLCI**
(the same idea rebuilt around how a camera rather than an eye sees), **TM-30** (99 samples, fidelity
and gamut reported separately), and **SSI** (spectral similarity to a reference, which catches the
gaps the others miss).

The takeaway for this product is narrow but real: **a spectrally deficient source is a colour error
our white balance cannot correct**, because the light is missing wavelengths rather than being the
wrong temperature. It is invisible to a Kelvin control and shows up as bad skin tone. We cannot detect
it and should not pretend to.

---

## 6. Flicker: the part this product already lives inside

Two distinct mechanisms produce banding, and **only one of them is fixed by our shutter ladder.**

**Mains ripple (100 / 120 Hz).** A light driven from rectified AC ripples at twice the mains
frequency — 100 Hz on a 50 Hz grid, 120 Hz on 60 Hz. This is what PRD §6.2's flicker-free shutter
addresses, and the fix is exact: an exposure that is a whole multiple of the half-period integrates
the same energy every row and every frame. 1/50 and 1/100 on 50 Hz, 1/60 and 1/120 on 60 Hz — which
is precisely the ladder ADR-0005 defines. Cheap drivers skimp on the smoothing that suppresses the
ripple, so its depth varies enormously between one bulb and the next; IEEE 1789 puts the "low risk"
bar at about 8 % modulation at these frequencies, and inexpensive domestic LEDs frequently miss it.

**PWM dimming (hundreds of Hz to tens of kHz).** Nearly every dimmable LED sets brightness by
switching fully on and off at a rate the eye integrates and a sensor samples. The rate is a property
of the light itself, not of the grid: around 400 Hz at the cheap end, ~1.2 kHz mid-range, 19 kHz and
up where high-frequency dimming is designed in. Rolling shutter makes it worse — each sensor row
samples a different phase, so the artefact is bands crawling through the frame rather than a
whole-frame pulse.

**The consequence for [#37](https://github.com/davideme/scenaristo-camera/issues/37) is the honest
one:** our shutter ladder cannot fix PWM in general. It can only fix a PWM rate that happens to be
harmonically related to 100 or 120 Hz. A 400 Hz source bands at every rung; a 19 kHz source is clean
at every rung, because each exposure spans hundreds of cycles. There is no shutter this app can
choose that solves an arbitrary rate, and pretending otherwise would send the user hunting through a
control that cannot help.

What *does* solve it is at the light: take the dimmer to 100 %, since many sources stop modulating at
full, or light with something that dims at high frequency. That makes #37 a **detection and copy**
problem rather than an exposure-control one — tell the user what is causing it and what to do — which
is a materially different piece of work from the one the issue title implies, and worth deciding
before anyone starts on it.

---

## 7. Where this leaves us

Take it as given that three of the five variables are handled — that between the app's defaults and
a user who acts on what the app tells them, **ISO, flicker and white balance are resolved.** It is
worth being exact about what that covers, because the remainder is the interesting part.

| Handled | What it actually fixes | What it leaves untouched |
|---|---|---|
| **ISO, auto at lowest** (§6.3) | The *level*. A correctly exposed face, at the lowest noise the room allows, that does not drift. | Where the light comes from, how hard it is, and how the face sits against everything else in the frame. |
| **Flicker-safe shutter** (§6.2) | Mains ripple, exactly and provably. PWM is resolved by the user, at the light — but only once something tells them that is what they are looking at. | Nothing physical. The telling is the gap. |
| **Locked Kelvin preset** (§6.4) | The overall cast, and its drift mid-take. | The green–magenta axis, mixed sources in one frame, and spectrum. |

**All three are things the app *sets*. Everything left is a thing the app can only *see*.** That is
the shape of the next problem, and it is a different shape from every capture decision made so far:
there is no key to write and no value to lock. The camera cannot move the lamp. It can only measure
the light it is given and say, in one line, what is wrong with it.

### The residue, in the order it is worth having

1. **The subject-to-background relationship.** §2 says the background belongs a stop or two under the
   face; the common domestic failure is the inversion — a window or a lamp behind the speaker. This
   is both the most frequent fault and the cheapest to detect: face-weighted metering already
   locates the face, so face luminance against the rest of the frame is very nearly free. The fix is
   one sentence and the user can act on it in ten seconds.
2. **Key direction: flat, one-sided, or from below.** Luminance across the face box — left against
   right, upper against lower — is a coarse but honest read of where the light is. Underlighting is
   the one with an unambiguous verdict; "flat" is a judgement, and 1:1 is a legitimate choice (§2),
   so it is a weaker candidate than it first looks.
3. **Hardness.** Shadow-edge transfer on a moving face at preview resolution is not something to
   claim we can measure. Honest answer: out of reach, and saying nothing beats guessing.
4. **Mixed colour temperature across one frame.** Detectable in principle as a chroma difference
   between regions, but with tint fixed at 0 we could only ever report it, never correct it.
5. **Spectrum.** Not measurable by a camera at all. Permanently out.

### It is the same shape as the level indicator

This is not a new kind of feature for the product. [#131](https://github.com/davideme/scenaristo-camera/issues/131)
measures mount level and steadiness — facts the app cannot change — and reports them without nagging;
the distance guidance of PRD §6.5 is the older precedent. The house rules already exist: one line
beginning with the action, dismissible where it is advice rather than a fault (UI-11, UI-12).

Where it would ride, if it ships:

- **On the preview tap the exposure loop already reads** ([ADR-0018](../adr/0018-preview-tap-for-metering-and-preview-frames.md)).
  No second stream, no `ImageAnalysis`, nothing that touches the 4K path.
- **At setup time only.** It belongs in the quick mode of [ADR-0022](../adr/0022-two-exposure-responsiveness-modes.md),
  while the user is still moving lamps. [ADR-0023](../adr/0023-lock-exposure-for-the-take.md) freezes
  the warnings during a take, and that is right here rather than a limitation: nobody re-lights mid-take.
- **With the decision logic in `:domain`** ([ADR-0010](../adr/0010-platform-free-domain-defer-kmp.md),
  [ADR-0013](../adr/0013-multiplatform-strategy.md)), fixture-tested, so iOS inherits the same verdicts.
- **Behind an ADR.** New `Warning` values are additive on the protocol, but this introduces shared
  logic both platforms must implement identically, which is on `CLAUDE.md`'s list. It is also new
  product scope, so the shape is Davide's call before any of it is built.

### Three questions for Davide, with a recommendation each

1. **Does the product critique lighting at all, or does it stop at what it can control?** Recommend
   **yes, narrowly** — only conditions with an unambiguous verdict and a one-line fix. That is the
   bar #131 met, and it is the bar that keeps this from becoming a photography tutor.
2. **If yes, what ships first?** Recommend **the backlit subject, alone.** One measurement we can
   already almost make, the most common real failure, and an action the user can take immediately.
   Everything in the list above it can wait for evidence that people want more.
3. **Setup-time only, or during the take too?** Recommend **setup only**, consistent with ADR-0023.

### Two things from earlier stay on the books

- **The flicker-safe shutter is scoped to mains ripple** and should never be described, in code or in
  copy, as fixing "flicker" generally. #37 and [#47](https://github.com/davideme/scenaristo-camera/issues/47)
  are different mechanisms and must not be closed by the same evidence. Under the framing above, #37
  *is* the "telling the user" gap, which is a useful thing to know before anyone starts it.
- **The 3200 K rung is a tungsten-era anchor**, and the warm domestic light our user is most likely to
  be sitting under is below it at 2700 K — worth a look during #24's grey-card session, while the
  equipment is already set up.

**What desk research cannot settle:** every number here about *our* camera is arithmetic from the
PRD's own defaults, not measurement. The grey-card curve (#24), the ISO loop's behaviour (#25), and
whether real domestic lighting bands on the reference Pixel 10 (#37, #47) are device work, and per
[ADR-0017](../adr/0017-phase-0-verification-matrix.md) their answers will be facts about one handset.
Whether a face-versus-frame luminance read is reliable enough to warn on is the same kind of
question, and would need the same kind of session.

---

## Sources

Three-point lighting and ratios:
[StudioBinder](https://www.studiobinder.com/blog/three-point-lighting-setup/),
[Videomaker](https://www.videomaker.com/how-to/lighting/lighting-design/three-point-lighting/),
[StreamYard](https://streamyard.com/blog/how-to-set-up-a-three-point-lighting-system-key-fill-backlighting).

Background separation and level:
[CineD, 8 rules for interview lighting](https://www.cined.com/8-cinematography-tips-making-interview/),
[MACCAM, how to light an interview](https://www.maccam.tv/blogs/lighting-guides/how-to-light-an-interview),
[Wikipedia, background light](https://en.wikipedia.org/wiki/Background_light).

Portrait patterns, and on-axis light:
[Fstoppers](https://fstoppers.com/lighting/getting-started-portrait-lighting-4-classic-patterns-explained-901256),
[Digital Photography School](https://digital-photography-school.com/6-portrait-lighting-patterns-every-photographer-should-know/),
[PPA](https://www.ppa.com/ppmag/articles/9-types-of-portrait-lighting),
[Alan Spicer on flat frontal light](https://alanspicer.com/best-youtube-lighting-ring-light-vs-softbox-vs-led-panel-real-trade-offs/).

Light quality, source size and falloff:
[ASC, "Light Quality 101"](https://theasc.com/post/shot-craft/light-quality-101/),
[ASC, "Revisiting — and Updating — Inverse-Square Law"](https://theasc.com/article/revisiting-and-updating-inverse-square-law/),
[Wikipedia, hard and soft light](https://en.wikipedia.org/wiki/Hard_and_soft_light),
[Neil Oseman](https://neiloseman.com/inverse-square-law/).

Illuminance levels:
[Wikipedia, illuminance](https://en.wikipedia.org/wiki/Illuminance),
[Engineering Toolbox recommended light levels](https://www.engineeringtoolbox.com/light-level-rooms-d_708.html).

Colour temperature, tint and correction:
[Videomaker, colour temperature](https://www.videomaker.com/article/c13/14939-color-temperature-for-video/),
[Videomaker, correcting with gels](https://www.videomaker.com/article/c13/7444-light-source-color-correcting-with-gels/),
[FilmDaft, gels](https://filmdaft.com/what-are-gels-in-lighting/).

Spectrum and colour-rendering metrics:
[Yuji, CRI / TM-30 / TLCI / CQS](https://store.yujiintl.com/blogs/all-about-led-technology/whats-cri-ies-tm-30-tlci-cqs-and-why-is-it-important-part-1),
[UPRtek, TLCI / SSI / TLMF](https://www.uprtek.com/en/blogs/what-is-tlci-ssi-and-tlmf),
[Ulanzi, SSI vs TM-30](https://www.ulanzi.com/blogs/knowledges/ssi-tm-30-color-fidelity-standards-explained).

Flicker and PWM:
[ENTTEC, why LEDs flicker on camera](https://support.enttec.com/pixel/pixel-general-knowledge/why-do-leds-flicker-on-camera),
[Waveform Lighting, flicker-free dimming](https://www.waveformlighting.com/film-photography/an-introduction-to-flicker-free-led-strip-dimming),
[Avnet Abacus, LED flicker](https://my.avnet.com/abacus/resources/article/what-is-led-flicker-and-how-to-prevent-it/),
[Waveform Lighting, diagnosing flicker](https://www.waveformlighting.com/human-centric/diagnosing-and-resolving-led-flicker-issues).
