package club.rainbowkitty.chopt.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.chopt.ChoptSettings;
import club.rainbowkitty.chopt.ledger.Provenance;

/**
 * The logs one cut brings down: the cut cluster and every natural log that hangs from it, by the
 * cheapest path of each log to the ground.
 *
 * @param family the shape of the cut log
 * @param cluster the logs the cut breaks itself: the log, or a quarter log's four cells
 * @param members every log felled, the cluster included, in (y, x, z) order
 */
public record Region(Family family, List<BlockPos> cluster, List<BlockPos> members) {
    // Cost of a step between logs: 10 for a face, 25 for an edge, 40 for a corner.
    private static final int STEP_BASE = 15;
    private static final int STEP_OFFSET = 5;
    // Roots are the rooted logs within this many blocks of the lowest one.
    private static final int ROOT_BAND = 2;
    private static final List<BlockPos> NEIGHBOURS = neighbours();
    private static final Comparator<BlockPos> LOWEST_FIRST = Comparator
            .<BlockPos>comparingInt(BlockPos::getY).thenComparingInt(BlockPos::getX)
            .thenComparingInt(BlockPos::getZ);

    /** A cut that fells nothing beyond itself. */
    public static Region none(Family family) {
        return new Region(family, List.of(), List.of());
    }

    /** The logs above the cut: the region's size minus the log the cut itself breaks. */
    public int n() {
        return Math.max(0, members.size() - 1);
    }

    /** Whether the cut fells a log outside its own cluster, which is what makes it worth it. */
    public boolean stageable() {
        return members.size() > cluster.size();
    }

    /**
     * The region of the cut of the log {@code state} at {@code origin}, of {@code family}. Empty
     * when the log is not natural. Reads only loaded chunks, and stops at the edge of one.
     */
    public static Region of(ServerLevel level, BlockPos origin, BlockState state, Family family) {
        if (!Provenance.isNatural(level, origin)) {
            return none(family);
        }
        if (family == Family.QUARTER) {
            List<BlockPos> group = Stageable.quarterGroup(level, origin, state);
            return group == null ? none(family) : hangFrom(level, group, family);
        }
        return hangFrom(level, List.of(origin), family);
    }

    /**
     * The region of a log already staged, worked out again from the shape it was staged with:
     * the staged cells no longer carry a quarter log's bark or a pillar's family, so the shape
     * comes from {@code family} and the stored {@code original} state.
     */
    public static Region ofStaged(ServerLevel level, BlockPos master, Family family,
            BlockState original) {
        if (!Provenance.isNatural(level, master)) {
            return none(family);
        }
        Stageable.Corner corner = Stageable.barkSide(original);
        if (family == Family.QUARTER && corner != null) {
            return hangFrom(level, Stageable.quarterCells(master, corner), family);
        }
        return hangFrom(level, List.of(master), family);
    }

    // The logs that hang from the cut cluster, by the cheapest path of every log to a root. A cut
    // that would fell more than the most logs one felling may take stages nothing.
    private static Region hangFrom(ServerLevel level, List<BlockPos> cluster, Family family) {
        Set<BlockPos> component = component(level, cluster.get(0));
        if (component == null) {
            return none(family);
        }
        Set<BlockPos> roots = roots(level, component);
        Map<BlockPos, BlockPos> parents = new HashMap<>();
        Map<BlockPos, Integer> costs = cheapestPaths(component, roots, parents);
        if (!costs.keySet().containsAll(cluster)) {
            return none(family);
        }
        // A log is felled when its chain of parents reaches the cluster through natural logs.
        Map<BlockPos, Boolean> hangs = new HashMap<>();
        cluster.forEach(cell -> hangs.put(cell, true));
        List<BlockPos> members = new ArrayList<>();
        for (BlockPos log : component) {
            if (hangsFrom(level, log, parents, hangs)) {
                members.add(log);
            }
        }
        if (members.size() > ChoptSettings.MAX_LOGS.get(level.getServer())) {
            return none(family);
        }
        members.sort(LOWEST_FIRST);
        return new Region(family, cluster, members);
    }

