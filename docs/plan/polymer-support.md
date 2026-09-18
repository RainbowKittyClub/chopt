# Polymer support for the shrinking stump

Make the shrinking stump render on clients that do not have chopt installed, by routing it through
Polymer instead of a client-only `BlockEntityRenderer`, so the mod becomes genuinely server-side.

Claims about chopt's current behaviour were read out of `src/main/java_v26.3/` and
`src/client/java_v26.3/` and are cited inline. Claims about the Polymer API were checked with
`javap` against the nested jars inside `polymer-bundled-0.18.1+26.3.jar` downloaded from Modrinth —
that is the version chopt's primary target would actually run, and it is a different minor line
from the 0.17.5 source checkout at `libs/polymer` (branch `dev/26.2`). Two things are **not**
verified and are called out where they matter: what a vanilla client does today when it meets an
unknown block-state id, and whether a virtual display element can be hidden from one player while
shown to another.

S1 is a build-system change and unblocks everything else. S2 is the smallest change that makes a
vanilla client usable and is worth landing on its own. S3 is what actually delivers the feature —
after S3 a vanilla client sees the same thing a chopt client sees, and the feature is complete.
S4 and S5 are independent of each other and both optional: S4 restores the higher-fidelity `BER`
path for clients that do have chopt, S5 is cleanup that only becomes possible once S4 exists.

---

## Findings that reshape the work

**`polymer-networking` does not exist in the 0.18.x line, so the usual handshake pattern is
unavailable on chopt's primary target.** `polymer-bundled-0.18.1+26.3.jar` nests exactly eight
module jars — `autohost`, `blocks`, `common`, `core`, `resource-pack`, `resource-pack-extras`,
`sound-patcher`, `virtual-entity`. There is no `polymer-networking` among them, and the only
`networking` package left in `polymer-core` is `eu.pb4.polymer.core.impl.client.networking`, which
is implementation-internal. The 0.17.x line did ship it as a separate module. Every workspace
recipe that reaches for `PolymerNetworking` to register a versioned capability packet therefore
does not port to 26.3. S4 uses `PolymerSyncedObject.canSynchronizeToPolymerClient(PacketContext)`
for "is this a polymer client" and chopt's own `ChoptNetworking` payload for "does this client have
chopt", rather than a Polymer handshake.

**chopt already ships the element-less client model that a Polymer block needs.**
`assets/chopt/models/block/shrinking_stump_placeholder.json` has `"elements": []` and a single
`particle` texture pointing at `minecraft:block/stripped_oak_log`, and the blockstate file maps
every variant to it. That is exactly the shape a Polymer block wants so a mod-running client does
not draw a missing-model cube behind its renderer. This half of the usual setup needs no work.

**The stump's block state carries no species, so the substitute state cannot be a log.**
`ShrinkingStumpBlock` (`ShrinkingStumpBlock.java:29`) has only `AXIS`, `STAGE` and `STAGES`; the
wood species lives in `ShrinkingStumpBlockEntity.displayState`
(`ShrinkingStumpBlockEntity.java:22`), written by `TreeChopper.updateStumpVisual`. Polymer's
`getPolymerBlockState(BlockState, PacketContext)` receives no position and no block entity, so it
physically cannot pick the right stripped log. The substitute has to be species-independent and the
species has to come from somewhere else — which is why the display entity in S3, not the substitute
state in S2, is what makes this feature complete.

**An invisible substitute breaks client-side mining, and Polymer has a hook for exactly that.**
A stump exists to be hit repeatedly. If the substitute is `Blocks.BARRIER`, a vanilla client
computes destroy progress against barrier's `-1` hardness, never reaches completion, and never
sends the stop-destroy packet. `PolymerBlock.handleMiningOnServer(ItemStack, BlockState, BlockPos,
ServerPlayer)` exists in 0.18.1 to move that decision server-side, and `PolymerBlockUtils` also
exposes `SERVER_SIDE_MINING_CHECK` and `BREAKING_PROGRESS_UPDATE` events. S2 is not finished
without it.

