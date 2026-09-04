<div align="center">

<img src="paradox-icon.png" alt="Paradox" width="180">

# Paradox

**You do not die. You get one shot at stopping it.**

![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-brightgreen)
![Fabric](https://img.shields.io/badge/Loader-Fabric-dbd0b4)
![Version](https://img.shields.io/badge/Version-1.0.1-blue)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

</div>

A hardcore-survival mod for Minecraft that turns dying into a puzzle you are given one chance to
solve.

---

## What happens when you die

The killing blow is **suspended**. Your inventory goes into escrow, so nothing hits the ground — no
more losing a netherite kit to a mistimed jump. You are cast out of your body as a small pale
spirit: flying, invulnerable, breaking blocks instantly, carrying a curated kit of water buckets,
obsidian, hay and a sword.

Beside you, a replica of yourself **wearing your own skin** re-enacts the last six seconds of your
life on a loop, alongside whatever killed you. A marker sits on the exact block where you died.

You have **sixty seconds**. Dump water on the lava. Break the cactus. Kill the thing that was about
to kill you. The world is re-judged every single tick, so the instant the cause is gone you are
pulled back into your body.

Then the world **winds backwards twelve seconds** and drops you in, alive, to play those seconds
again — while the work your ghost did replays itself around you on schedule. Obsidian places itself.
The creature that killed you is struck three times by nothing at all and dies.

You never meet your rescuer, because it was you.

If the clock runs out with the cause unfixed, the loop closes and the death is real.

## The Remnant

Rarely, a rescue leaves something behind: a pale spirit that does not go back.

It carries a letter to you before it draws its sword, and it will take your **next three deaths**
outright — no loop, no ghost, it simply does not let them happen. It orbits your shoulder, hums,
darts out at hostile mobs and hovers over them, plants itself in front of your face when you are
nearly dead, cheers when you win a fight, and refuses absolutely to harm an animal.

It also remembers exactly how you died, and it cannot be calm about that one thing. A Remnant born
from lava will put itself between you and every lava pool you ever walk past.

Others know what it is. **Vexes** will not fight it and it will not fight them — a swarm that should
be shredding you simply stops. An **Allay** will give it back a life for an amethyst shard. An
**Evoker** will try to take it, and if it succeeds it cages it in iron bars, and no other will come
to you until you break it out. A **Warden** will eat it whole, and that one is permanent.

## Requirements

| | |
|---|---|
| Minecraft | 26.2 (Java Edition) |
| Loader | [Fabric](https://fabricmc.net/) |
| Required | [Fabric API](https://modrinth.com/mod/fabric-api) 0.149.0 or newer |
| Optional | [Mod Menu](https://modrinth.com/mod/modmenu) + [Cloth Config](https://modrinth.com/mod/cloth-config) — in-game settings screen |
| Java | 21+ (Minecraft 26.2 itself needs 25) |

Single-player worlds. The rewind is not safe to inflict on other players, so it stands down when
more than one is online.

## Commands

No permissions required, so they work in a hardcore world with cheats off.

| Command | Does |
|---|---|
| `/paradox status` | Recording buffer, current loop, Remnant state |
| `/paradox test` | A dry run of the whole loop. **Cannot kill you**, even if the timer runs out |
| `/paradox abort` | End a running loop harmlessly |
| `/paradox reload` | Re-read the config file |

## Settings

Mod Menu → Paradox, or `config/paradox.properties`. The useful ones:

| Setting | Default | |
|---|---|---|
| `interventionSeconds` | 60 | Your clock as a ghost |
| `replayWindowSeconds` | 6 | How much of the run-up replays |
| `rewindSeconds` | 12 | How far back you are put to carry on from |
| `rewindEnabled` | true | Off: you are simply saved where you stand |
| `reliveInvulnerable` | true | Nothing can undo your rescue during the re-live |
| `remnantChance` | 0.08 | Chance of a Remnant after a successful rescue |
| `remnantLives` | 3 | Deaths a Remnant will take for you |

## Building

No Gradle, no Loom. Minecraft 26.x ships deobfuscated, so this compiles directly against the game
jar with `javac`:

```bash
bash build.sh
```

Licensed MIT.
