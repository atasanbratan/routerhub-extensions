## What and why

<!-- Which extension, and what does this change? Link the issue if there is one. -->

## Tested on

<!-- The router model and firmware you tried it on and what you verified. "Not tested on hardware" is a fine answer. -->

## Checklist

- [ ] `./gradlew testDebugUnitTest assembleDebug` passes
- [ ] New extension: copied from `extensions/demo`, with its own package, `applicationId` and a permanent factory `id`
- [ ] Everything is validated before the first request, and a write is never replayed
- [ ] Secrets are write-only and never logged
- [ ] Parsing is in pure functions with unit tests; any real-hardware tests are opt-in and restore what they change
- [ ] No real data in code, fixtures, tests or screenshots (SSIDs, passwords, MAC addresses, serial numbers, public IPs)
- [ ] README table and docs updated
