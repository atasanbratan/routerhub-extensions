# OpenWrt (LuCI)

The extension in [`extensions/openwrt-ubus`](../extensions/openwrt-ubus) manages an OpenWrt router over HTTP, through
the same `/ubus` calls LuCI's own pages use. It needs nothing installed on the router beyond a normal LuCI, and no
hardware-specific code.

## Supported versions

| OpenWrt | State |
| --- | --- |
| 25.12 | Tested in a virtual machine: status, every settings page, reservations, port forwarding, router name |
| 24.10 | Tested on a real router (Xiaomi Mi Router 4C): everything, including Wi-Fi changes, blocking and reservations |
| 23.05 | Tested in a virtual machine, like 25.12 |
| 22.03 | Tested in a virtual machine, like 25.12 |
| 21.02 | Tested in a virtual machine, like 25.12 |
| 19.07 and older | Not supported: LuCI there has no `/ubus` address. Use the [SSH extension](openwrt-ssh.md) |

A virtual machine has no Wi-Fi, so the Wi-Fi pages were tested on the real router only. Images without LuCI need
the [SSH extension](openwrt-ssh.md) too.

Use it instead of the [SSH extension](openwrt-ssh.md) when you want the settings pages.

## Adding the router

Pick **OpenWrt (LuCI)** and enter the address and the login you use for LuCI, normally `root`. Port 80 is the default.

HTTP sends the password to the router unencrypted, over your own network. If you turn on HTTPS, the app needs a
certificate the phone trusts. Routers normally have a self-signed one, which is refused, because this version has no way
to pin it.

## What it does

| Group | Page | Changes |
| --- | --- | --- |
| Wireless | One page per Wi-Fi network | Name, security, password, hidden, client isolation, on or off |
| Wireless | One page per radio | Channel, width, power, country, on or off |
| Network | LAN and DHCP | DHCP server, address range, lease time |
| Network | Internet connection | DHCP, static or PPPoE, DNS servers |
| Network | Interfaces | Shows the state of every interface |
| Network | Address reservations | Fixed addresses for devices |
| Firewall | Port forwarding | IPv4 forwards from WAN to LAN |
| Firewall | Blocked devices | Devices kept off the internet |
| Firewall | Zones and rules | Shows what the firewall allows |
| System | Name and time | Hostname, time zone, time sync |
| System | System log | Shows the latest 200 messages |
| System | Login password | Password of the logged in user |

The home screen also shows status, connected clients and the Wi-Fi switch, and the router can be rebooted.

Tap a client to copy its MAC address, IP address or name, block or allow its internet, or reserve its current address.
Blocking adds a firewall rule that refuses traffic from that MAC address to the WAN, named `RouterHub block`. The device
stays on your network. Connections it already has open can keep working until they end, and a device that changes its
MAC address gets around the block.

Wi-Fi networks that connect the router to another network (client mode) get no page, as a wrong value there would cut
the router's own uplink. The status still recognises such an uplink as the WAN.

## How changes are applied

- **Network, firewall and system pages** use LuCI's apply with rollback. The router keeps the old configuration and
  restores it after 45 seconds unless the app confirms the change. A setting that cuts the phone off undoes itself.
- **Wi-Fi pages and the Wi-Fi switch** commit directly. These changes often end the phone's own connection on purpose,
  so a rollback would undo what you asked for. The page warns about this before saving.
  The router can stop answering for about 20 seconds afterwards, even over a cable.
- **The router's LAN address is read-only.** The app couldn't confirm a change from the new address, so the router would
  always roll it back. Change it in LuCI, which follows the router to its new address.

Wired devices show as an unknown connection type, and come from the DHCP leases, because LuCI's access rules don't let
the app read the router's list of active neighbours. A device that has just left can stay listed until its lease ends.

Stored passwords stay on the router: the Wi-Fi key and the PPPoE password are never read into a page.

## Testing on a router

`UbusLiveTest` runs against a real router when the environment names one. It prints no names, addresses or passwords.

```bash
ROUTERHUB_LIVE_HOST=192.168.1.1 ROUTERHUB_LIVE_USER=root ROUTERHUB_LIVE_PASS=... \
  ./gradlew :extensions:openwrt-ubus:cleanTestDebugUnitTest :extensions:openwrt-ubus:testDebugUnitTest \
  --tests '*UbusLiveTest.reads*' -i
```

`ROUTERHUB_LIVE_PORT` sets another port. Adding `ROUTERHUB_LIVE_WRITE=1` also runs the write tests, which change a port forward, a reservation, the router name
and a Wi-Fi option, then put each one back. The Wi-Fi test restarts the radio.
