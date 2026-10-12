# Swap

A Paper plugin (Minecraft 1.21.x, Java 21) where everyone lives in a **superflat** world and, every
cycle, one random online player is sent to the **main** survival world for a timed session.

## How a cycle works

| Step | Default |
| --- | --- |
| A random online player is chosen and teleported to the main world | every 15 min (10 session + 5 cooldown) |
| Session length | 10 min, then back to superflat |
| Cooldown before the next player is chosen | 5 min |
| Dying in the main world | instantly sent back to superflat, session ends |
| Next player's spawn | the exact position where the previous player left the main world |

* The first player is chosen `timing.initial-delay-minutes` (default 5) after startup, so the 15 minute rhythm
  holds from then on. If nobody is online, the plugin waits and picks as soon as someone joins.
* Slimes cannot spawn in the superflat world. Everyone always respawns and joins there
  (give a player `swap.bypass` to exempt them from the join teleport).
* A boss bar shows the countdown to everyone.
* Last exit position is saved to `plugins/Swap/state.yml`, so it survives restarts.

## Powerups

Both are custom items identified by hidden item data, not by name.

**Dual Gemstone**

```
D D D        D = Diamond
G R G        G = Gold Ingot
D D D        R = Redstone Block
```

During your own session run `/swap bring <player>` (consumes one gemstone). That player is pulled in next to
you and you share inventory and health, synchronised every tick. Their own inventory is stored on disk and
handed back when the link ends, so nothing is duplicated; the link ends when the session ends, when either
player leaves, or when either dies (the shared inventory drops where the dying player fell and both are sent out).

**Time Extender**

```
G B G        G = Glass
G S G        B = Diamond Block
G E G        S = Sand, E = Emerald Block
```

Right-click it (or `/swap extend`) during your own session: the session becomes 20 minutes and the cooldown
afterwards 10 minutes. It can be used once per session.

## Random modifiers

When a player is chosen, at most one modifier is rolled (chances are configurable, ~31% overall):

| Modifier | Chance | Effect |
| --- | --- | --- |
| PARTY (rainbow) | 10% | all online players join the session |
| FORTUNATE (blue) | 1% | temporary Fortune V pickaxe |
| LOOTABLE (blue) | 1% | temporary Looting V sword |
| DEFLATION (green) | 2% | every villager trade costs 1 emerald |
| INFLATION (red) | 2% | every villager trade costs 10% more (rounded up) |
| THE GREAT RESET (red) | 0.1% | the main world is deleted and regenerated with a new seed |
| INVINCIBLE (white/grey) | 10% | Resistance 225 |
| DAMAGED (purple) | 5% | start on half health |

Modifiers are applied before the player enters the main world and reverted when the session ends (temporary
items are deleted on session end, death and logout; they cannot be dropped or put in containers).
Villager offers are only changed while a trade window is open and restored on close.
After The Great Reset the next player starts at the new world spawn.

## Commands

| Command | Permission | |
| --- | --- | --- |
| `/swap status` | `swap.play` (default) | current phase and time left |
| `/swap bring <player>` | `swap.play` | use a Dual Gemstone |
| `/swap extend` | `swap.play` | use a Time Extender from your inventory |
| `/swap start [player] [modifier\|none]` | `swap.admin` (op) | start a session right now |
| `/swap end` | `swap.admin` | end the current session |
| `/swap give <player> <dual\|extender> [amount]` | `swap.admin` | hand out powerups |
| `/swap reload` | `swap.admin` | reload `config.yml` |

## Build and install

```
mvn package          # produces target/Swap-1.0.0.jar
```

Drop the jar into `plugins/` of a Paper 1.21.x server. The `superflat` and `main` worlds are created on first
start. Every push also builds the jar in GitHub Actions (artifact `Swap`).

## Notes and limits

* Only the Overworld is rotated. Nether/End portals from `main` use the server's normal dimensions, which are
  not reset by The Great Reset.
* If a server crash happens while a Dual Gemstone link is active, the partner's inventory is restored from
  `plugins/Swap/pending/` the next time they join. A crash while a villager trade window is open under
  DEFLATION/INFLATION would leave that villager's prices adjusted.
