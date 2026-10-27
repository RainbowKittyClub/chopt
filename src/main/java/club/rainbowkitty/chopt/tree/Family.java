package club.rainbowkitty.chopt.tree;

/**
 * The shapes of log that are staged, each with its phase count and the width of every phase. Phase
 * {@code p} is {@code fullWidth - 2p} pixels across, so the first one starts 2 px in from the log
 * that vanilla breaks first, and the last is 2 px.
 */
public enum Family {
    /** A plain 16 px log of any axis. */
    PILLAR(7, 16),
    /** A 10 px small log, drawn as a column along its main axis. */
    SMALL(4, 10),
    /** A 2x2 trunk of quarter logs, drawn as one 32 px column. */
    QUARTER(15, 32);

    private final int phases;
    private final int fullWidth;

    Family(int phases, int fullWidth) {
        this.phases = phases;
        this.fullWidth = fullWidth;
    }

    /** How many phases a felling goes through. */
    public int phases() {
        return phases;
    }

    /** The width in pixels of phase {@code phase}, counted from 1. */
    public int width(int phase) {
        return fullWidth - 2 * phase;
    }

    /**
     * The phase shown during stage {@code stage} of a felling that has {@code stages} stages, both
     * counted from 1. A felling with fewer stages than the family has phases skips some, spread
     * evenly: the first stage shows the first phase and the last shows the last, so the trunk
     * still goes from its widest to its thinnest.
     */
    public int phaseAt(int stage, int stages) {
        if (stages <= 1) {
            return 1;
        }
        return 1 + ((stage - 1) * (phases - 1) + (stages - 1) / 2) / (stages - 1);
    }

    /** The stage during which {@code phase} is shown: the inverse of {@link #phaseAt}. */
    public int stageAt(int phase, int stages) {
        if (stages <= 1) {
            return 1;
        }
        return 1 + ((phase - 1) * (stages - 1) + (phases - 1) / 2) / (phases - 1);
    }
}
