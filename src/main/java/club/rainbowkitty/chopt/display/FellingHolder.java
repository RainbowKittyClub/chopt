package club.rainbowkitty.chopt.display;

import java.lang.reflect.Method;
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
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.chopt.block.FellingLogBlock;
import club.rainbowkitty.chopt.block.FellingLogBlockEntity;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Stageable;

/**
 * What a felling log looks like: the stripped log, narrower with each phase, drawn inside the
 * barrier every client sees. It is built before the block entity exists, so a conversion hands it
 * the species directly and a chunk load fills it in on its first tick. A quarter group is drawn
 * once, by its northwest cell, as four block displays squeezed toward the middle of the group. A
 * small log is a block display for most viewers, but a viewer with the server's pack is shown the
 * item display that polymer-patch-bundle draws small logs with, because a block display of a small
 * log shows the chorus plant it stands in for.
 */
public final class FellingHolder extends ElementHolder {
    // A display's view range is 64 blocks times this and its own scale, which shrinks with the
    // phases; a generous value keeps a thin log from vanishing while its barrier cell stands.
    private static final float VIEW_RANGE = 2.0F;

    // polymer-patch-bundle's small-log item, by reflection: it is not a dependency, and where it is
    // absent a small log keeps its block display.
    private static final @Nullable Method SMALL_LOG_ITEM = ppbMethod("stack", Identifier.class,
            BlockState.class);
    private static final @Nullable Method SMALL_LOG_PARTS = ppbMethod("parts", Identifier.class);

    private final FellingLogBlock.@Nullable Pending staged;
    private @Nullable Species species;
    private final List<ViewerBlockDisplay> shown = new ArrayList<>();
    private @Nullable ViewerItemDisplay column;
    private @Nullable ItemStack columnItem;
    private boolean columnChecked;
    private int drawnPhase = -1;

    /** What is known of the log being drawn: the family and the stripped look of each cell. */
    private record Species(Family family, List<BlockState> stripped) {}

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
        if (species == null || phase == drawnPhase) {
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
                : new Species(family, List.of(stripped));
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
            return new Species(Family.QUARTER, List.of());
        }
        List<BlockState> looks = new ArrayList<>();
        for (BlockPos cell : Stageable.quarterCells(pos, corner)) {
            if (!(level.getBlockEntity(cell) instanceof FellingLogBlockEntity other)
                    || other.stripped() == null) {
                return null;
            }
            looks.add(other.stripped());
        }
        return new Species(Family.QUARTER, looks);
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
                place(i, looks.stripped().get(i), new Vector3f(s, 1.0F, s),
                        new Vector3f(0.5F + cells[i][0] * s, -0.5F, 0.5F + cells[i][1] * s));
            }
            return;
        }
        float s = looks.family().width(phase) / (looks.family() == Family.SMALL ? 10.0F : 16.0F);
        Vector3f scale = switch (axis) {
            case X -> new Vector3f(1.0F, s, s);
            case Y -> new Vector3f(s, 1.0F, s);
            case Z -> new Vector3f(s, s, 1.0F);
        };
        if (looks.family() == Family.SMALL && !columnChecked) {
            columnChecked = true;
            columnItem = smallLogColumn(looks.stripped().get(0), axis);
        }
        place(0, looks.stripped().get(0), scale,
                new Vector3f(-scale.x / 2.0F, -scale.y / 2.0F, -scale.z / 2.0F));
        if (columnItem != null) {
            placeColumn(scale);
        }
    }

    // Block display {@code index}: made if it is not there yet, and set to this scale and shift.
    private void place(int index, BlockState look, Vector3f scale, Vector3f shift) {
        ViewerBlockDisplay element;
        if (index < shown.size()) {
            element = shown.get(index);
        } else {
            element = new ViewerBlockDisplay(look);
            element.setViewRange(VIEW_RANGE);
            if (columnItem != null) {
                element.viewers = viewer -> !hasPack(viewer);
            }
            shown.add(element);
            addElement(element);
        }
        element.setScale(scale);
        element.setTranslation(shift);
    }

    // The small log's column for viewers with the pack: the item display is made the first time,
    // then only resized. It is centred on the block, so the scale needs no shift.
    private void placeColumn(Vector3f scale) {
        if (column == null) {
            column = new ViewerItemDisplay(columnItem);
            column.setItemDisplayContext(ItemDisplayContext.NONE);
            column.setYaw(180.0F);
            column.setViewRange(VIEW_RANGE);
            column.viewers = FellingHolder::hasPack;
            addElement(column);
        }
        column.setScale(scale);
    }

    private static boolean hasPack(ServerPlayer viewer) {
        return PolymerResourcePackUtils.hasMainPack(viewer.connection);
    }

    // The item polymer-patch-bundle draws the stripped small log with, with only the two arms
    // along the axis, or null when it cannot be made. The arm flags are the stack's custom model
    // data, set from the state.
    private static @Nullable ItemStack smallLogColumn(BlockState stripped, Direction.Axis axis) {
        Identifier id = BuiltInRegistries.BLOCK.getKey(stripped.getBlock());
        if (SMALL_LOG_ITEM == null || !drawnByPpb(id)) {
            return null;
        }
        BlockState arms = stripped;
        for (Direction side : Direction.values()) {
            var arm = PipeBlock.PROPERTY_BY_DIRECTION.get(side);
            if (!arms.hasProperty(arm)) {
                return null;
            }
            arms = arms.setValue(arm, side.getAxis() == axis);
        }
        try {
            return (ItemStack) SMALL_LOG_ITEM.invoke(null, id, arms);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    // Whether ppb read this block's arm parts and so put its item model in the pack: it keeps a
    // block it cannot read as a plain full-block stand-in, which has no item to draw.
    private static boolean drawnByPpb(Identifier id) {
        if (SMALL_LOG_PARTS == null) {
            return false;
        }
        try {
            return !((List<?>) SMALL_LOG_PARTS.invoke(null, id)).isEmpty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static @Nullable Method ppbMethod(String name, Class<?>... types) {
        try {
            return Class.forName("me.drex.ppb.block.SmallLogDisplays").getMethod(name, types);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
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

        ViewerItemDisplay(ItemStack item) {
            super(item);
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
