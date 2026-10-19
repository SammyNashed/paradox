# Changelog

## 1.1.5

- the replay zombie has the same gear as the real one now, so it doesn't burn in daylight
- no more name tag floating over the replay copy of you
- you can't hit your own ghost body by accident anymore
- deaths from /damage don't start a rescue loop anymore (there's nothing to fix)
- your ghost body doesn't block your view in first person anymore, and you still see yourself as the ghost in third person (F5). needs Paradox on the client too, without it the body just always shows

## 1.1.4

- the mob that killed you now walks the same route in the replay as it did when you saw it as a ghost, and dies at the same spot where you killed it (before, it walked straight at you and died somewhere else)

## 1.1.3

- chat messages are mostly gone. the timer is a boss bar now, and dying/rewinding uses sounds and effects instead of text
- "You lived" shows up right when the killer dies, not a few seconds later
- default timer is 30 seconds now (was 60). if you already have a config it stays at what you had
- lava deaths: you only have to clear the spot you died in, not all the lava around it
- fixed blocks you placed right before the rescue sometimes not getting rewound and replayed
- fixed the Remnant's saved data getting lost when you join a world
- Remnant chance at 1.0 now works every time (was still limited to once a day)
- rewrote all the text and renamed the advancements

## 1.1.2

- works in survival worlds if you turn on `survivalWorlds` (off by default, it's made for hardcore)
- if the game closes in the middle of a loop, you get your items and game mode back when you join again
- creeper deaths: the crater explodes all at once during the replay instead of block by block
- fixed another creeper near your death spot killing you again right after you were saved
- removed the "NPC" label over the copy of you

## 1.1.1

- the Remnant's color and what it watches out for now depend on the exact thing that killed you (cactus, berry bush, magma, powder snow, dripstone, wither rose, suffocation, TNT)
- fixed TNT and end crystal deaths being treated like mob deaths
- added 15 advancements, most of them hidden
- the Remnant can only roll once per in-game day, so you can't farm it with controlled deaths
- fixed villagers panicking near your Remnant, and evoker vexes still hitting it sometimes
- blocks during the replay now come back one at a time with their sounds instead of all appearing at once
- fewer chat messages
- the Remnant has its own icon now

## 1.0.1

- fixed the warden never eating the Remnant
- the warden now roars, faces the Remnant while it eats it, and ignores you during it
- the Remnant stops fighting once a warden notices it

## 1.0.0

First release.
