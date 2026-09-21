# Bastion

A crowdfunded, instanced PvE dungeon for Paper 1.21+, built for a whole server to play. Players pool money towards a
goal. When it is reached a join window opens, then everyone inside is locked in for a scripted three-room raid: two
wave rooms gated behind nine hidden artifacts, then a guarded throne room and a boss. Everything about the dungeon
(rooms, mobs, bosses, story lines, rewards, menus) is in yml files, and nothing about a particular build is in the
code.

**No player limit.** Any number can join, and `/dungeon forcestart` needs no goal and no minimum.

## What is in the box

| Download | What it is |
| --- | --- |
| `Bastion-1.0.0.jar` | The plugin |
| `Dungeons-world.zip` | A ready world called `Dungeons` with the castle pasted in, gates built, and the game rules set. Import it with the Worlds plugin |
| `Bastion-default-setup.zip` | `regions.yml`, `doors.yml`, `points.yml` and `dungeon_clean.schem` for that world, and `Dungeons.schem`, the castle with the gates, in case you want to paste it somewhere else |

## Setting it up

1. Put `Bastion-1.0.0.jar` in `plugins`. Vault and an economy plugin are needed for contributions, PlaceholderAPI is optional.
2. Unzip `Dungeons-world.zip` into the server folder and import `Dungeons` with the Worlds plugin.
3. Unzip `Bastion-default-setup.zip` into `plugins/Bastion` (skip `Dungeons.schem`) and restart.
4. `/dungeon` opens the menu. `/dungeon forcestart` opens the dungeon at once, for a test.

The default setup is only files, so it can be changed by editing them or with the in-game tools below. The world
is at its clean state when you get it, and `dungeon_clean.schem` is that state.

## How a run goes

```
FUNDING -> OPEN -> LOCKED -> COUNTDOWN -> ROOM1_TRAVEL -> ROOM1_COMBAT -> ROOM1_LOOT
  -> ROOM2_TRAVEL -> ROOM2_COMBAT -> ROOM3_TRAVEL -> ROOM3_COMBAT -> VICTORY -> CELEBRATION -> RESETTING -> FUNDING
                                                                  \-> FAILED -> RESETTING
```

- **Funding**: only this state takes money. Any other state shows "Dungeon in progress" and refuses.
- **Open**: 5 minutes to join from the menu. Nobody came? The money is refunded (or kept, in `config.yml`).
- **Locked, countdown**: doors seal, a 10 second countdown, then the first gate lifts.
- **Room 1**: a compass on the action bar points the way. Once everyone is in, the gate seals behind them, three waves
  come up out of the floor, then four of the nine artifacts are hidden in barrels around the room.
- **Room 2**: five waves, then the Bloodwoken rises from the altar. When it dies the remaining artifacts are handed to
  the nearest players. **Nine artifacts found in total** is the one check that opens the throne room.
- **Room 3**: throne wardens first. When the last one falls the storm gathers, lightning converges on the throne, and
  the Buried Sovereign rises.
- **Victory**: everyone still in gets the victory rewards and the boss drops the Special Artifact (the shop currency,
  not one of the nine). A 2 minute celebration with fireworks, then everybody goes home.
- **Failure**: 60 minutes after the first gate, or if everyone is dead or gone, the boss escapes and the run is lost.

A player who dies keeps everything, is out of the run and is sent home to exactly where they entered. Their position
is written to disk when they enter, so it survives a crash, and they are sent home when they next log in.

## Commands

| Command | Use |
| --- | --- |
| `/dungeon` (`menu`) | The menu. `menu reload` reads `menus.yml` again |
| `/dungeon status` | State, funding, players inside, artifacts, time |
| `/dungeon contribute <amount>` | Put money towards the goal |
| `/dungeon enter`, `/dungeon leave` | Join during the join window, or leave |
| `/dungeon forcestart [now]` | Open the dungeon without the goal. `now` skips the join window too. No minimum players |
| `/dungeon forceend` | End the run as a failure |
| `/dungeon forcereset` | Reset the world and go back to funding, for recovering from a crash |
| `/dungeon forcefund [amount]` | Add funding as an admin |
| `/dungeon debug` | State, mobs, waves, bosses, running tasks, regions |
| `/dungeon reload` | Read every file again |
| `/dungeon wand [cuboid\|polygon]` | The selection wand |
| `/dungeon region save\|cuboid\|polygon\|list\|show\|delete\|setclean` | Regions, and the clean snapshot |
| `/dungeon door define\|cuboid\|list\|delete\|open\|close` | Gates |
| `/dungeon point add\|list\|remove\|clear <group>`, `/dungeon chest add\|list\|remove\|clear` | Spawn points, and the barrels artifacts hide in |
| `/dungeon paste <file> [world x y z]` | Paste a schematic, spread over ticks. Works with any Sponge `.schem` |

Permissions: `dungeon.use` (everyone), `dungeon.admin`, `dungeon.wand`, `dungeon.bypass.commands`, `dungeon.spy`,
`dungeon.bypass.entry`.

