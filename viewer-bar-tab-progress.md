# viewer-bar-tab — progress

Temporary: deleted as the last commit on this branch, right before it is merged.

## The plan

Port to the viewer what WebAppLauncher did to its copy of `ViewerBar` in
`ec746467` ("Merge the page header"):

1. **The bar starts hidden** behind a small tab hanging from the top edge. A tap
   opens it; a sideways drag moves the tab off whatever the page has under it,
   and it stays where it is let go. The bar gains a **hide** button. Nothing of
   it is saved: every entry starts hidden. With no page to show (connecting,
   refused, unreachable, disabled) the bar is always shown, because **Servers**
   lives there. While hidden, the page clears the status bar itself, and the
   load line is drawn at its top edge so a fresh WebView still has something in
   the white gap.
2. **Never pin the viewport while a page loads.** `onPageFinished` pins the
   scale for 50 ms; in WebAppLauncher, on the DT50, that killed the pinch for
   good after a reload at any zoom but 100 %. Split into `pin = true` (the bar,
   rotation) and `pin = false` (a load).
3. **Rewrite the viewport when the view's width changes.** The Activity handles
   rotation itself, so the viewport keeps the other axis's scale.

Points 2 and 3 were inferred from reading the code, so each is reproduced on
the DT50 first and only what reproduces goes in.

All in `ui/ViewerScreen.kt`, plus `ic_chevron_up` / `ic_chevron_down`.

Progressive commits, each compiling: viewport fixes, then the hideable bar,
then the docs. README and the deletion of this file go in the final commit,
after the user has tried it.

## Status

- [x] **Rotation** — reproduced on 1.1.1: desktop mode at 150 %, turned to
  landscape, kept portrait's scale (~0.53) and drew the page smaller than at
  100 %. Phone mode does not show it (Chromium refits on its own). Fixed with a
  layout-change listener that rewrites the viewport when the width changes.
- [x] **Pin while loading** — **not reproduced, so dropped** from the branch.
  Built the pre-change code, zoomed to 110 % and to 150 %, reloaded, and
  pinched over DevTools: `visualViewport.scale` went 1.5 → 2.25 → 3.37, and
  1.10 → 1.65. The pinch survives a reload here.
- [x] **Hideable bar and tab** — measured on the DT50, release-signed debug
  build installed over 1.1.1: hidden on entry with the load line over the
  page's top edge, tab tap shows the bar, the chevron hides it, the drag stops
  at the edge and the position survives hide/show, page clear of status and
  navigation bars in portrait, landscape and dark. Not seen: the no-page case
  (the server answered every time).
- [x] CLAUDE.md verifying rows — the bar, turning the phone, the pinch over
  DevTools and its focus quirk, the progress line while hidden.