    // Every log connected to the origin, or null when the connection runs into an unloaded chunk
    // or past the scan cap: nothing may be felled on a guess about what is out of sight.
    private static Set<BlockPos> component(ServerLevel level, BlockPos origin) {
        int cap = ChoptSettings.SCAN_CAP.get(level.getServer());
        Set<BlockPos> component = new LinkedHashSet<>();
        Deque<BlockPos> queue = new ArrayDeque<>();
        component.add(origin);
        queue.add(origin);
        while (!queue.isEmpty()) {
            BlockPos current = queue.poll();
            for (BlockPos offset : NEIGHBOURS) {
                BlockPos next = current.offset(offset);
                if (!level.hasChunkAt(next)) {
                    return null;
                }
                if (!component.contains(next) && level.getBlockState(next).is(BlockTags.LOGS)) {
                    if (component.size() >= cap) {
                        return null;
                    }
                    component.add(next);
                    queue.add(next);
                }
            }
        }
        return component;
    }

    // The logs that stand on the ground: those with a sturdy non-log block below, limited to the
    // lowest ones so a branch resting on a slope does not count. With none, the lowest logs.
    private static Set<BlockPos> roots(ServerLevel level, Set<BlockPos> component) {
        List<BlockPos> rooted = new ArrayList<>();
        for (BlockPos log : component) {
            BlockPos below = log.below();
            BlockState support = level.getBlockState(below);
            if (!support.is(BlockTags.LOGS) && support.isFaceSturdy(level, below, Direction.UP)) {
                rooted.add(log);
            }
        }
        Set<BlockPos> roots = new HashSet<>();
        if (rooted.isEmpty()) {
            int lowest = component.stream().mapToInt(BlockPos::getY).min().orElse(0);
            component.stream().filter(log -> log.getY() == lowest).forEach(roots::add);
            return roots;
        }
        int lowest = rooted.stream().mapToInt(BlockPos::getY).min().orElse(0);
        rooted.stream().filter(log -> log.getY() <= lowest + ROOT_BAND).forEach(roots::add);
        return roots;
    }

    // Dijkstra from every root at once. A step costs 15 per block of distance less 5; of equal
    // paths the one through the lowest (y, x, z) parent wins, which fixes the result.
    private static Map<BlockPos, Integer> cheapestPaths(Set<BlockPos> component,
            Set<BlockPos> roots, Map<BlockPos, BlockPos> parents) {
        Map<BlockPos, Integer> costs = new HashMap<>();
        Set<BlockPos> settled = new HashSet<>();
        PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator
                .comparingInt(Visit::cost).thenComparing(Visit::pos, LOWEST_FIRST));
        for (BlockPos root : roots) {
            costs.put(root, 0);
            queue.add(new Visit(root, 0));
        }
        while (!queue.isEmpty()) {
            Visit visit = queue.poll();
            if (visit.cost() != costs.get(visit.pos()) || !settled.add(visit.pos())) {
                continue;
            }
            for (BlockPos offset : NEIGHBOURS) {
                BlockPos next = visit.pos().offset(offset);
                if (!component.contains(next)) {
                    continue;
                }
                int cost = visit.cost() + stepCost(offset);
                Integer known = costs.get(next);
                if (known == null || cost < known) {
                    costs.put(next, cost);
                    parents.put(next, visit.pos());
                    queue.add(new Visit(next, cost));
                } else if (cost == known && !roots.contains(next)
                        && LOWEST_FIRST.compare(visit.pos(), parents.get(next)) < 0) {
                    parents.put(next, visit.pos());
                }
            }
        }
        return costs;
    }

    // Whether log hangs from the origin: walk up its parents through natural logs, remembering
    // the answer for every log on the way.
    private static boolean hangsFrom(ServerLevel level, BlockPos log,
            Map<BlockPos, BlockPos> parents, Map<BlockPos, Boolean> hangs) {
        List<BlockPos> path = new ArrayList<>();
        BlockPos current = log;
        boolean result;
        while (true) {
            Boolean known = hangs.get(current);
            if (known != null) {
                result = known;
                break;
            }
            BlockPos parent = parents.get(current);
            if (parent == null || !Provenance.isNatural(level, current)) {
                result = false;
                path.add(current);
                break;
            }
            path.add(current);
            current = parent;
        }
        for (BlockPos visited : path) {
            hangs.put(visited, result);
        }
        return result;
    }

    private static int stepCost(BlockPos offset) {
        int distance = Math.abs(offset.getX()) + Math.abs(offset.getY()) + Math.abs(offset.getZ());
        return STEP_BASE * distance - STEP_OFFSET;
    }

    // The 26 offsets around a block.
    private static List<BlockPos> neighbours() {
        List<BlockPos> offsets = new ArrayList<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dy != 0 || dz != 0) {
                        offsets.add(new BlockPos(dx, dy, dz));
                    }
                }
            }
        }
        return List.copyOf(offsets);
    }

    // A log reached at a cost, queued by cost and then by position.
    private record Visit(BlockPos pos, int cost) {}
}
