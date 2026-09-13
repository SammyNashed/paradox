# Changelog

## 1.1.1 — hazard-specific Remnants, advancements, and a real icon

- **The Remnant dreads, and colours itself, by the exact thing that killed you, not the broad
  family.** Cactus (green), sweet berry bush (dark red), a hot floor (gold), freeze (icy blue), a
  falling stalagmite (blue-grey), a wither rose (ash grey) — each now has its own colour and its
  own dread, and puts itself between you and *that* hazard specifically. Suffocation (a wall
  closing in, or too much company in one block) and an explosion (it now watches for a lit TNT
  fuse) went from no dread reaction at all to a real one.
- **Fixed: TNT and end-crystal deaths were mislabelled `MOB:tnt` / `MOB:end_crystal`.** The dread
  lookup for a `MOB:` origin can only ever search living entities, so it silently found nothing —
  those deaths now correctly fall back to the new plain `EXPLOSION` handling above.
- **A hidden 15-advancement tree**, granted from the exact code path each moment happens in rather
  than any vanilla trigger — first rescue, cutting it to two seconds, meeting and binding a
  Remnant, reading its letter, spending it down to nothing, an Evoker caging it and getting it back,
  a Warden that will never let go, and a challenge-tier capstone for surviving every kind of death
  the mod recognises at least once. *A Small Kindness* (the Allay + amethyst shard trade) is left
  visible-but-locked, since nothing else hints it exists; everything else stays hidden on purpose.
- **The Remnant can no longer be farmed.** Loops were unlimited and a controlled death was close to
  risk-free, so the 8% roll could be repeated a dozen times in a few real minutes. Only the day's
  first eligible rescue gets a shot at it now, win or lose — real close calls are unaffected.
- **Fixed: villagers panicked near a bound Remnant.** Vanilla's `VillagerHostilesSensor` can't tell
  an evoker's Vex from yours. A real threat standing next to it still gets a real reaction.
- **The re-live's block placements used to just silently appear.** They now land one at a time, in
  the order you actually placed them, each with its own vanilla place/break sound.
- Most paired chat messages are now a single message instead of two, so a rescue no longer floods
  the chat log.
- The Remnant has a real icon now — `paradox:remnant_sigil` — used on the advancement it's tied to.

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
