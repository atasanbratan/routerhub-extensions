# TP-Link Wi-Fi routers (classic web interface)

The extension in [`extensions/tplink-wr`](../extensions/tplink-wr) manages TP-Link routers with the
classic web interface: pages under `/userRpm/`, `Server: Router Webserver`, and a session code in the
address after logging in (`http://tplinkwifi.net/ABCDEFGHIJKLMNOP/userRpm/Index.htm`).

## Tested

| Router | Firmware | What was tried |
| --- | --- | --- |
| TL-WR940N v4/v5 | 3.17.1 Build 170207 | Status, connected clients, reboot, every settings page and blocking, each changed and put back |

Siblings with the same interface, such as the TL-WR840N and TL-WR841N, very likely work too. They are
added once someone has tried one: please open a router support request with what you saw.

## What it does

- **Status:** model, firmware, uptime, internet address, gateway and DNS servers, Wi-Fi on or off and
  its name.
- **Connected clients:** every device with a DHCP lease, marked wired or Wi-Fi from the router's Wi-Fi
  station list.
- **Reboot,** and the router screen's **Wi-Fi switch.**
- **Settings pages:**
  - **Wi-Fi:** on or off, name, hidden name, channel (only those the router's region allows), WPA2 or
    WPA/WPA2 with AES, and a new password. It refuses to turn a protected network into an open one;
    security the page doesn't offer, such as WEP, is shown as it is and kept.
  - **DHCP server:** on or off, address range, lease time, gateway, domain and DNS servers. Read-only
    while the router runs DHCP by itself.
  - DHCP reservations, port forwarding (single ports and ranges), DMZ and UPnP.
- **Block and allow from the clients list,** through the router's Wi-Fi MAC filter: the device loses this
  Wi-Fi, not just the internet. Only offered while the filter refuses the devices on its list; under the
  other rule, adding a device would let it in and switching the filter on would shut everyone else out.
  Allowing the last blocked device switches the filter off again.

The Wi-Fi and DHCP server pages were read on the test router; saving them was tried only against a
simulation of its pages, since changing them on that router would cut off its users. Access control and
time schedules are next.

## Things to know

- **Logging in right after logging out** is answered by sending the browser home; the extension simply
  tries again.
- **One login at a time.** Logging in from the app ends a session open in a browser, and the other way
  round. The app logs out when you leave the router, so the browser can log in again.
- **Adding the router:** the address is usually `192.168.0.1` or `tplinkwifi.net`, user `admin`. The
  password is the router's admin password, `admin` on a new router.

## Tests

```bash
./gradlew :extensions:tplink-wr:testDebugUnitTest
```

The live tests read a real router; with `ROUTERHUB_LIVE_WRITE=1` they also change settings and put them
back, using a made-up device (`02:00:00:00:fe:xx`) at the `.250` address. `ROUTERHUB_LIVE_REBOOT=1` adds a
reboot. Without them nothing is changed:

```bash
ROUTERHUB_LIVE_HOST=192.168.0.1 ROUTERHUB_LIVE_PASS=... \
  ./gradlew :extensions:tplink-wr:cleanTestDebugUnitTest :extensions:tplink-wr:testDebugUnitTest --tests '*TpLinkWrLive*'
```
