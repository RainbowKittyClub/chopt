package club.rainbowkitty.chopt.display;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.chopt.block.FellingLogBlock;
import club.rainbowkitty.chopt.block.FellingLogBlockEntity;
import club.rainbowkitty.chopt.pack.FellingModels;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Stageable;

/**
 * What a felling log looks like: the stripped log, narrower with each phase, drawn inside the
 * barrier every client sees. It is built before the block entity exists, so a conversion hands it
 * the species directly and a chunk load fills it in on its first tick. A quarter group is drawn
 * once, by its northwest cell, as four block displays squeezed toward the middle of the group. A
 * viewer with the server's pack is shown the species' cropped model instead, in an item display, so
 * the texture keeps its scale and a small log is not the chorus plant that a block display of it
 * shows; a species with no model is a block display for everyone.
 */
public final class FellingHolder extends ElementHolder {
    // A display's view range is 64 blocks times this and its own scale, which shrinks with the
    // phases; a generous value keeps a thin log from vanishing while its barrier cell stands.
    private static final float VIEW_RANGE = 2.0F;

    private final FellingLogBlock.@Nullable Pending staged;
    private @Nullable Species species;
    private final List<ViewerBlockDisplay> shown = new ArrayList<>();
    private @Nullable ViewerItemDisplay cropped;
    // Whether the pack had cropped models for this log when it was last drawn. The pack is built
    // after the server starts, so a log can be drawn before its models exist.
    private boolean cropKnown;
    private int drawnPhase = -1;

    /**
     * What is known of the log being drawn: the family, the stripped look of each cell, and the
     * block the pack's cropped models would be under, null for a quarter group's followers.
     */
    private record Species(Family family, List<BlockState> stripped, @Nullable Identifier block) {}

    /**
     * Creates the holder for a felling log; {@code staged} is the data of a conversion in
     * progress, or null when a chunk is loading the block.
     */
    public FellingHolder(FellingLogBlock.@Nullable Pending staged) {
        this.staged = staged;
    }

    @Override
    public void notifyUpdate(HolderAttachment.UpdateType type) {
        super.notifyUpdate(type);
        if (type == BlockAwareAttachment.BLOCK_STATE_UPDATE) {
            redraw();
        }
    }

    @Override
    protected void onTick() {
        super.onTick();
        redraw();
    }

    // Draws the log again if its phase is not the one on show.
    private void redraw() {
        if (!(getAttachment() instanceof BlockAwareAttachment attachment)
                || !(attachment.getBlockState().getBlock() instanceof FellingLogBlock)) {
            return;
        }
        BlockState state = attachment.getBlockState();
        int phase = state.getValue(FellingLogBlock.PHASE);
        if (species == null) {
            species = learn(attachment.getBlockPos(), attachment.getWorld());
        }
        if (species == null) {
            return;
        }
        boolean crop = species.block() != null
                && FellingModels.defines(species.block(), species.family());
        if (crop != cropKnown) {
            clear();
            cropKnown = crop;
        }
        if (phase == drawnPhase) {
            return;
        }
        drawnPhase = phase;
        draw(species, state.getValue(FellingLogBlock.AXIS), phase);
    }

    // The species of the log, from the conversion in progress or else the block entity; null while
    // neither is there yet.
    private @Nullable Species learn(BlockPos pos, ServerLevel level) {
        if (staged != null) {
            return speciesOf(level, pos, staged.family(), staged.stripped());
        }
        if (level.getBlockEntity(pos) instanceof FellingLogBlockEntity entity
                && entity.family() != null && entity.stripped() != null) {
            return speciesOf(level, pos, entity.family(), entity.stripped());
        }
        return null;
    }

    // The species of one log, or of the group it heads when it is a quarter log.
    private @Nullable Species speciesOf(ServerLevel level, BlockPos pos, Family family,
            BlockState stripped) {
        return family == Family.QUARTER ? quarterSpecies(level, pos)
                : new Species(family, List.of(stripped), blockOf(stripped));
    }

    private static Identifier blockOf(BlockState stripped) {
        return BuiltInRegistries.BLOCK.getKey(stripped.getBlock());
    }

    // Takes every element down, so the log is drawn from nothing the next time.
    private void clear() {
        shown.forEach(this::removeElement);
        shown.clear();
        if (cropped != null) {
            removeElement(cropped);
            cropped = null;
        }
        drawnPhase = -1;
    }

