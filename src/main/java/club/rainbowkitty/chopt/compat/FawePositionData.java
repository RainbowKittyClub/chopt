package club.rainbowkitty.chopt.compat;

import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import club.rainbowkitty.kalic0re.common.positiondata.PositionDataCodecs;

/** Supplies the placed-log mark codec from Chopt's relocated library copy when FAWE asks. */
public final class FawePositionData implements Supplier<List<Map<String, Object>>> {
    @Override
    public List<Map<String, Object>> get() {
        return PositionDataCodecs.declarations();
    }
}