**Polymer replaces non-player collision with the client state's shape, which silently makes the
block a redstone conductor.** The stump deliberately shrinks its collision and outline per stage
(`ShrinkingStumpBlock.java:59`, `:69`, `:74`). Polymer rewrites `getCollisionShape` to the client
state whenever the `CollisionContext` has no `ServerPlayer`, including the empty context that fills
the `isCollisionShapeFullBlock` cache — so with a full-cube substitute the default
`isRedstoneConductor` predicate starts returning true. The properties at `ChoptBlocks.java:24` are
built with `ofFullCopy(Blocks.OAK_LOG)` and must gain an explicit
`.isRedstoneConductor((state, level, pos) -> false)`. Mobs and dropped items will still collide
with the full cube while players see the shrunken shape; that asymmetry is accepted, not fixed.

**Polymer cannot be a hard dependency, and cannot be added to every version tree.** chopt builds
nine Minecraft targets from one `build.gradle` and must keep loading on servers with no Polymer.
The build already has the pattern for this: `jade_version` gates a dependency, a set of compat
source files and a `fabric.mod.json` fixup (`build.gradle:112`, `:132`, `:161`, `:209`). Polymer
support follows the same shape under a `polymer_version` key, present only in the profiles that
have a Polymer release.

---

## How these stages get tested

Everything here is an in-world observation on a dev server; nothing in it is provable by a build.
The server side is Loom's `runServer` out of the chopt checkout. Two clients are needed at once and
both already exist as Modrinth profiles: **Fabric 26.3** (empty — it becomes the "vanilla" client,
and must stay without chopt for S2 and S3, needing only Fabric API to connect) and **Kalic0
Modpack** (26.2, already carries `polymer-bundled-0.17.5+26.2`, so it is the client that exercises
the S4 path once chopt is installed into it).

A stage is not finished until its "Done when" has been observed with both clients connected at the
same time. That simultaneity is the point: the whole feature is about two clients disagreeing about
what a block looks like while agreeing about what it does. Testing them one after the other will
hide exactly the bugs this work can introduce.

Before the first round, confirm the premise that motivates all of it: connect the vanilla client to
a chopt server as it stands today and record what actually happens at a stump. The expectation is
that the unknown block-state id resolves to air on the client, since chopt's states are appended
after every vanilla state, but this has not been checked and the failure mode could instead be a
disconnect on registry sync. If it turns out a vanilla client cannot connect at all, S2 grows to
cover registry sync and stops being small.

## S1 — Optional, version-gated Polymer dependency — **planned**

**Build.** Add a `polymerVersion` gate to `build.gradle` mirroring the `jadeVersion` one at
`:112`: a `compileOnly`/`modCompileOnly` dependency on `eu.pb4:polymer-bundled` when the key is
present, exclusion of `**/compat/polymer/**` from the jar when it is absent (`:209`), and removal
of the `polymer` entry from `fabric.mod.json`'s `suggests` when absent (`:161`). Polymer belongs in
`suggests`, never `depends` — the mod has to keep loading without it. Add `polymer_version` to the
`26.1`, `26.1.1`, `26.1.2`, `26.2` and `26.3` profiles under `gradle/profiles/` only; the 1.20.1
and 1.21.x profiles stay without it. New code lives in `src/main/java_v<mc>/mod/chopt/compat/polymer/`
so the existing per-version source-root injection picks it up.

**Done when.** `./gradlew build` is green for the 26.3 profile with the key set and for the 1.20.1
profile without it, and `unzip -l` on the two jars shows `mod/chopt/compat/polymer/` present in the
first and absent from the second. `fabric.mod.json` inside the 1.20.1 jar has no `polymer` key
anywhere.

The awkward part is that the gate has to be by *presence* of the key rather than a truthy value, to
match how `jadeVersion` already reads, and that the compat package cannot be referenced from
unconditionally-compiled code. Reach it the way the Jade compat is reached, through an entrypoint
or a reflective/`FabricLoader.isModLoaded` guard, not a direct call from `Chopt.onInitialize`.

## S2 — Substitute block state and server-side mining — **planned**

**Build.** Make `ShrinkingStumpBlock` implement `PolymerBlock`. `getPolymerBlockState` returns a
species-independent substitute for every state — see Open decisions for the choice between
`Blocks.BARRIER` and a fixed stripped log. Override `handleMiningOnServer` to return true so the
server drives break progress rather than trusting a client that thinks the block is unbreakable.
Add `.isRedstoneConductor((state, level, pos) -> false)` to the properties at `ChoptBlocks.java:24`.

