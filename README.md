# Enginehost HTML runtime

The Enginehost plugin for HTML games, Twine stories included (SugarCube,
Harlowe, and the rest).

The game is served to Android's WebView from its own folder over a private
`https` origin, so the page behaves the way a browser expects (fetch,
seekable media) while staying confined to the game folder. Network access is
off unless the game's config turns it on.

## Saves live on disk

A WebView normally keeps `localStorage` in the app's private data, where it
cannot be backed up or moved, and every page it opens shares one store, so two
games would overwrite each other's saves. This runtime replaces the page's
`localStorage` before its first script runs with a store backed by
`localStorage.json` in the save folder Enginehost chose for the game. Copy the
folder and the saves come with it.

Enginehost names that folder from the story's own title, or the game's folder
name when the page states none, and writes it into the game's
`enginehost.json` as `saveFolder`.

## Options

`allowNetwork`, `entryPoint`, `javaScript`, `mediaPlaybackRequiresGesture`,
`userAgent`, `webContentsDebugging`; see `enginehost/bundle-metadata.json`.

## Releases

GitHub Releases form the catalog Enginehost reads. Every build is signed;
`enginehost-public-key.json` is the repository key Enginehost pins before
accepting a bundle. Builds publish on the unstable channel on every push, and
are promoted to testing and stable by hand once they have run real games.

The runtime is GPL-3.0 licensed. Games and the story formats they embed are not
redistributed.

## Licence

GPL-3.0, see [LICENSE](LICENSE) and [NOTICE](NOTICE). All code here is ours; the third-party attributions in NOTICE and THIRD_PARTY.md keep their own notices.

## Branches

This core has no upstream engine, so it has no `upstream-sync` (Droidtop/tracker#324, the owner,
2026-11-05). `plugin-core`, the default branch, is everything: the runtime, the build workflows,
the bundle metadata and the line pins. Each `plugin/<line>` is composed from `plugin-core` alone
(its `enginehost/lines/<line>/line.json` has `"upstream": null`) by the plugin-line tool; nothing on
it is written by hand.
