# Enginehost web runtime

One Enginehost plugin for the engines that are a web page:

- **HTML games**, Twine stories included (SugarCube, Harlowe, and the rest).
- **RPG Maker MV and MZ** games deployed for the web or for Windows (the
  `www` folder is all the runtime needs).
- **Flash and Adobe AIR** applications, played with [Ruffle](https://ruffle.rs).

The game is served to Android's WebView from its own folder over a private
`https` origin, so pages behave the way a browser expects (fetch, WebAssembly,
seekable media) while staying confined to the game folder. Network access is
off unless the game's config turns it on.

## Saves live on disk

A WebView normally keeps `localStorage` in the app's private data, where it
cannot be backed up or moved, and every page it opens shares one store, so two
games would overwrite each other's saves. This runtime replaces the page's
`localStorage` before its first script runs with a store backed by
`localStorage.json` in the save folder Enginehost chose for the game. Twine
stories, RPG Maker MV, RPG Maker MZ (through localforage) and Ruffle's local
shared objects all save through it. Copy the folder and the saves come with it.

Enginehost names that folder from what the engine itself would use: a Twine
story's title, or the game's folder name for RPG Maker and AIR. The name is
written into the game's `enginehost.json` as `saveFolder`.

## Audio

RPG Maker MV asks a mobile browser for `.m4a` audio and a Windows deploy
ships only `.ogg`. When a requested audio file does not exist and its sibling
in the other format does, the sibling is served.

## Options

`allowNetwork`, `entryPoint`, `javaScript`, `mediaPlaybackRequiresGesture`,
`userAgent`, `webContentsDebugging`; see `enginehost/bundle-metadata.json`.

## Releases

GitHub Releases form the catalog Enginehost reads. Every build is signed;
`enginehost-public-key.json` is the repository key Enginehost pins before
accepting a bundle. Builds publish on the unstable channel on every push, and
are promoted to testing and stable by hand once they have run real games.

The wrapper is MIT-licensed. Ruffle is redistributed as built by the Ruffle
project under its MIT/Apache-2.0 licence. Games and the engines they embed
are not redistributed.