**Done when.** The vanilla client joins, can target a stump, chop it with repeated swings, and fell
the tree — the gameplay effect is the actual requirement here. The display effect at this stage is
only "something sane instead of a missing-model cube or nothing"; a full-size or invisible stump is
an acceptable intermediate, because S3 is what fixes the visuals. Separately, place a chest
directly under a stump and confirm it still opens, which is the observable probe for the
redstone-conductor regression.

## S3 — Virtual display entity for the shrinking stump — **planned**

**Build.** Make the block implement `BlockWithElementHolder`. The holder owns one
`BlockDisplayElement`, whose block state is the stump's `displayState` and whose scale comes from
`ShrinkingStumpBlock.scaleFor(stage, stages)` (`ShrinkingStumpBlock.java:84`) applied to X and Z
with Y left at 1.0 — the same axes the client renderer scales at
`ShrinkingStumpRenderer.java:63`. The renderer brackets its scale with a translate to the block
centre and back (`:62`, `:64`); the display element needs the equivalent translation or the stump
will shrink toward a corner instead of its axis. Drive updates from
`TreeChopper.updateStumpVisual`, which is already the single place stage changes are published.

**Done when.** With both clients watching the same tree, the vanilla client sees a stump of the
correct wood species that visibly shrinks step by step as the tree is chopped, and it matches what
the chopt client draws through its `BER` closely enough that the two are not obviously different
blocks. This is the stage that makes the feature complete.

`createElementHolder(ServerLevel, BlockPos, BlockState)` runs inside `LevelChunk`'s constructor
during chunk load. Reading `level.getBlockState` or `level.getBlockEntity` from it deadlocks the
server thread at "Preparing spawn area". Take the `initialBlockState` parameter for anything needed
immediately and defer the block-entity read — which is where the species lives — to the holder's
tick. Whether the species can be recovered early enough to avoid a one-tick wrong-wood flash is an
open question; resolve it by building this, not by guessing.

## S4 — Real block state for clients that have chopt — **planned**

**Build.** Mark the block `PolymerClientDecoded`, and have `getPolymerBlockState` return the real
state when the context is both a polymer client
(`PolymerSyncedObject.canSynchronizeToPolymerClient(PacketContext)`) and a chopt client. Because
`polymer-networking` is gone on 0.18.x, determine the latter with chopt's own payload: extend
`ChoptNetworking` (`ChoptNetworking.java:29` is already an empty `registerServerReceivers` hook) so
a chopt client announces itself on join. Register the block entity with the two-argument
`PolymerBlockUtils.registerBlockEntity(BlockEntityType<?>, PolymerSyncedObject<BlockEntityType<?>>)`
form, returning the type for chopt clients and null otherwise — the varargs form strips the block
entity from *every* client's chunk packet, which would leave a chopt client with the real block
state and no block entity for its renderer to read. Suppress the S3 display element for these
clients.

**Done when.** Two clients on one server at one stump: the chopt client renders through
`ShrinkingStumpRenderer`, the vanilla client renders the display entity, and neither shows the
other's artefacts — no doubled geometry on the chopt client, no missing-model cube on either.

Whether a `BlockDisplayElement` can be shown to one player and hidden from another is the flagged
unknown here. If `ElementHolder` has no per-player visibility control, the fallback is to let chopt
clients see the display entity too and drop the `BER` entirely, which would make S5 mandatory
rather than optional and would arguably be the better end state anyway.

## S5 — Retire the bespoke sync payload — **planned**

**Build.** `ChoptNetworking.ShrinkingStumpDisplay` (`ChoptNetworking.java:40`) pushes the display
state to tracking players, duplicating what `ShrinkingStumpBlockEntity.getUpdatePacket`
(`ShrinkingStumpBlockEntity.java:64`) already sends. Once S4 keeps the block entity in chunk
packets for chopt clients, check whether the payload still earns its place and delete it if not.

**Done when.** With the payload removed, a chopt client joining near an existing partly-chopped
stump sees the correct species and scale immediately, and sees it update on every subsequent swing.

## Open decisions

