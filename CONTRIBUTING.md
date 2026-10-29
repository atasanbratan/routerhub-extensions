# Contributing to RouterHub Extensions

Thank you for helping more routers work with RouterHub. Extensions are small, self-contained and easy to review,
which makes this a good place to start contributing.

## Ways to help

- **Add a router.** Write a new extension (the walkthrough below).
- **Test on hardware.** The OpenWrt extension has only been unit-tested. A report of what worked and what did not,
  with anything personal removed, is a real contribution.
- **Extend an extension.** The TP-Link extension has a [list of pages still to do](README.md#help-wanted).
- **Ask for support** for a router you cannot write code for, with a
  [router support request](https://github.com/atasanbratan/routerhub-extensions/issues/new?template=router_support.yml).
- **Improve the docs**, or help others in [Discussions](https://github.com/atasanbratan/routerhub-extensions/discussions).

Look for the [`good first issue`](https://github.com/atasanbratan/routerhub-extensions/labels/good%20first%20issue)
and [`help wanted`](https://github.com/atasanbratan/routerhub-extensions/labels/help%20wanted) labels. For anything
bigger than a fix, please open an issue first. Security problems go to [SECURITY.md](SECURITY.md).

## Setting up

You need JDK 17+ and the Android SDK (platform 36 and build-tools 36); Android Studio brings both.

```bash
git clone https://github.com/<you>/routerhub-extensions.git
cd routerhub-extensions
./gradlew testDebugUnitTest assembleDebug
```

If Gradle cannot find the SDK, create `local.properties` with `sdk.dir=/path/to/Android/Sdk`. The file is
git-ignored. The extensions compile against `router-api`, which Gradle downloads from JitPack. To work on
`router-api` too, check the [app repository](https://github.com/atasanbratan/routerhub) out next to this one and add
`-Prouterhub.sdk=../routerhub` to your Gradle commands.

You will also want the [RouterHub app](https://github.com/atasanbratan/routerhub) on a device or emulator to try your
extension. The Demo extension is a handy way to see how the app behaves with no router at all.

## Adding an extension

1. **Copy `extensions/demo`** to `extensions/<vendor>-<family>` (lowercase, hyphens) and add it to
   `settings.gradle.kts`.
2. **Rename everything that identifies it:**
   - the package, `namespace` and `applicationId`, which must match (`dev.routerhub.extension.<vendor>`);
   - the manifest `label` ("RouterHub: Vendor Family") and the entry class in its meta-data;
   - the factory `id` (`<vendor>.<family>`, plus a protocol suffix when one router has several factories).
     **Ids are permanent**: saved routers refer to them.
3. **Replace the fake router** with real requests, and declare only the capabilities you implement.
4. **Write the tests** (see the standards below).
5. **Add a row** to the table in the README and, if there is more to say, a page under `docs/`.
6. **Open a pull request** and fill in the template.

The [guide to writing an extension](https://github.com/atasanbratan/routerhub/blob/main/docs/writing-an-extension.md)
explains the manifest, the contract and the loading rules.

## Standards

**Safety.** A wrong setting can lock someone out of their own network.

- Validate everything before sending anything, so a bad value never leaves a page half changed.
- Never replay a write. If a request may have applied, report it, and let the app read the page again.
- Keep secrets write-only: never return a stored password to the app, never log one, never put one in `toString()`.
- Prefer safeguards to surprises: refuse changes that lock the user out, expose the router or leave a network
  open, and say why in the message.
- If the router reports a refusal as a normal page, detect it and surface the reason.
- Build commands for a shell from validated values only.

**Tests.**

- Keep router-specific parsing in **pure functions** and unit-test them against captured pages or command output.
- For code that sends requests, put an interface in front of the transport and use a fake that records what was
  requested, so you can pin the exact request a "Save" button would send.
- Tests that need a real router are **opt-in** (guarded by environment variables), must restore everything they
  change, and must never print secrets. `TpLinkLiveWriteTest` is a worked example.

**No real data in the repository.** Captures and fixtures must not contain real SSIDs, passwords, serial numbers,
MAC addresses, hostnames or public IP addresses. Use locally administered MACs (`02:00:00:00:00:xx`) and the
documentation address ranges (`192.0.2.0/24`, `198.51.100.0/24`, `203.0.113.0/24`). Scrub screenshots too.

**Dependencies.** The platform (`router-api`, the Kotlin stdlib, coroutines and OkHttp) is `compileOnly`: the app
provides it. Anything else you bundle must be under a license compatible with Apache-2.0 (Apache, MIT, BSD).
Do not turn minification on: the app finds your entry class by name.

## The pull request

- Say **which router and firmware** you tested on and what you verified, or say it is untested. Both are fine, and
  honesty is what matters.
- Keep it focused: one extension or one change per pull request.
- CI builds every extension and runs the unit tests. Please run `./gradlew testDebugUnitTest assembleDebug` first.
- Write commit messages in the imperative mood ("Add the QoS page"), with the reason in the body when it is not obvious.

## Licensing

Everything here is under the [Apache License 2.0](LICENSE). By contributing you agree that your contribution is
licensed under the same terms (section 5 of the license).

## Code of Conduct

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
