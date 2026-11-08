package club.rainbowkitty.chopt.compat;

import java.util.List;
import java.util.function.Supplier;

import club.rainbowkitty.kalic0re.common.positiondata.PositionDataCodecs;

/** Declares that an empty placed-log ledger stays empty when block lifecycle hooks are skipped. */
public final class FaweEmptyPositionData implements Supplier<List<String>> {
    @Override
    public List<String> get() {
        return PositionDataCodecs.emptyStableOwners();
    }
}