**Substitute block for S2.** `Blocks.BARRIER` is invisible, which is right once S3 draws the real
visual, but it needs `handleMiningOnServer` to stay chopable and it leaves the client drawing a
full-cube selection outline around a shrunken stump. A fixed stripped log is breakable and outlined
correctly with no extra hook, but it is the wrong species most of the time and would sit visibly
inside the S3 display entity. Settle it by building S2 with barrier and looking at the outline
mismatch in-world; if it reads as broken, the log substitute plus suppressing the display entity
for vanilla clients is the alternative.

**Whether the 1.21.x and 1.20.1 trees get Polymer at all.** Polymer exists for those versions but
on a much older API line, and chopt's per-version trees would each need their own compat package.
The cheap answer is to support Polymer only on 26.x and leave the older targets client-required.
Settle it by asking whether anyone runs chopt on a 1.21.x server where this matters.

**What the README should say.** `README.md:12` already claims "Client-agnostic: no client-side
setup required; works server-side", which is not true today — the stump is a real block with a
client-only renderer. Either this work makes the line true or the line needs correcting; it should
not be left as is either way. Not edited here.

## Reference: verified Polymer API, 0.18.1+26.3

Read with `javap` from the nested module jars of `polymer-bundled-0.18.1+26.3.jar`. All parameter
types below are the real ones, including Fabric's
`net.fabricmc.fabric.api.networking.v1.context.PacketContext`.

| Member | Module |
|---|---|
| `PolymerBlock.getPolymerBlockState(BlockState, PacketContext)` — abstract | core |
| `PolymerBlock.handleMiningOnServer(ItemStack, BlockState, BlockPos, ServerPlayer)` — default | core |
| `PolymerBlock.getPolymerBreakEventBlockState(BlockState, PacketContext)` — default | core |
| `PolymerBlockUtils.registerBlockEntity(BlockEntityType<?>...)` | core |
| `PolymerBlockUtils.registerBlockEntity(BlockEntityType<?>, PolymerSyncedObject<BlockEntityType<?>>)` | core |
| `PolymerBlockUtils.SERVER_SIDE_MINING_CHECK`, `BREAKING_PROGRESS_UPDATE` — events | core |
| `PolymerSyncedObject.getPolymerReplacement(T, PacketContext)` — abstract | core |
| `PolymerSyncedObject.canSynchronizeToPolymerClient(PacketContext)` — default | core |
| `PolymerClientDecoded` — marker interface | core |
| `BlockWithElementHolder.createElementHolder(ServerLevel, BlockPos, BlockState)` — default | virtual-entity |
| `BlockWithElementHolder.tickElementHolder(ServerLevel, BlockPos, BlockState)` — default | virtual-entity |
| `BlockDisplayElement(BlockState)`, `setBlockState(BlockState)` | virtual-entity |
| `DisplayElement.setScale(Vector3fc)`, `setTranslation(Vector3fc)`, `setInterpolationDuration(int)` | virtual-entity |

Polymer releases relevant here: `0.18.1+26.3` and `0.18.0+26.3-rc-1` on Modrinth for 26.3;
`0.17.5+26.2` for 26.2, which is what `libs/polymer` (branch `dev/26.2`) contains and what the
Kalic0 Modpack profile runs.

## Reference: how the stump displays today

`TreeChopper.updateStumpVisual` replaces the base log with `chopt:shrinking_stump`, stores the
stripped variant on the block entity with the original's axis copied across
(`ShrinkingStumpBlockEntity.copyAxis`, `:56`), and pushes it to tracking players through
`ChoptNetworking.syncShrinkingStump` (`:33`). `ShrinkingStumpBlock.getRenderShape` returns `MODEL`
(`:53`) so particles and picking work, while the visible geometry comes entirely from
`ShrinkingStumpRenderer`, which resolves the stored state's baked model and scales it on X and Z
(`:59`–`:66`). Collision, outline and visual shape all shrink in step through `shapeFor`
(`:91`–`:106`), and stage-to-scale is `Mth.lerp(stage / stages, 1.0f, 0.1f)` (`:84`).

The block is registered as a real entry in `BuiltInRegistries.BLOCK` with a real block entity type
(`ChoptBlocks.java:43`–`:44`), which is the root of the problem this document addresses.
