# Contributing

Thanks for helping. Small, focused changes are the easiest to review, and it's worth opening an issue before starting anything large.

## Ground rules

- **No network code.** The app must never gain the `INTERNET` permission, analytics, ads or an account.
- **Keep people's data separate.** Anything belonging to a person must stay with that profile.
- **Don't change the numbers by accident.** Changes to body composition formulas, grading tables or the scale protocols need a clear reason and tests.
- **Say what's true.** New insights need tests and must stay quiet when the data can't support them. Document the method in [`docs/INSIGHTS.md`](docs/INSIGHTS.md).
- **Keep it minimal.** UI changes follow [`DESIGN.md`](DESIGN.md).

## Setup

You need JDK 21 and the Android SDK (platform 36). Run everything from the `app` directory.

```sh
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # needs a device or emulator
```

## Working without a scale

Debug builds include a demo household and a simulated scale. Neither is in release builds. After installing a debug build:

```sh
R="adb shell am broadcast -n app.okoklibre.debug/app.okoklibre.DemoReceiver"

$R --es action seed                                       # add three people with months of readings
$R --es action scale --ef weight 61.9 --ef impedance 505  # the next weigh-in reads this
$R --es action scale-off                                  # go back to the real scale
```

Seeding replaces the data in the debug app.

## Pull requests

1. Branch from `main`.
2. Add or update tests for behaviour changes.
3. Make sure unit tests pass. Run the device tests if you touched the database or profiles.
4. For UI changes, include light and dark screenshots.
5. Add a line to [`CHANGELOG.md`](CHANGELOG.md) under Unreleased.

Database changes need a Room migration, an updated schema in `app/app/schemas` and a test in `MigrationTest`.

Never commit signing keys, `keystore.properties` or real weight data. Use the demo household for logs and screenshots.

By contributing, you agree that your work is licensed under [GPL-3.0](LICENSE).
