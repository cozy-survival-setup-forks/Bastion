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
| `Bastion-1.2.1.jar` | The plugin |
| `Dungeons-world.zip` | A ready world called `Dungeons` with the castle pasted in, gates built, and the game rules set. Import it with the Worlds plugin |
| `Bastion-default-setup.zip` | `setup.yml` (regions, doors and points) and the `schematics` folder for that world: `dungeon_clean.schem` (the snapshot every run resets to) and `Dungeons.schem` (the castle with the gates, for pasting elsewhere) |

## Setting it up

1. Put `Bastion-1.2.1.jar` in `plugins`. Vault and an economy plugin are needed for contributions, PlaceholderAPI is optional.
2. Unzip `Dungeons-world.zip` into the server folder and import `Dungeons` with the Worlds plugin.
3. Unzip `Bastion-default-setup.zip` into `plugins/Bastion` and restart.
4. `/dungeon` opens the menu. `/dungeon forcestart` opens the dungeon at once, for a test.

Coming from 1.2? The first gate moved to the archway into the rotunda, and artifacts and rewards use a new layout: delete `config.yml` and `setup.yml`, then import the new world zip (or re-paste the castle and run `/dungeon region setclean`).

Coming from 1.1? Delete `config.yml`, `messages.yml`, `mobs.yml` and `setup.yml` (the new ones add mixed waves, more mobs, room 2 chests, the announcement blocks and guard spawn points), use the new setup zip, then re-paste the castle so the lowered gold blocks and room 2 barrels exist: `/dungeon paste Dungeons.schem Dungeons -251 135 -326`, then `/dungeon region setclean`. Or import the new `Dungeons-world.zip` instead.

Coming from 1.0? The files were merged, so delete the old ones (`config.yml`, `messages.yml`, `mobs.yml`, `dialogue.yml`, `rooms.yml`, `bosses.yml`, `artifacts.yml`, `rewards.yml`, `regions.yml`, `doors.yml`, `points.yml`, `locations.yml`) and let the new ones generate.

The world must be called exactly `Dungeons`, or change `world:` in `config.yml` and the world name in `setup.yml`. The default setup is only files, so it can be changed by editing them or with the in-game tools below.

## How a run goes

```
FUNDING -> OPEN -> LOCKED -> COUNTDOWN -> ROOM1_TRAVEL -> ROOM1_COMBAT -> ROOM1_LOOT
  -> ROOM2_TRAVEL -> ROOM2_COMBAT -> ROOM3_TRAVEL -> ROOM3_COMBAT -> VICTORY -> CELEBRATION -> RESETTING -> FUNDING
                                                                  \-> FAILED -> RESETTING
```

- **Funding**: only this state takes money. Any other state shows "Dungeon in progress" and refuses.
- **Open**: 5 minutes to join from the menu. Nobody came? The money is refunded (or kept, in `config.yml`).
- **Locked, countdown**: doors seal, a 10 second countdown, then the first gate lifts. While they wait, players see the time left on the action bar and cannot break or place blocks.
- **Room 1**: a compass on the action bar points the way, relative to where you look (first at the gate, then at the heart of the room). Once everyone is in the heart of the room (not just the
  corridor), the gate seals behind them and three waves come up out of the floor. When the last wave is dead the gate
  lifts again, and four of the nine artifacts are hidden in chests and barrels around the room, with a faint glint over each. Waves mix several kinds of mob, and their size follows how many players are in the room.
- **Room 2**: the same, with five waves, then the Bloodwoken rises from the altar. When it dies the remaining artifacts are hidden in the
  hall's chests and barrels (point group `chests_room2`), the same as in room 1. **Nine artifacts found in total** is the one check that opens the throne room.
- **Room 3**: four waves of guards, weakest to strongest, rise out of the sunken gold blocks. After the last one the storm gathers, lightning converges on the throne, and
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
| `/dungeon point add\|list\|remove\|clear <group>` | Spawn points |
| `/dungeon chest add\|list\|remove\|clear [room2]` | Where artifacts hide. Look at a barrel or chest, or at the floor to have a chest placed there. `room2` is the south hall |
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
| `config.yml` | Goal, every timer, mob limits, command whitelist, then the rooms (waves, doors, mini-boss, guards, boss), the nine artifacts and the rewards |
| `messages.yml` | Every system message, the big server announcements (`announce-opened`, `announce-start`, `announce-victory`, `announce-failure`, one line each), and every line of the story (`dialogue`), by trigger. NORMAL lines are action bar and chat, MAJOR ones add a typed title |
| `mobs.yml` | The mobs and the two bosses: type, name, gear, damage, effects, one random minor power, and the boss abilities with weights and cooldowns |
| `menus.yml` | The menu, in the style of DeluxeMenus. Actions: `[COMMAND]` `[CONSOLE]` `[MESSAGE]` `[OPEN_MENU]` `[CONTRIBUTE]` `[CONTRIBUTE_PROMPT]` `[ENTER]` `[LEAVE]` `[CLOSE]` `[SOUND]` |
| `setup.yml` | The build: regions, doors and points. The default setup fills it in |
| `schematics/` | `dungeon_clean.schem`, the snapshot every run is reset to, and any schematic you want to `/dungeon paste` |
| `data.yml` | Runtime data: the funding so far, and where players in the dungeon came from. Not for editing |

Add a wave, a mob, a boss ability or a line of dialogue by editing a file and running `/dungeon reload`.

### Building your own

`/dungeon wand` gives a blaze rod. In cuboid mode left click is corner 1 and right click is corner 2. In polygon mode
right click each corner in turn and sneak + left click to close the shape. `/dungeon region save <id> <type>` keeps it.
Region types are `SPAWN`, `ROOM1`, `ROOM2`, `ROOM3`, `DUNGEON`, and `OTHER` for the core of a room, the smaller region inside it where everyone has to be for the fight to start (`core:` in `config.yml`). A gate is a box with the bars in it: build it closed,
select it, `/dungeon door define <id>`. When it is all built, `/dungeon region setclean` takes the snapshot. After that, blocks an admin places or breaks in the dungeon region while it is idle are written into `dungeon_clean.schem` a few seconds later, so small edits never need the snapshot to be taken again. Edits made with WorldEdit or by other plugins still need `/dungeon region setclean`.

## Why it is light

Many players can be in one run, so:

- **Regions** are bucketed by chunk, so a lookup tests only the regions that touch the chunk. A polygon rejects by
  height and bounding box before it does the ray cast, and a move is looked at only when a player changes block.
- **One shared tick** (four a second) drives bossbars, timers, waves and boss abilities. Nothing has a task of its own
  per mob. Mobs rise, doors lift and bosses grow through shared animation tasks that run only while something moves.
- **A cap on live mobs** (`mobs.max-active`, plus `max-active-per-player` for each extra player in the room, up to `hard-cap`). The rest come as earlier ones die, and at most four spawn per second.
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
- A custom mob AI. Mobs are vanilla with a kit and one small power (a shove, a leap, a blink, a wither or hunger touch, a heal pulse, a blast on death and so on).
- Sign text and container items in the snapshot. Restoring puts blocks back and empties containers, which is what a
  run needs.

## Building

```
./gradlew build
```

The jar is in `build/libs`. Licensed under MIT.
