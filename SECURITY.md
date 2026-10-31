# Security policy

## Supported versions

Security fixes go into the latest release.

## Reporting a problem

Please don't open a public issue for a security problem. Use [private vulnerability reporting](https://github.com/JYunth/okok-libre/security/advisories/new) instead, with steps to reproduce and the app version you tested.

You should get a reply within a week. This is a volunteer project, so a fix may take longer.

## What counts

The app has no server or account, so the main concerns are:

- data leaving the phone without the person asking
- one person's data reaching another profile in a way that exposes it
- exported backups or spreadsheets containing more than they should
- anything that weakens the app's lack of network access

Exported backup files aren't encrypted. That's documented behaviour, so keep them somewhere private.

Release APKs are signed by the project. Only install them from this repository's releases page.
