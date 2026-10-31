# Changelog

Notable changes to OKOK Libre. This project follows [Semantic Versioning](https://semver.org).

## Unreleased

## 2.0.0 - 2026-10-31

### Added

- Minimal redesign of every screen, with dark mode and a new logo.
- The app takes on the active person's colour.
- Answers to three questions after each weigh-in: muscle, pace and health.
- A statistical trend model, goal forecasts with a date range, and hydration checks on readings.
- Body composition map, phases, energy balance, weekly rhythm and consistency.
- Per-person goals and reminders.
- Moving a reading to another person.
- One-file backup and restore.
- Undo after deleting readings.

### Changed

- Reports and records are merged into one reading screen.
- Spreadsheet imports match each person by name, birthday and body details instead of a stored id.
- Android cloud backup is off, so data leaves the phone only as a file you save.
- Camera and media permissions are gone. Profile photos use the system photo picker.

### Fixed

- Importing a people spreadsheet could delete that person's history.
- Readings without body composition data skewed insights.
- Bluetooth permission was never requested on the weigh-in screen.
- Scales couldn't be found on Android 11 and older.

### Removed

- Custom date ranges in Trends, theme colour presets, wallpaper colours and home card reordering.

## 1.0.0 - 2026-10-08

First version: offline weighing, body composition reports, history, goals and reminders.