    // A quarter group's four stripped looks, northwest first, or null until every cell is staged.
    // A cell that is not the northwest one draws nothing.
    private @Nullable Species quarterSpecies(ServerLevel level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof FellingLogBlockEntity self)
                || self.original() == null) {
            return null;
        }
        Stageable.Corner corner = Stageable.barkSide(self.original());
        if (corner == null) {
            return null;
        }
        if (corner != Stageable.Corner.NORTHWEST) {
            return new Species(Family.QUARTER, List.of(), null);
        }
        List<BlockState> looks = new ArrayList<>();
        for (BlockPos cell : Stageable.quarterCells(pos, corner)) {
            if (!(level.getBlockEntity(cell) instanceof FellingLogBlockEntity other)
                    || other.stripped() == null) {
                return null;
            }
            looks.add(other.stripped());
        }
        return new Species(Family.QUARTER, looks, blockOf(looks.get(0)));
    }

    // Sets the elements for one phase: built the first time, then only moved and resized, so a
    // phase change is a snap and not a new entity.
    private void draw(Species looks, Direction.Axis axis, int phase) {
        if (looks.stripped().isEmpty()) {
            return;
        }
        if (looks.family() == Family.QUARTER) {
            float s = Family.QUARTER.width(phase) / 32.0F;
            float[][] cells = {{-1, -1}, {0, -1}, {0, 0}, {-1, 0}};
            // The holder sits at the northwest cell's centre, and the group's middle is the corner
            // it shares with the other three: half a block east and south of that.
            for (int i = 0; i < 4; i++) {
                place(looks, i, looks.stripped().get(i), new Vector3f(s, 1.0F, s),
                        new Vector3f(0.5F + cells[i][0] * s, -0.5F, 0.5F + cells[i][1] * s));
            }
            crop(looks, Direction.Axis.Y, phase, new Vec3(0.5, 0.0, 0.5));
            return;
        }
        float s = looks.family().width(phase) / (looks.family() == Family.SMALL ? 10.0F : 16.0F);
        Vector3f scale = switch (axis) {
            case X -> new Vector3f(1.0F, s, s);
            case Y -> new Vector3f(s, 1.0F, s);
            case Z -> new Vector3f(s, s, 1.0F);
        };
        place(looks, 0, looks.stripped().get(0), scale,
                new Vector3f(-scale.x / 2.0F, -scale.y / 2.0F, -scale.z / 2.0F));
        crop(looks, axis, phase, Vec3.ZERO);
    }

    // Block display {@code index}: made if it is not there yet, and set to this scale and shift.
    private void place(Species looks, int index, BlockState look, Vector3f scale, Vector3f shift) {
        ViewerBlockDisplay element;
        if (index < shown.size()) {
            element = shown.get(index);
        } else {
            element = new ViewerBlockDisplay(look);
            element.setViewRange(VIEW_RANGE);
            if (cropKnown) {
                element.viewers = viewer -> !hasPack(viewer);
            }
            shown.add(element);
            addElement(element);
        }
        element.setScale(scale);
        element.setTranslation(shift);
    }

    // The cropped model for viewers with the pack: an item display made the first time and then
    // given the next phase's model. The models are upright and centred on the display, and a log
    // along another axis is turned to it. Item displays draw facing the other way, hence the yaw.
    private void crop(Species looks, Direction.Axis axis, int phase, Vec3 offset) {
        if (!cropKnown || looks.block() == null) {
            return;
        }
        if (cropped == null) {
            cropped = new ViewerItemDisplay();
            cropped.setItemDisplayContext(ItemDisplayContext.NONE);
            cropped.setYaw(180.0F);
            cropped.setOffset(offset);
            cropped.setViewRange(VIEW_RANGE);
            // The turns of vanilla's x:90,y:90 (along x) and x:90 (along z), seen through the item
            // display's own half turn.
            cropped.setLeftRotation(switch (axis) {
                case X -> new Quaternionf().rotationY(-Mth.HALF_PI).rotateX(Mth.HALF_PI);
                case Z -> new Quaternionf().rotateX(Mth.HALF_PI);
                case Y -> new Quaternionf();
            });
            cropped.viewers = FellingHolder::hasPack;
            addElement(cropped);
        }
        cropped.setItem(FellingModels.stack(looks.block(), phase));
    }

    private static boolean hasPack(ServerPlayer viewer) {
        return PolymerResourcePackUtils.hasMainPack(viewer.connection);
    }

    // A block display that only the viewers it is meant for are sent. Its updates still go to every
    // watcher of the holder, which a client ignores for an entity it was never told of.
    private static final class ViewerBlockDisplay extends BlockDisplayElement {
        Predicate<ServerPlayer> viewers = viewer -> true;

        ViewerBlockDisplay(BlockState look) {
            super(look);
        }

        @Override
        public void startWatching(ServerPlayer player,
                Consumer<Packet<ClientGamePacketListener>> packets) {
            if (viewers.test(player)) {
                super.startWatching(player, packets);
            }
        }
    }

    // The same for an item display.
    private static final class ViewerItemDisplay extends ItemDisplayElement {
        Predicate<ServerPlayer> viewers = viewer -> true;

        ViewerItemDisplay() {
            super();
        }

        @Override
        public void startWatching(ServerPlayer player,
                Consumer<Packet<ClientGamePacketListener>> packets) {
            if (viewers.test(player)) {
                super.startWatching(player, packets);
            }
        }
    }
}
