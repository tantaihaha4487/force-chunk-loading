# Force Chunk Loading

A server-only Fabric mod for Minecraft 26.1.2 / Fabric Loader 0.19.3.

## Behavior

- The recipe creates a vanilla `minecraft:player_head` named `Chunk load` with a static Earth texture.
- The head is intentionally a vanilla block, so a client does **not** need this mod installed.
- Placing the marked head force-loads the chunk containing it with `ServerLevel#setChunkForced`.
- The location is persisted with vanilla `SavedData`, and force-loading is restored after a server restart.
- Breaking the head removes the saved marker and unforces the chunk.
- If non-player destruction is disabled, the server reconciles an explosion, creeper, piston, command, or other external removal on the next server tick by restoring the marker. If it is enabled, the marker is removed and its chunk is unforced.

The Earth texture is embedded as a vanilla player profile property; the client receives normal player-head block-entity data and does not need a custom renderer or registry entry.

## Configuration

On first dedicated-server start, the mod writes `config/force_chunk_loading.json`:

```json
{
  "allowPlacement": true,
  "placementPermissionLevel": 0,
  "allowPlayerRemoval": true,
  "allowNonPlayerRemoval": false,
  "recipe": {
    "enabled": true,
    "pattern": ["OOO", "OEO", "OOO"],
    "ingredients": {
      "O": "minecraft:obsidian",
      "E": "minecraft:ender_eye"
    }
  }
}
```

`placementPermissionLevel` uses the Minecraft 26.1 permission levels: `0` allows all players, while `1`–`4` require progressively higher moderator/gamemaster/admin/owner command permission. Edit the file and restart the server; recipe changes are injected as a vanilla shaped recipe during the server data reload.

`allowPlayerRemoval` controls player breaking. `allowNonPlayerRemoval` controls destructive world changes such as explosions, creepers, pistons, and commands. The default is player-removable but protected from non-player removal.

## Why no SQLite?

SQLite is unnecessary for this state: the data is a small set of world positions, is owned by each dimension, and must load/save with the Minecraft world lifecycle. `SavedData` is the native persistent store, avoids an extra driver/native file, and is durable across restarts.

## Build

```bash
./gradlew clean build
```

The distributable JAR is written to `build/libs/`. Install it on the dedicated server with Fabric Loader and Fabric API; do not install it on clients.
