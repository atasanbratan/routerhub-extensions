# TP-Link ADSL2+ (TD-W8960N)

The extension in [`extensions/tplink-adsl`](../extensions/tplink-adsl) manages TP-Link's Broadcom-based ADSL2+
routers. It was developed and verified against a **TD-W8960N V7** running firmware 1.1.0 Build 171106. Other
Broadcom-era models probably share the same pages, but add a model only after checking it on real hardware.

## Two ways in

| Factory | Protocol | What it does |
|---|---|---|
| `tplink.adsl.td-w8960n` | Web | Status, DSL line stats, connected clients, reboot and all the settings pages below |
| `tplink.adsl.td-w8960n.telnet` | Telnet | Status, DSL stats, reboot and a Wi-Fi switch, through the router's restricted CLI (`xdslctl`, `wl`, `route`, `ifconfig`, `swversion`) |

Telnet is unencrypted and has no host identity to pin, so prefer the web UI. The router allows **one admin
session at a time** (telnet included), so a second login is reported as "busy".

## Settings pages (web)

| Page | Editable |
|---|---|
| Wi-Fi | on/off, name (SSID), hide SSID, client isolation, maximum clients |
| Wi-Fi security | WPA-PSK / WPA2-PSK / mixed, encryption, password, key renewal interval |
| Guest network | on/off, name, open or WPA-PSK, password, home-network access, isolation |
| MAC filter | off / allow only / block, and the list of devices |
| Wi-Fi schedule | on/off only; the weekly pattern is summarised and stays on the router's own page |
| Advanced radio | view only (see below) |
| DHCP server | on/off, address range, lease time; the router's own address is read-only |
| DHCP reservations | list of MAC address to IP address reservations |
| Port forwarding | list of rules: name, protocol, external and internal ports, server address |
| Static routes | list of IPv4 routes: network, gateway, interface, metric |
| Website filter | off / allow only / block, the websites, and the devices it applies to |
| Dynamic DNS | list of accounts: hostname, user, provider, interface, password (write-only) |
| DMZ host | address; empty turns it off |
| UPnP | on/off |
| Date and time | NTP on/off, up to five NTP servers, time zone (manual clock and daylight saving stay on the router's page) |
| System log | the router's messages, newest first (read-only) |
| Log settings | logging on/off, record and display levels, local or remote log server |
| Remote access | web admin page, Telnet and ping from the WAN side, per interface |
| SNMP | agent on/off, system name, contact, location, trap address, community strings (write-only) |
| TR-069 | view-only: provider contact on/off, interval and server; credentials are never read |
| Router password | new login password; the app updates its saved copy |

## How it works

The firmware has no API: its pages are static HTML whose current values sit in inline JavaScript, and saving
means requesting a URL that the page's own script assembles. So every page is a `SettingsPageSpec` that does
two things, both pure functions that are unit-tested against placeholder pages:

- `parse(html)` turns the page into typed fields;
- `buildRequest(...)` or `apply(...)` reproduces, byte for byte, the URL the page's **Save** button would
  request, including its anti-CSRF `sessionKey`.

List pages differ in how they edit: adding and removing takes one request each, every request needs a fresh
`sessionKey` from the page it belongs to, and each page joins its removal list its own way. The extension
receives the **complete** wanted list from the app, diffs it against the router and issues the router's own add
and remove requests.

## Things learned the hard way

- **The router reports a refusal as an ordinary page.** A rejected change comes back as HTTP 200 with a title
  ending in "Error" ("Url Filter Entry Add Error", the reason underneath). Without a check, a refused save
  looks identical to a successful one. Every request goes through `CheckedPageClient`, which turns such a
  page into a `CommandFailedException` carrying the router's reason (the path only, never the query, which
  can hold a password). Found on real hardware: adding a website while the filter was off was refused
  silently. The router wants the filter on first, so the extension switches it on before adding sites and
  refuses up front a change that adds sites while leaving the filter off.
