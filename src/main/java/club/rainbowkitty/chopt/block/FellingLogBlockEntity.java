package club.rainbowkitty.chopt.block;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

import club.rainbowkitty.chopt.tree.Family;

/**
 * What a felling log remembers: the log it was, how far the felling has come, and what it has
 * cost the tool so far. Everything is saved with the block, so a chop survives a restart and a
 * chunk unload. A quarter group is four of these; the northwest cell's carries the progress.
 */
public final class FellingLogBlockEntity extends BlockEntity {
    private static final String FAMILY = "family";
    private static final String PHASES = "phases";
    private static final String ORIGINAL = "original";
    private static final String STRIPPED = "stripped";
    private static final String LOGS_ABOVE = "logs_above";
    private static final String PROGRESS = "progress";
    private static final String LAST_HIT = "last_hit";
    private static final String PAID = "paid";

    private @Nullable Family family;
    private int phases = 1;
    private @Nullable BlockState original;
    private @Nullable BlockState stripped;
    private int logsAbove;
    private int progress;
    private long lastHit;
    private int paid;

    public FellingLogBlockEntity(BlockPos pos, BlockState state) {
        super(ChoptBlocks.FELLING_LOG_ENTITY, pos, state);
    }

    // Fills a fresh block entity from the data staged for it.
    void fill(FellingLogBlock.Pending pending) {
        this.family = pending.family();
        this.phases = pending.phases();
        this.original = pending.original();
        this.stripped = pending.stripped();
        this.logsAbove = pending.logsAbove();
    }

    /** The family, or null on a block entity that was never filled. */
    public @Nullable Family family() {
        return family;
    }

    /** How many phases this felling goes through: the family's, or 1 for a short cut. */
    public int phases() {
        return phases;
    }

    /** The unstripped log this was, with its axis; null on a block entity never filled. */
    public @Nullable BlockState original() {
        return original;
    }

    /** The original, or an oak log for a felling log with no data. */
    public BlockState originalOrOak() {
        return original != null ? original : Blocks.OAK_LOG.defaultBlockState();
    }

    /** The stripped log the display is drawn from. */
    public @Nullable BlockState stripped() {
        return stripped;
    }

    /** Logs the cut brings down besides itself, as of the last time it was counted. */
    public int logsAbove() {
        return logsAbove;
    }

    /** Records how many logs the cut brings down besides itself. */
    public void setLogsAbove(int logsAbove) {
        this.logsAbove = logsAbove;
    }

    /** Progress through the current phase, in 1/65536 of it. */
    public int progress() {
        return progress;
    }

    /** Sets the progress through the current phase, in 1/65536 of it. */
    public void setProgress(int progress) {
        this.progress = progress;
    }

    /** The server tick a player last advanced this log. */
    public long lastHit() {
        return lastHit;
    }

    /** Records the server tick a player last advanced this log. */
    public void setLastHit(long lastHit) {
        this.lastHit = lastHit;
    }

    /** Durability charged for this log so far, in blocks. */
    public int paid() {
        return paid;
    }

    /** Records the durability charged for this log so far, in blocks. */
    public void setPaid(int paid) {
        this.paid = paid;
    }

    /** Saves a change to the chunk, which a bare {@code setChanged} would not. */
    public void save() {
        if (level != null) {
            level.blockEntityChanged(worldPosition);
        }
    }

    @Override
    protected void loadAdditional(ValueInput input) {
        super.loadAdditional(input);
        this.family = input.getString(FAMILY).map(FellingLogBlockEntity::familyNamed).orElse(null);
        this.phases = input.getIntOr(PHASES, 1);
        this.original = input.read(ORIGINAL, BlockState.CODEC).orElse(null);
        this.stripped = input.read(STRIPPED, BlockState.CODEC).orElse(null);
        this.logsAbove = input.getIntOr(LOGS_ABOVE, 0);
        this.progress = input.getIntOr(PROGRESS, 0);
        this.lastHit = input.getLongOr(LAST_HIT, 0L);
        this.paid = input.getIntOr(PAID, 0);
    }

    @Override
    protected void saveAdditional(ValueOutput output) {
        super.saveAdditional(output);
        if (family != null) {
            output.putString(FAMILY, family.name());
        }
        output.putInt(PHASES, phases);
        output.storeNullable(ORIGINAL, BlockState.CODEC, original);
        output.storeNullable(STRIPPED, BlockState.CODEC, stripped);
        output.putInt(LOGS_ABOVE, logsAbove);
        output.putInt(PROGRESS, progress);
        output.putLong(LAST_HIT, lastHit);
        output.putInt(PAID, paid);
    }

    // The family a saved name stands for, or null for one a later version dropped.
    private static @Nullable Family familyNamed(String name) {
        try {
            return Family.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
