# Design

Minimal, by the owner's instruction. There is no concept or theme: ink on a plain ground, one typeface, whitespace instead of containers. If a decision isn't covered here, take the plainer option.

## Colour

Colour means one of two things.

- **The person.** `accent` is the active profile's colour. It tints the chart line, primary buttons and selection, so switching profile re-tints the whole app and whose data is on screen is obvious without a label. People choose from six colours (`personColor`), tuned for at least 4.5:1 in both themes.
- **Attention.** `attention` means something needs a look. Nothing else is orange. A value in range gets no colour at all.

Everything else is `ink`, `muted` and `faint` on `ground` and `raised`, with `hairline` for dividers. Tissues keep one colour everywhere: muscle is the accent, fat is ink, bone is faint. All tokens live in `ui/theme/Theme.kt`. Don't add raw colours to screens.

Light or dark follows the system: mornings in a bright bathroom, evenings on the sofa.

## Type

Geist, bundled as a variable font, with tabular figures on every style so numbers line up. The big reading is `displayLarge`, light weight with tight tracking. Screen titles are `headlineLarge`, section titles `headlineSmall`, rows `bodyLarge`.

## Layout

- `Gutter` (20dp) is the page margin. `SectionGap` (32dp) separates sections.
- Sections are a heading and content on the ground. No cards, no shadows, no icon tiles, and no eyebrow labels above headings.
- Lists use `ListRow`, at least 56dp tall, with figures flush right. Hairlines divide groups.
- Destructive actions use `DestructiveAction`, alone and at the bottom of the page.

## Motion

Short and functional. Tabs fade through, screens slide a twelfth of their width and fade (260ms), charts draw in once (650ms), digits roll when a stored value changes, and the accent cross-fades on a profile switch (240ms). Live scale frames just update in place. Compose honours the system's animation setting.

## Charts

`ui/design/Charts.kt`, hand-drawn on Canvas with no chart library.

- The line is the estimated true level and the dots are what the scale read, so noise is visible rather than hidden. A faint band shows the 80% range.
- Label directly: first and last dates, high and low values. No legends unless a chart has two series.
- Rulers show a value's position against its standard range. Position carries the meaning, colour only flags concern.
- Every chart has a `contentDescription`.

## Logo

A ring standing on a bar: a person on a scale, and the O of OKOK. Sources are in `docs/brand/`. `logo-mark.svg` and `logo-wordmark.svg` use `currentColor`; `logo-icon.svg` is the launcher tile. The Android vectors in `res/drawable` use the same paths.

## Copy

Sentence case and plain words. Name the problem and the way out. Say how sure the app is, and stay quiet when the data can't support a claim. Never show a protocol term outside Diagnostics.
