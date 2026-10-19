<div align="center">

<img src="paradox-icon.png" alt="Paradox" width="180">

# Paradox

![Minecraft 26.2](https://img.shields.io/badge/Minecraft-26.2-brightgreen)
![Fabric](https://img.shields.io/badge/Loader-Fabric-dbd0b4)
![Version](https://img.shields.io/badge/Version-1.1.3-blue)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

</div>

Hardcore mod for Fabric. When you die, you get one chance to go back and stop it from happening.

## How it works

You die, but the death gets paused. Your items stay safe and you turn into a ghost that can fly and can't take damage.

A copy of you replays the last few seconds before you died, next to whatever killed you, so you can see what happened. The boss bar at the top tells you what to do and how much time you have left (30 seconds by default).

What you have to do depends on how you died:

- mob or creeper: kill it
- lava: get the lava out of the spot you died in
- fire: put it out
- cactus, berry bush, magma, dripstone, wither rose, powder snow: break it
- drowning: make an air pocket
- stuck in a wall: clear the block
- fall: put water, hay, slime or a cobweb where you landed

The void, starving and /kill can't be saved.

Once you fix it, the world rewinds 12 seconds and you're back in your body. You play those seconds again, and the blocks your ghost placed or broke come back at the same moments you did them. If a mob killed you, something invisible hits it 3 times and it dies (that's your ghost).

If the timer runs out, you die for real.

## Remnant

Sometimes a rescue leaves a Remnant behind. It's a small pale vex, right-click it to keep it. 8% chance, and only your first rescue each in-game day can roll it.

- takes your next 3 deaths for you, no loop needed. the stars over its head are its lives
- follows you around and attacks hostile mobs, never animals
- its color depends on how you died, and it watches out for that exact thing
- leaves you a letter

Some mobs react to it:

- vexes won't fight it
- an allay gives it a life back if you give the allay an amethyst shard
- an evoker can steal its lives and lock it in a cage. break the bars or kill the evoker to get it back
- a warden eats it, and that's permanent

## Advancements

15 of them, most are hidden until you get them.

## Requirements

| | |
|---|---|
| Minecraft | 26.2 (Java Edition) |
| Loader | [Fabric](https://fabricmc.net/) |
| Required | [Fabric API](https://modrinth.com/mod/fabric-api) 0.149.0 or newer |
| Optional | [Mod Menu](https://modrinth.com/mod/modmenu) + [Cloth Config](https://modrinth.com/mod/cloth-config) for the settings screen |

Hardcore only by default. Turn on `survivalWorlds` if you want it in survival too.

Single player only. It turns itself off when more than one player is online.

## Commands

None of these need cheats, so they work in hardcore.

| Command | |
|---|---|
| `/paradox status` | shows what the mod is recording and your Remnant |
| `/paradox test` | practice run, it can't kill you even if time runs out |
| `/paradox abort` | ends a running loop |
| `/paradox selftest` | checks that the copy of you can be created |
| `/paradox reload` | reloads the config |

## Settings

In Mod Menu, or `config/paradox.properties`.

| Setting | Default | |
|---|---|---|
| `interventionSeconds` | 30 | ghost timer |
| `replayWindowSeconds` | 6 | how much of your death gets replayed |
| `rewindSeconds` | 12 | how far back the rewind goes |
| `rewindEnabled` | true | off = you just get saved where you are, no replay |
| `reliveInvulnerable` | true | you can't get hurt while replaying |
| `survivalWorlds` | false | also works in survival worlds |
| `remnantChance` | 0.08 | Remnant chance (1.0 = every time, for testing) |
| `remnantLives` | 3 | how many deaths a Remnant takes |

## Building

No Gradle. 26.x isn't obfuscated, so it builds with plain javac:

```bash
bash build.sh
```

MIT license.
