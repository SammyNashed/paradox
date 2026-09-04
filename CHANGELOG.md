# Changelog

## 1.0.1 — the Warden actually eats it

- **Fixed: the Warden never took the Remnant.** The Warden check sat at the end of the kin-and-
  predators pass, behind an early return that fired whenever no Evoker was in sight — so in a Deep
  Dark, with no evoker for hundreds of blocks, the code that eats the soul never ran. The Remnant
  simply read the Warden as the nearest monster and charged it, and the Warden, with no reason to
  target a vex, ignored it. The check now runs first, ahead of everything else it might be doing.
- The Warden is looked for around the **Remnant** as well as the player, and the nearest one wins —
  the Remnant is usually the one out in front, and it is the Remnant the Warden wants.
- The Remnant drops its target and any bind pressure the moment a Warden notices it: it no longer
  tries to fight the thing that is eating it.
- The Warden now roars when it notices, turns to face the Remnant for the whole drain, and drops
  the player as a target while it feeds.

## 1.0.0 — first public release

Everything. Paradox suspends your death, gives you sixty seconds as a spirit to fix what killed
you, then winds the world back so you can live those seconds again.

- **The loop** — death suspended, inventory held in escrow, ghost phase, live judgement every tick
- **The replay** — a player-model replica wearing your own skin re-enacts your last seconds on a loop
- **The rewind** — a block journal walks the world backwards twelve seconds and replays your ghost's
  work on schedule while you live it
- **The Remnant** — a rare pale spirit that takes three deaths for you, with its own letter, its own
  dread, and its own opinions about your safety
- **Kin** — a truce with Vexes, Allays that give a life back, Evokers that cage your Remnant, and a
  Warden that eats it
- Mod Menu / Cloth Config settings screen, and a `/paradox test` dry run that cannot kill you
