# Changelog

All notable changes to this plugin are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Unstable and testing stay moving pointers to whichever build was last
published to each; every build they ever point at also gets a permanent
release of its own (`<bundle>-build.<run>`), which is never overwritten.

## [Unreleased]

## [1.0.7]

### Changed
- HTML and Twine games always run locked down: files only through Enginehost's broker, no
  sockets, no exec. The page itself runs in WebView's own renderer, which Android isolates
  (`isolated_app`). It passed the rig on the AVD with a Play Store WebView and TrichromeLibrary
  (Droidtop/tracker#550), on Enginehost from ef4428d. The "Run unsandboxed?" sheet no longer
  appears for this core.

### Added

- A link a story opens (a link, a location change or window.open) is offered by Enginehost: open in
  the app it belongs to, in a browser, copy, or not. It used to be dropped without a word.
- A permanent, never-overwritten release for every bundle build published
  to any channel, so a version that was once installable stays that way in
  the release history even after the next push moves the channel pointers.

## [1.0.1] - 2026-09-29

### Changed

- Declared version raised to 1.0.1 (stable channel) so the next publish is
  1.0.1-1, which orders above every legacy `X.Y.<run>` build already published
  (Enginehost reads those as `X.Y.0-<run>`). No republish; the change takes
  effect on the next build.

## [1.0.0] - 2026-09-28

### Changed

- Promoted to the stable channel at version 1.0.0. The maintainer's decision of
  2026-09-28 promotes every plugin line that has already run a real game
  from testing, without the usual promotion checks. CI stamps its run
  number as the third component of the published version (1.0.<run>), so
  every build stays newer than the one before it.

## [0.1] - 2026-09-04

This is the plugin's first version-history entry: there was no changelog
before this release, so this section summarizes what already exists.

### Added

- Runs a game's own HTML/JS deck (including Twine exports) confined to its
  own folder, served to Android's WebView over a private origin.
- Android-compatible save I/O for decks that expect a browser's storage.
- Signed bundle releases on the unstable and testing channels, verified
  file by file against this repository's pinned key at install time.