- **The security page follows a server-side "selected SSID".** Opening the guest page switches it to the guest
  network, after which the router's own Wi-Fi security page shows the guest's values while its Save still
  targets the main network. The extension loads that page through `wlsecrefresh.wl?wlRefresh=0&wlSsidIdx=0`,
  refuses to edit anything not marked as the main network, and restores the selection after using the guest page.
- **Saving a wireless page restarts the radio** for a few seconds; the confirmation says so.
- **Advanced radio is view only.** Its Save sends about 40 values whose final state comes from a dozen coupled
  scripts. It stays read-only until it can be verified with careful live saves.
- **Switching a protected Wi-Fi to open is not offered.** An open network can be shown, and upgraded to WPA, but
  the extension never creates one.
- **Every list page does its own thing.** Removal lists are joined with `,`, `, ` or a space depending on the
  page, a page's `sessionKey` belongs to that page, and routes take `version|id`. Each is copied from the page's
  own script and pinned by a test. The row layouts of port forwarding, DHCP reservations, static routes, the MAC
  filter, the website filter and Dynamic DNS were confirmed against real entries on hardware.
- **The time page has its own quirks**, reproduced exactly: time-zone offsets keep their `+` sign raw in the
  query, the zone at index 15 is sent with an empty offset, `&` in a zone name becomes `_and_bit_`, and the
  zone list is keyed by the router's own array index. The page is shown read-only while daylight saving or a
  first-setup flow is active.

## Safeguards

- "Allow only" modes with an empty list (which would block every device or website) are refused.
- A port-80 or port-23 forward that the router's own page would silently rewrite to 8080 or 2323 is refused.
- **Remote access shows what is exposed**, not only what can be changed. Closing access is always allowed;
  opening the web page or Telnet is refused while the router password is a well-known default.
- SNMP cannot be switched on while the community strings are the well-known `public` / `private`; turning it off
  is always allowed. Community strings and Dynamic DNS passwords are write-only.
- The Wi-Fi schedule is a switch, not a grid editor: nothing on the page says whether a selected cell means Wi-Fi
  on or off, and an empty pattern or an unset router clock could keep the radio off for good, so enabling it is
  refused in both cases.
- Everything is validated before the first request, so a bad row never leaves a list half changed.

## Testing

Unit tests never touch a router; they run against placeholder pages and a fake `PageClient` that records the
requests:

```bash
./gradlew :extensions:tplink-adsl:testDebugUnitTest
```

Two opt-in suites talk to a real router:

- `TpLinkLiveTest` is read-only: it logs in and reads every page.
- `TpLinkLiveWriteTest` **changes the router**. Each test edits a setting, reads it back, restores the original in
  a `finally` block and reads that back too. It uses obviously fake data (locally administered MAC addresses, an
  unused LAN address), holds the Wi-Fi password in memory only to restore it, and never prints it. It also reboots
  the router, and it **leaves remote access closed** on purpose, because reopening it with a default password is
  exactly what the extension refuses.

```bash
ROUTERHUB_LIVE_HOST=192.168.1.1 ROUTERHUB_LIVE_USER=admin ROUTERHUB_LIVE_PASS=... ROUTERHUB_LIVE_WRITE=1 \
  ./gradlew :extensions:tplink-adsl:cleanTestDebugUnitTest :extensions:tplink-adsl:testDebugUnitTest \
  --tests '*TpLinkLiveWriteTest.upnp' -i
```

Without the variables every live test is skipped. Run one test at a time against a router you can reset, and
never over a Wi-Fi link that the test itself restarts. Environment variables are not Gradle task inputs, so a
repeated identical run is skipped as `UP-TO-DATE` and does nothing: that is why `cleanTestDebugUnitTest` comes first.

## Adding another model

1. Add it to `TpLinkModel`, and capture its pages with every personal value replaced by a placeholder.
2. Compare the page layouts with the TD-W8960N fixtures. Where a page differs, add a spec variant and a fixture.
3. Run the read-only live test, then the write tests one at a time on a device you can reset, and say in the pull
   request which firmware you used.
