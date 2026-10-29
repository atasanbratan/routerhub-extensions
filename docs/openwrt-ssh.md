# OpenWrt (SSH)

The extension in [`extensions/openwrt-ssh`](../extensions/openwrt-ssh) manages an OpenWrt router through its
shell. It uses the maintained [JSch fork](https://github.com/mwiede/jsch) (modern key exchange and ciphers),
bundled in the APK.

> **Status: unit-tested, not yet verified on real hardware.** The parsers are tested against captured command
> output, but no maintainer has run it against a real device. If you have an OpenWrt router, a report is one of
> the most useful contributions you can make (see below).

## What it does

| Capability | How |
|---|---|
| Status | `cat /proc/uptime`, `cat /etc/openwrt_release`, `cat /tmp/sysinfo/model`, `ip -4 route get 1.1.1.1` (WAN device and address) and `uci -q show wireless`, in one round trip |
| Connected clients | `cat /proc/net/arp` joined with `cat /tmp/dhcp.leases` |
| Wi-Fi switch | `uci set wireless.<radio>.disabled=<0\|1>`, then `uci commit wireless && wifi reload` |
| Reboot | `(sleep 2; reboot)` detached, so the command exits cleanly before the router goes down |

On login it checks for `/etc/openwrt_release` and fails fast on anything that is not OpenWrt.

## Security

- **SSH host keys are pinned on first use.** The first connection shows the fingerprint for you to confirm;
  a later change is refused and never retried automatically.
- Radio ids are interpolated into a shell command, so they are validated against `[A-Za-z0-9_]+` first.
- Password and private-key authentication are both supported by the contract (`SshAuth`).

## Help us verify it

If you have a device running OpenWrt, please try the extension and
[open a report](https://github.com/atasanbratan/routerhub-extensions/issues/new?template=bug_report.yml) with:

- the OpenWrt version and target (`cat /etc/openwrt_release`) and the device model,
- what the overview screen showed and whether it matched reality,
- whether the client list was complete, and whether the Wi-Fi switch worked,
- anything that looked wrong, with your SSID, MAC addresses and IP addresses removed.

Pull requests that add fixtures from other OpenWrt versions are very welcome too. Settings pages for Wi-Fi (name,
password, channel) are the natural next step: `uci` makes them a good fit for the declarative settings contract.
