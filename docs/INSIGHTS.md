# Insights

How the app turns readings into answers. All of this sits on top of the body-composition metrics and never changes them. Code: `app/okoklibre/insights/`, tests alongside in `src/test`.

## Inputs

- Weight-only readings store `0` in every composition field. Those zeros mean "unknown": they are filtered out of composition analysis and never treated as values (`hasComposition`).
- Several readings in a day count once, as that day's mean, on the phone's local calendar.
- Soft-deleted readings are invisible.

## Trend model (`TrendModel.kt`)

A local-linear-trend Kalman filter with a Rauch–Tung–Striebel smoother, run separately on weight, fat mass, muscle mass and lean mass.

- It handles skipped days properly: uncertainty grows across a gap, which a rolling window can't express.
- Measurement noise comes from each person's own data: the median absolute deviation of day-to-day jumps, clamped to 0.1 to 1.5 kg.
- Rate drift is fixed at 1.5e-5 kg²/day³, so the rate can move about 0.1 kg/week over a fortnight. That averages over roughly two weeks of noise.
- A reading more than 3 standard deviations from the prediction widens its own noise rather than dragging the trend.
- A change is only claimed when it clears both a 90% confidence bar and a minimum size. Endpoint errors are treated as independent, which is conservative.

## Answers (`BodyAnalysis.kt`)

| Question | Method | Stays quiet until |
|---|---|---|
| Am I gaining muscle? | Change in smoothed muscle, fat, lean and weight over 30 days. Recomposition means fat fell while muscle didn't. If weight fell, also reports the share that wasn't fat. | 4 composition readings over 10 days |
| Am I on pace? | Rate from the filter, as % of body weight per week: under 0.1 holding, under 0.5 gentle, up to 1 steady, above that fast. Goal date comes from the rate, with an 80% window. | 5 days over 14 days, and goes quiet again after 28 days without a reading |
| Am I healthy? | Grades from `Grading`, read with polarity: high muscle is not a concern, high visceral fat is. Score levers re-run the real score formula with 1 kg moved. | One reading |

## Reading check

Bioimpedance reads a dry body as fatter. Today's water is compared with the person's own last 30 days. A shift of at least 1 point, or 3 times their usual spread, flags the reading. It needs 5 readings first.

## Other analysis

- **Rhythm:** each day's distance from the trend, averaged by weekday (3 or more of each) and by time of day (4 or more readings), in local time.
- **Phases:** runs of losing, holding or gaining from the smoothed rate, merged until each lasts 10 days.
- **Energy balance:** tissue changed over 30 days at 9,400 kcal/kg fat and 1,816 kcal/kg lean (Hall, *Int J Obes*, 2008), or 7,700 kcal/kg of weight when there is no composition data. Needs a 14-day window.
- **Body map:** fat-mass index against fat-free-mass index, after Hattori's body composition chart (*Am J Hum Biol*, 1997). The two add up to BMI, so diagonals are lines of equal BMI.
- **Consistency:** local Monday-to-Sunday weeks, plus a 26-week grid.

## Attribution (`data/Attribution.kt`)

Built on the ±3 kg match. The active profile wins ties, because the person holding the phone usually picked it.

| Situation | Result |
|---|---|
| Only one profile exists | Saved to them |
| Active person fits, nobody else does | Saved to them |
| Active person fits, so does someone else | Ask, active person first, auto-picked after 10 s |
| Active person doesn't fit, one other does | Saved to that person, who becomes active |
| Active person doesn't fit, several others do | Ask, closest first |
| Nobody fits | Ask, with a guest option |

An active profile with no history counts as fitting. Any reading can be moved afterwards from its reading screen, which recomputes composition for the new owner. If the scale itself settled who it was, that choice is kept.

Spreadsheet imports never trust a role id from another install: each file owner has to fit a local person on sex, height and age, and rows nobody fits are skipped.

All of this is an estimate from a bathroom scale, not medical advice.
