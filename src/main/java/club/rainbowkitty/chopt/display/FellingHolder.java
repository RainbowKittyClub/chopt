package club.rainbowkitty.chopt.display;

import java.util.ArrayList;
import java.util.List;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.BlockAwareAttachment;
import eu.pb4.polymer.virtualentity.api.attachment.HolderAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.chopt.block.FellingLogBlock;
import club.rainbowkitty.chopt.block.FellingLogBlockEntity;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Stageable;

/**
 * What a felling log looks like: the stripped log, narrower with each phase, drawn inside the
 * barrier every client sees. It is built before the block entity exists, so a conversion hands it
 * the species directly and a chunk load fills it in on its first tick. A quarter group is drawn
 * once, by its northwest cell, as four block displays squeezed toward the shared edge.
 */
public final class FellingHolder extends ElementHolder {
    // A display's view range is 64 blocks times this and its own scale, which shrinks with the
    // phases; a generous value keeps a thin log from vanishing while its barrier cell stands.
    private static final float VIEW_RANGE = 2.0F;

    private final FellingLogBlock.@Nullable Pending staged;
    private @Nullable Species species;
    private final List<BlockDisplayElement> shown = new ArrayList<>();
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
            for (int i = 0; i < 4; i++) {
                place(i, looks.stripped().get(i), new Vector3f(s, 1.0F, s),
                        new Vector3f(cells[i][0] * s, -0.5F, cells[i][1] * s));
            }
            return;
        }
        float s = looks.family().width(phase) / (looks.family() == Family.SMALL ? 10.0F : 16.0F);
        Vector3f scale = switch (axis) {
            case X -> new Vector3f(1.0F, s, s);
            case Y -> new Vector3f(s, 1.0F, s);
            case Z -> new Vector3f(s, s, 1.0F);
        };
        place(0, looks.stripped().get(0), scale,
                new Vector3f(-scale.x / 2.0F, -scale.y / 2.0F, -scale.z / 2.0F));
    }

    // Element {@code index}: made if it is not there yet, and set to this scale and shift.
    private void place(int index, BlockState look, Vector3f scale, Vector3f shift) {
        BlockDisplayElement element;
        if (index < shown.size()) {
            element = shown.get(index);
        } else {
            element = new BlockDisplayElement(look);
            element.setViewRange(VIEW_RANGE);
            shown.add(element);
            addElement(element);
        }
        element.setScale(scale);
        element.setTranslation(shift);
    }
}
