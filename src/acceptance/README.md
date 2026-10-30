# Disposable in-game acceptance fixture

This is a separate test-only Forge mod. `src/acceptance` is not part of the production source set, production jar, or production sources jar. It does not add any MCP tools or change production tool behavior.

Build it with `./gradlew reobfAcceptanceJar`. Install **both** `gtnhmcp-0.1.0.jar` and `gtnhmcp-acceptance-0.1.0.jar` only in an isolated GTNH 2.9.0-RC-2 test instance. Enable the fixture with JVM properties:

```text
-Dgtnhmcp.acceptance=true
-Dgtnhmcp.acceptance.dir=/absolute/path/to/work/acceptance-control
-Dgtnhmcp.acceptance.world=gtnhmcp-acceptance-rc2
```

The world name must have the `gtnhmcp-acceptance-` prefix. The fixture creates or opens that disposable flat creative world after the main menu loads. It never selects another save. Do not install it in a normal play instance.

When `status.json` reports `phase: "world_ready"`, the authoritative integrated-server state contains:

| Location | Contents |
| --- | --- |
| Player at `(0.5, 65, 0.5)` | 32 iron ingots, 16 redstone, 3 diamonds |
| Chest at `(2, 65, 0)` | 64 iron ingots, 32 redstone, 16 oak logs |
| Furnace at `(4, 65, 0)` | 8 iron ore; no fuel |
| Crafting table at `(2, 65, 2)` | Empty |

All coordinates are in dimension 0. The player initially faces the chest. On each integrated-server start the disposable fixture and player inventory are reset to these known values. Losing window focus does not pause the test. The fixture does not automatically pause after preparation.

Write a single command into `command.txt` inside the control directory. The fixture consumes and deletes it on the game client thread, then records `lastCommand` in the atomically replaced `status.json`:

- `pause`: open the vanilla pause menu.
- `resume`: close the pause menu.
- `unload`: save/disconnect from the test world and return to the main menu.
- `rejoin`: reopen the same test world; only valid after unloading.
- `quit`: shut down this test client.

Wait for the corresponding actual `worldLoaded`, `paused`, and `ready` fields, not only the command acknowledgement. An invalid command or fixture exception changes `phase` to `failed` and logs the failure in the game log. This control path is test code running through Minecraft APIs, independent of the production MCP endpoint.
