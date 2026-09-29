# Changelog

All notable changes to this plugin are documented in this file. The format
follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

Unstable and testing stay moving pointers to whichever build was last
published to each; every build they ever point at also gets a permanent
release of its own (`<bundle>-build.<run>`), which is never overwritten.

## [Unreleased]

### Added

- A permanent, never-overwritten release for every bundle build published
  to any channel, so a version that was once installable stays that way in
  the release history even after the next push moves the channel pointers.

## [1.0.1] - 2026-09-29

### Changed

- Declared version raised to 1.0.1 (testing channel) so the next publish is
  1.0.1-1, which orders above every legacy `X.Y.<run>` build already published
  (Enginehost reads those as `X.Y.0-<run>`). No republish; the change takes
  effect on the next build.

## [0.1] - 2026-09-04

This is the plugin's first version-history entry: there was no changelog
before this release, so this section summarizes what already exists.

### Added

- Runs a game's own HTML/JS deck (including Twine exports) confined to its
  own folder, served to Android's WebView over a private origin.
- Android-compatible save I/O for decks that expect a browser's storage.
- Signed bundle releases on the unstable and testing channels, verified
  file by file against this repository's pinned key at install time.
