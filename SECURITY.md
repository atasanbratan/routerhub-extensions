# Security policy

Extensions run inside the RouterHub app's process and handle router credentials, so security reports are taken
seriously.

## Supported versions

Fixes go into `main`. The project is pre-1.0, so there are no older supported versions.

## Reporting a vulnerability

**Please do not open a public issue.** Report it privately:

1. Open the repository's **Security** tab.
2. Choose **Report a vulnerability** and describe what you found: what it affects, how to reproduce it and what you
   think the impact is.

You will get a first answer within about a week. This is a volunteer project, so that is a best effort, not a
promise. Once a fix is released you will be credited, unless you prefer not to be.

## What counts

- An extension sending credentials, or any data from the router, anywhere other than that router
- Credentials in logs, exception messages or `toString()` output
- Command or request injection, such as unvalidated values reaching a shell command or a router URL
- A safeguard that can be bypassed, for example opening remote administration while the router still has a default
  password
- A write that is replayed, or applied to a different page than the user chose
- A bundled library with a known vulnerability that an extension actually reaches

## What does not

- Flaws in a router's own firmware. Report those to the vendor.
- The fact that an extension has access to the credentials of the routers it manages. That is how it works, and
  the app asks you to trust an extension's signer before loading it.

For the app itself, the loader and the signature checks, see the
[app repository's policy](https://github.com/atasanbratan/routerhub/blob/main/SECURITY.md).
