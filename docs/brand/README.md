# The mark

A terminal prompt and the trail it leaves behind: the solid chevron is now, the two behind it are what already happened. Terracotta `#D97757` — the accent claude-history already wore — with the trail cut into it in a warm near-black `#2B2320`, warm because a cool black on terracotta goes muddy.

**The tile IS the mark**, not a glyph dropped on a background. That is what a launcher installs and what fills a home screen beside twenty other icons, and it is the thing three rounds of this design got wrong before the sizes that matter were put in front of the sizes that are easy to check.

| File | What reads it |
| --- | --- |
| `icon-a.svg` | the source of the drawing, in the one format that can be opened anywhere |
| `icon-b.svg` | the alternative that was not chosen: the same mark on a dark tile, with the fade carried by temperature instead of opacity. Nothing points at it |

## The one duplicate, and why it exists

`app/src/main/res/drawable/ic_launcher_foreground.xml` is `icon-a.svg` again, as a VectorDrawable, because **Android cannot read SVG**. Both are the same 48-unit canvas and the same path data, so a change to one is a change to both — the AVD says so in its own comment, and this is the only place in either repository where one drawing is stored twice.

Two things about that transcription are not obvious:

- **The group scale is 1.65, not 1.5.** At 1.65 the furthest ink sits 30.9 from the centre of the 48-unit canvas, and Android's guaranteed-visible circle has a radius of 33 once scaled — so the mark fills as much of the tile as it can with the mask still unable to reach it.
- **`ic_notification.xml` is the trail with no tile**, at half the coordinates on the 24dp canvas a status-bar icon must use. Android keeps only the alpha up there and tints what is left, so a tile would arrive as a white rectangle with smudges in it. The fade survives: the two chevrons behind come through as a lighter tint.

## A trap

**A double hyphen inside an XML comment is illegal.** The build says only `Failed to parse XML file` and gives no line number.
