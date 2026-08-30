# enginehost Twine plugin

This Android plugin runs a compiled Twine 2 HTML story directly from the live
game directory. The story carries its selected story-format runtime; this
repository does not redistribute Twine or story-format code.

Network access is disabled by default. Supported options are `allowNetwork`,
`javaScript`, `domStorage`, `database`, and
`mediaPlaybackRequiresGesture`. The selected entry and subsequent local
navigation are confined to the supplied game directory.

The first compatibility branch supports compiled stories authored by Twine
2.0 through 2.12.0 under engineContext `compiled-html`.

The Enginehost wrapper is MIT-licensed. Compiled stories and their embedded
story formats retain their own licenses and are not redistributed here.