## Placeholders (PlaceholderAPI)

`%dungeon_state%`, `%dungeon_goal_current%`, `%dungeon_goal_total%`, `%dungeon_goal_percent%`,
`%dungeon_join_time_left%`, `%dungeon_countdown%`, `%dungeon_run_time_left%`, `%dungeon_celebration_time_left%`,
`%dungeon_players_inside%`, `%dungeon_artifacts_found%`, `%dungeon_artifacts_total%`, `%dungeon_current_room%`,
`%dungeon_boss_name%`, `%dungeon_boss_hp_percent%`, `%dungeon_last_result%`, `%dungeon_coins%`.

## The files

| File | What it holds |
| --- | --- |
| `config.yml` | Goal, every timer, mob limits, command whitelist, server announcements |
| `messages.yml` | Every system message |
| `dialogue.yml` | Every line of the story, by trigger. NORMAL lines are action bar and chat, MAJOR ones add a title |
| `rooms.yml` | Waves, mini-boss, guards and boss for each room, and which gates and points they use |
| `mobs.yml` | The mobs: type, gear, effects and one random minor power (shove, flicker, hurl, war cry) |
| `bosses.yml` | The two bosses: health, size, bossbar and the abilities with weights and cooldowns |
| `artifacts.yml` | The nine artifacts and the special one |
| `rewards.yml` | Chance tables per mob tier, and the victory reward |
| `menus.yml` | The menu, in the style of DeluxeMenus. Actions: `[COMMAND]` `[CONSOLE]` `[MESSAGE]` `[OPEN_MENU]` `[CONTRIBUTE]` `[CONTRIBUTE_PROMPT]` `[ENTER]` `[LEAVE]` `[CLOSE]` `[SOUND]` |
| `regions.yml`, `doors.yml`, `points.yml` | The build: where things are. The default setup fills these in |
| `dungeon_clean.schem` | The snapshot every run is reset to |

Add a wave, a mob, a boss ability or a line of dialogue by editing a file and running `/dungeon reload`.

### Building your own

`/dungeon wand` gives a blaze rod. In cuboid mode left click is corner 1 and right click is corner 2. In polygon mode
right click each corner in turn and sneak + left click to close the shape. `/dungeon region save <id> <type>` keeps it.
Region types are `SPAWN`, `ROOM1`, `ROOM2`, `ROOM3`, `DUNGEON`. A gate is a box with the bars in it: build it closed,
select it, `/dungeon door define <id>`. When it is all built, `/dungeon region setclean` takes the snapshot.

## Why it is light

Many players can be in one run, so:

- **Regions** are bucketed by chunk, so a lookup tests only the regions that touch the chunk. A polygon rejects by
  height and bounding box before it does the ray cast, and a move is looked at only when a player changes block.
- **One shared tick** (four a second) drives bossbars, timers, waves and boss abilities. Nothing has a task of its own
  per mob. Mobs rise, doors lift and bosses grow through shared animation tasks that run only while something moves.
- **At most 8 mobs at once** (`mobs.max-active`), however big the wave. The rest come as earlier ones die.
- **Particles and sounds go to the players in the run only**, never a radius broadcast, so nothing leaks out and the
  cost follows the party, not the server.
- **Bossbars** only send an update when health moved enough to be seen.
- **The world reset** never stalls a tick. Chunks are loaded off the main thread and compared with the snapshot on
  another thread. Only blocks that differ are written, at most 6 ms per tick, and the main-thread part of each chunk is
  spread over ticks. Restoring the whole 237x154x192 castle (7 million blocks) takes about three seconds and the worst
  tick during it is a few milliseconds.
- **Player data is never written on the main thread**, and one writer keeps saves in order.
- **Everything a run starts is cancelled when it ends**: tasks, mobs, bossbars, doors. `/dungeon debug` shows the
  task count, which is 0 between runs.
- **A step that takes over 25 ms is logged by name**, so any lag from a heavy step shows up in the console.

## Measured

On Paper 1.21.11 with two players in a run, a full playthrough from funding to the reset after the boss:

- 20.0 TPS throughout, average tick about 1.5 ms with 8 mobs, a boss and its adds up
- pasting the castle from nothing: 72,076 blocks written in about a second
- taking the snapshot of 7,007,616 blocks: about a second, off the main thread
- a reset after a run: 0.1 to 2.9 seconds depending on how many chunks were loaded, no tick over the 6 ms budget

## Left out on purpose

- FAWE. The restore is built in, so there is one dependency less.
- ProtocolLib and packet gates. Gates are real blocks, which works the same for everyone and survives a relog.
- A custom mob AI and a mob-effect on hit (hunger). Mobs are vanilla with a kit and one small power.
- Sign text and container items in the snapshot. Restoring puts blocks back and empties containers, which is what a
  run needs.

## Building

```
./gradlew build
```

The jar is in `build/libs`. Licensed under MIT.
