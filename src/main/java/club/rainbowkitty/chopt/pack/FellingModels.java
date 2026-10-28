package club.rainbowkitty.chopt.pack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import eu.pb4.polymer.resourcepack.api.ResourcePackBuilder;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomModelData;
import net.minecraft.world.level.block.Block;

import club.rainbowkitty.chopt.Chopt;
import club.rainbowkitty.chopt.display.Cracks;
import club.rainbowkitty.chopt.tree.Family;
import club.rainbowkitty.chopt.tree.Stageable;

/**
 * The pack models a felling log is drawn with for a viewer who has the server's pack: for each
 * species of stripped log, one cropped model for each width it narrows through. A block display
 * squeezes the log's texture into the narrow width; these models keep the texel size and show the
 * middle of the texture, as a log cut thinner would. The models are found by following each log
 * block's own blockstate and model through the pack being built, so any mod's log whose model is
 * the usual cube, quarter log or small log is covered without being named. A plain log is covered
 * twice, standing alone and as one of the four of a 2x2 trunk. A species whose model is anything
 * else is left out and drawn as a block display for everyone.
 */
public final class FellingModels {
    private static final Identifier AFTER_DEFAULT = Chopt.id("felling_models");
    private static final int DEPTH = 16;
    private static final double EPSILON = 0.1;
    private static final List<String> LOG_WORDS = List.of("log", "wood", "stem", "hyphae");
    private static final Identifier QUARTER_PARENT =
            Identifier.parse("terraform:block/quarter_log");
    private static final List<String> SIDES =
            List.of("up", "down", "north", "south", "east", "west");
    private static final String MODELS = "assets/chopt/models/block/felling/";
    // The start of the names of the shared parents, which the width in pixels ends: a column, a
    // trunk of Terraform's quarter logs and a trunk of plain logs.
    private static final String COLUMNS = "chopt:block/felling/w";
    private static final String QUARTERS = "chopt:block/felling/quarter/w";
    private static final String SQUARES = "chopt:block/felling/square/w";

    // The species that have models, by block id, in registry order, each with the families it is
    // drawn as and the start of its parents' names; replaced whole by each build.
    private static volatile Map<Identifier, Map<Family, String>> defined = Map.of();

    private final ResourcePackBuilder builder;
    private final List<ModContainer> mods =
            new ArrayList<>(FabricLoader.getInstance().getAllMods());
    private final Map<Identifier, Map<Family, String>> found = new LinkedHashMap<>();
    private final List<String> skipped = new ArrayList<>();

    private FellingModels(ResourcePackBuilder builder) {
        this.builder = builder;
    }

    /** Has every main-pack build also carry the felling models, after the default-phase tasks. */
    public static void register() {
        Cracks.init();
        Event<Consumer<ResourcePackBuilder>> event =
                PolymerResourcePackUtils.RESOURCE_PACK_AFTER_INITIAL_CREATION_EVENT;
        event.addPhaseOrdering(Event.DEFAULT_PHASE, AFTER_DEFAULT);
        event.register(AFTER_DEFAULT, FellingModels::build);
    }

    /** Whether the pack carries models for this stripped log block, drawn as {@code family}. */
    public static boolean defines(Identifier block, Family family) {
        Map<Family, String> families = defined.get(block);
        return families != null && families.containsKey(family);
    }

    /** The families the last pack build gave models, by species. */
    public static Map<Identifier, Map<Family, String>> species() {
        return defined;
    }

    /**
     * The parent model of the cropped model that {@code block} has as {@code family} when
     * {@code width} pixels across, or null when it has none. The parent holds the shape, which a
     * crack over the log copies.
     */
    public static @Nullable Identifier parent(Identifier block, Family family, int width) {
        Map<Family, String> families = defined.get(block);
        return families == null || !families.containsKey(family) ? null
                : Identifier.parse(families.get(family) + width);
    }

    /**
     * The item that shows the cropped model of {@code block} at phase {@code phase}, counted from
     * 1: a stack whose item model is the species' definition for {@code family} and whose first
     * model number picks the phase.
     */
    public static ItemStack stack(Identifier block, Family family, int phase) {
        ItemStack stack = new ItemStack(Items.TRIAL_KEY);
        stack.set(DataComponents.ITEM_MODEL, Chopt.id("felling/" + key(block, family)));
        stack.set(DataComponents.CUSTOM_MODEL_DATA,
                new CustomModelData(List.of((float) phase), List.of(), List.of(), List.of()));
        return stack;
    }

    // The path a species' models and item definition go under: a trunk of four logs apart from
    // the single log of the same block.
    private static String key(Identifier block, Family family) {
        return (family == Family.GROUP ? "group/" : "") + block.getNamespace() + "/"
                + block.getPath();
    }

    private static void build(ResourcePackBuilder builder) {
        try {
            FellingModels models = new FellingModels(builder);
            models.writeParents();
            for (Map.Entry<ResourceKey<Block>, Block> entry : BuiltInRegistries.BLOCK.entrySet()) {
                models.consider(entry.getKey().identifier(), entry.getValue());
            }
            defined = Map.copyOf(models.found);
            Chopt.LOGGER.info("Felling models for {} species of log; left out: {}",
                    models.found.size(),
                    models.skipped.isEmpty() ? "none" : String.join(", ", models.skipped));
        } catch (Throwable e) {
            defined = Map.of();
            Chopt.LOGGER.error("No felling models; every viewer gets block displays", e);
        }
    }

    // The shared parents: a square column of each even width, and a trunk of each of both kinds.
    // The cracks over a log copy the shape of its parent, so each is asked for as it is written.
    private void writeParents() {
        for (int width = 2; width <= 14; width += 2) {
            write(MODELS + "w" + width + ".json", pillarParent(width));
            Cracks.request(Identifier.parse(COLUMNS + width));
        }
        for (int width = 2; width <= 30; width += 2) {
            write(MODELS + "quarter/w" + width + ".json", quarterParent(width / 2));
            write(MODELS + "square/w" + width + ".json", squareParent(width / 2));
            Cracks.request(Identifier.parse(QUARTERS + width));
            Cracks.request(Identifier.parse(SQUARES + width));
        }
    }

    // One block: its model decides whether it is a log this can draw, and as which family.
    private void consider(Identifier id, Block block) {
        String path = id.getPath();
        // A felling log is drawn from the stripped look of a log, so only those need models.
        if (id.getNamespace().equals(Chopt.MOD_ID) || !path.contains("stripped")
                || LOG_WORDS.stream().noneMatch(path::contains)) {
            return;
        }
        try {
            if (block.getStateDefinition().getProperty("bark_side") != null) {
                add(id, Family.GROUP, QUARTERS, quarterTextures(id));
            } else if (Stageable.isSmall(block.defaultBlockState())) {
                add(id, Family.SMALL, COLUMNS, smallTextures(id));
            } else if (block.getStateDefinition().getProperty("axis") != null) {
                Textures textures = pillarTextures(id);
                add(id, Family.PILLAR, COLUMNS, textures);
                add(id, Family.GROUP, SQUARES, textures);
            }
        } catch (RuntimeException | IOException e) {
            skipped.add(id + " (" + e.getClass().getSimpleName() + ")");
        }
    }

    // Writes the models of a species as a family, or notes it as left out when it has no textures.
    private void add(Identifier id, Family family, String parents, @Nullable Textures textures) {
        if (textures == null) {
            if (!skipped.contains(id.toString())) {
                skipped.add(id.toString());
            }
            return;
        }
        emit(id, family, parents, textures);
        found.computeIfAbsent(id, key -> new EnumMap<>(Family.class)).put(family, parents);
    }

    /** The two texture ids a species' column or trunk is drawn with. */
    private record Textures(Identifier end, Identifier side) {}

    // ---- the pillar, quarter and small shapes ----------------------------------------------

    // A log or wood block that is one full cube whose top and bottom match and whose four sides
    // match, in its upright variant with no turn.
    private @Nullable Textures pillarTextures(Identifier id) throws IOException {
        Identifier model = uprightModel(id);
        Flat flat = model == null ? null : flatten(model);
        if (flat == null || !fullCube(flat.elements(), true)) {
            return null;
        }
        JsonObject faces = faces(flat);
        Identifier end = face(flat, faces, "up");
        Identifier side = face(flat, faces, "north");
        boolean matched = end != null && end.equals(face(flat, faces, "down"))
                && side != null && side.equals(face(flat, faces, "south"))
                && side.equals(face(flat, faces, "east")) && side.equals(face(flat, faces, "west"));
        return matched ? usable(end, side) : null;
    }

    // Terraform's quarter log: one cube with the end on top and bottom, the inside on two sides and
    // the bark on the other two, turned by the variant to each corner. The four corners must be the
    // same model turned a quarter apart.
    private @Nullable Textures quarterTextures(Identifier id) throws IOException {
        JsonObject state = blockstate(id);
        if (state == null || !state.has("variants")) {
            return null;
        }
        Identifier model = null;
        int turns = 0;
        for (var variant : state.getAsJsonObject("variants").entrySet()) {
            if (!variant.getKey().contains("axis=y")) {
                continue;
            }
            JsonObject apply = first(variant.getValue());
            if (apply == null || turned(apply, "x") || apply.has("uvlock")
                    && apply.get("uvlock").getAsBoolean()) {
                return null;
            }
            Identifier here = Identifier.parse(apply.get("model").getAsString());
            if (model != null && !model.equals(here)) {
                return null;
            }
            model = here;
            turns |= 1 << (apply.has("y") ? apply.get("y").getAsInt() / 90 : 0);
        }
        Flat flat = model == null || turns != 0b1111 ? null : flatten(model);
        if (flat == null || !flat.chain().contains(QUARTER_PARENT)
                || !fullCube(flat.elements(), false)) {
            return null;
        }
        JsonObject faces = faces(flat);
        Identifier end = face(flat, faces, "up");
        Identifier side = face(flat, faces, "south");
        Identifier inside = face(flat, faces, "north");
        boolean matched = end != null && end.equals(face(flat, faces, "down"))
                && side != null && side.equals(face(flat, faces, "west"))
                && inside != null && inside.equals(face(flat, faces, "east"));
        return matched ? usable(end, side) : null;
    }

    // A small log: the column its arms and post draw with, from the model of its upward arm in the
    // multipart blockstate.
    private @Nullable Textures smallTextures(Identifier id) throws IOException {
        JsonObject state = blockstate(id);
        if (state == null || !state.has("multipart")) {
            return null;
        }
        Identifier arm = null;
        for (JsonElement part : state.getAsJsonArray("multipart")) {
            JsonObject when = part.getAsJsonObject().has("when")
                    ? part.getAsJsonObject().getAsJsonObject("when") : null;
            JsonObject apply = first(part.getAsJsonObject().get("apply"));
            if (when != null && apply != null && when.size() == 1 && when.has("up")
                    && "true".equals(when.get("up").getAsString())) {
                arm = Identifier.parse(apply.get("model").getAsString());
            }
        }
        Flat flat = arm == null ? null : flatten(arm);
        if (flat == null) {
            return null;
        }
        Identifier end = texture(flat, flat.textures().get("end"));
        Identifier side = texture(flat, flat.textures().get("side"));
        return end == null || side == null ? null : usable(end, side);
    }

    // The texture pair, if both are block textures the pack can show.
    private @Nullable Textures usable(Identifier end, Identifier side) {
        return reachable(end) && reachable(side) ? new Textures(end, side) : null;
    }

    private boolean reachable(Identifier texture) {
        if (!texture.getPath().startsWith("block/")) {
            return false;
        }
        String file = "assets/" + texture.getNamespace() + "/textures/" + texture.getPath()
                + ".png";
        return texture.getNamespace().equals("minecraft") || shipped(file);
    }

    // Whether the pack being built carries this file itself, not only a mod's source for it.
    private boolean shipped(String path) {
        try {
            return builder.getData(path) != null;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ---- reading the pack ------------------------------------------------------------------

    private @Nullable JsonObject blockstate(Identifier id) throws IOException {
        return json("assets/" + id.getNamespace() + "/blockstates/" + id.getPath() + ".json");
    }

    // The model of the upright variant, or null when there is none or it is turned.
    private @Nullable Identifier uprightModel(Identifier id) throws IOException {
        JsonObject state = blockstate(id);
        if (state == null || !state.has("variants")) {
            return null;
        }
        for (var variant : state.getAsJsonObject("variants").entrySet()) {
            if (variant.getKey().contains("axis=y")) {
                JsonObject apply = first(variant.getValue());
                if (apply == null || turned(apply, "x") || turned(apply, "y")) {
                    return null;
                }
                return Identifier.parse(apply.get("model").getAsString());
            }
        }
        return null;
    }

    private static boolean turned(JsonObject apply, String axis) {
        return apply.has(axis) && apply.get(axis).getAsInt() % 360 != 0;
    }

    private static @Nullable JsonObject first(@Nullable JsonElement element) {
        if (element == null) {
            return null;
        }
        JsonElement one = element.isJsonArray() ? element.getAsJsonArray().get(0) : element;
        return one.isJsonObject() && one.getAsJsonObject().has("model")
                ? one.getAsJsonObject() : null;
    }

    /**
     * A model with its parents followed: the textures, child first, the nearest elements, and the
     * models followed, the model itself first.
     */
    private record Flat(Map<String, String> textures, @Nullable JsonArray elements,
            List<Identifier> chain) {}

    private @Nullable Flat flatten(Identifier model) throws IOException {
        Map<String, String> textures = new LinkedHashMap<>();
        List<Identifier> chain = new ArrayList<>();
        JsonArray elements = null;
        Identifier current = model;
        for (int depth = 0; current != null && depth < DEPTH; depth++) {
            JsonObject json = json("assets/" + current.getNamespace() + "/models/"
                    + current.getPath() + ".json");
            if (json == null) {
                return null;
            }
            if (json.has("textures")) {
                for (var entry : json.getAsJsonObject("textures").entrySet()) {
                    String slot = slot(entry.getValue());
                    if (slot != null) {
                        textures.putIfAbsent(entry.getKey(), slot);
                    }
                }
            }
            chain.add(current);
            if (elements == null && json.has("elements")) {
                elements = json.getAsJsonArray("elements");
            }
            current = json.has("parent")
                    ? Identifier.parse(json.get("parent").getAsString()) : null;
        }
        return new Flat(textures, elements, chain);
    }

    // A texture slot is a name, or an object that gives the name as its sprite.
    private static @Nullable String slot(JsonElement value) {
        if (value.isJsonPrimitive()) {
            return value.getAsString();
        }
        if (value.isJsonObject() && value.getAsJsonObject().has("sprite")) {
            return value.getAsJsonObject().get("sprite").getAsString();
        }
        return null;
    }

    // A texture reference resolved through the model's own names, or null.
    private static @Nullable Identifier texture(Flat flat, @Nullable String reference) {
        String value = reference;
        for (int i = 0; value != null && value.startsWith("#") && i < DEPTH; i++) {
            value = flat.textures().get(value.substring(1));
        }
        return value == null || value.startsWith("#") ? null : Identifier.tryParse(value);
    }

    private static JsonObject faces(Flat flat) {
        return flat.elements().get(0).getAsJsonObject().getAsJsonObject("faces");
    }

    private static @Nullable Identifier face(Flat flat, JsonObject faces, String name) {
        JsonElement face = faces.get(name);
        if (face == null || !face.isJsonObject() || !face.getAsJsonObject().has("texture")) {
            return null;
        }
        return texture(flat, face.getAsJsonObject().get("texture").getAsString());
    }

    // One element, a whole block, with the six faces and nothing turned or tinted. A plain cube
    // keeps its faces to a texture, a cull face and the whole-texture uv; a quarter log's carry
    // their own turns.
    private static boolean fullCube(@Nullable JsonArray elements, boolean plain) {
        if (elements == null || elements.size() != 1 || !elements.get(0).isJsonObject()) {
            return false;
        }
        JsonObject element = elements.get(0).getAsJsonObject();
        if (element.has("rotation") || !element.has("from") || !element.has("to")
                || !element.has("faces")) {
            return false;
        }
        for (int i = 0; i < 3; i++) {
            double from = element.getAsJsonArray("from").get(i).getAsDouble();
            double to = element.getAsJsonArray("to").get(i).getAsDouble();
            if (Math.abs(from) > EPSILON || Math.abs(to - 16.0) > EPSILON) {
                return false;
            }
        }
        JsonObject faces = element.getAsJsonObject("faces");
        for (String name : SIDES) {
            if (!faces.has(name) || faces.getAsJsonObject(name).has("tintindex")
                    || plain && !plainFace(faces.getAsJsonObject(name))) {
                return false;
            }
        }
        return faces.size() == SIDES.size();
    }

    private static boolean plainFace(JsonObject face) {
        for (String key : face.keySet()) {
            if (!key.equals("texture") && !key.equals("cullface") && !key.equals("uv")) {
                return false;
            }
        }
        if (face.has("uv")) {
            double[] whole = {0.0, 0.0, 16.0, 16.0};
            for (int i = 0; i < whole.length; i++) {
                if (Math.abs(face.getAsJsonArray("uv").get(i).getAsDouble() - whole[i]) > EPSILON) {
                    return false;
                }
            }
        }
        return true;
    }

    private @Nullable JsonObject json(String path) throws IOException {
        byte[] data = safeData(path);
        for (int i = 0; data == null && i < mods.size(); i++) {
            Optional<java.nio.file.Path> found = mods.get(i).findPath(path);
            if (found.isPresent() && Files.isRegularFile(found.get())) {
                data = Files.readAllBytes(found.get());
            }
        }
        if (data == null) {
            return null;
        }
        return JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private byte @Nullable [] safeData(String path) {
        try {
            return builder.getDataOrSource(path);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- writing the models ----------------------------------------------------------------

    // The models and item definition of one species as one family.
    private void emit(Identifier id, Family family, String parents, Textures textures) {
        String key = key(id, family);
        List<String> entries = new ArrayList<>();
        for (int phase = 1; phase <= family.phases(); phase++) {
            write(MODELS + key + "/" + phase + ".json", "{\"parent\":\"" + parents
                    + family.width(phase) + "\",\"textures\":{\"end\":\"" + textures.end()
                    + "\",\"side\":\"" + textures.side() + "\"}}");
            entries.add("{\"threshold\":" + phase + ",\"model\":" + modelOf(key, phase) + "}");
        }
        write("assets/chopt/items/felling/" + key + ".json",
                "{\"model\":{\"type\":\"minecraft:range_dispatch\","
                        + "\"property\":\"minecraft:custom_model_data\",\"index\":0,\"entries\":["
                        + String.join(",", entries) + "],\"fallback\":" + modelOf(key, 1) + "}}");
    }

    // The item model that shows a species' model for a phase.
    private static String modelOf(String key, int phase) {
        return "{\"type\":\"minecraft:model\",\"model\":\"chopt:block/felling/" + key + "/"
                + phase + "\"}";
    }

    private void write(String path, String json) {
        builder.addData(path, json.getBytes(StandardCharsets.UTF_8));
    }

    // A square column {@code width} pixels across, over the whole block's height: the middle of the
    // end and side textures at their own scale.
    private static String pillarParent(int width) {
        int c = (16 - width) / 2;
        int far = 16 - c;
        String cap = "\"uv\":[" + c + "," + c + "," + far + "," + far + "],\"texture\":\"#end\"";
        return "{\"textures\":{\"particle\":\"#side\"},\"elements\":[{\"from\":[" + c + ",0," + c
                + "],\"to\":[" + far + ",16," + far + "],\"faces\":{\"down\":{" + cap + "},\"up\":{"
                + cap + "}," + column("north", c, far) + "," + column("south", c, far) + ","
                + column("west", c, far) + "," + column("east", c, far) + "}}]}";
    }

    private static String column(String face, int c, int far) {
        return "\"" + face + "\":{\"uv\":[" + c + ",0," + far + ",16],\"texture\":\"#side\"}";
    }

    // A quarter trunk whose half width is {@code h}: four quadrants about the middle of the block,
    // each showing the outer window of the texture on its two outer sides and its quarter of the
    // top and bottom. The windows are those of the real quarter-log bake.
    private static String quarterParent(int h) {
        int lo = 16 - h;
        String a = "[" + lo + ",0,16,16]";
        String b = "[16,16," + lo + ",0]";
        String top = "[" + lo + ",0,16," + h + "]";
        String flipped = "[16," + h + "," + lo + ",0]";
        return "{\"textures\":{\"particle\":\"#side\"},\"elements\":["
                + quadrant(8 - h, 8 - h, 8, 8, face("up", top, 90), face("down", top, 0),
                        face("north", b, 0), face("west", a, 0)) + ","
                + quadrant(8, 8 - h, 8 + h, 8, face("up", flipped, 0), face("down", top, 270),
                        face("north", a, 0), face("east", b, 0)) + ","
                + quadrant(8, 8, 8 + h, 8 + h, face("up", top, 270), face("down", flipped, 0),
                        face("south", b, 0), face("east", a, 0)) + ","
                + quadrant(8 - h, 8, 8, 8 + h, face("up", top, 0), face("down", top, 90),
                        face("south", a, 0), face("west", b, 0)) + "]}";
    }

    // A trunk of four plain logs whose half width is {@code h}: a box in each quarter about the
    // middle of the block. Each shows, on its two outer sides and its ends, the window of its own
    // block's texture that vanilla would give a box of that size at the block's corner nearest the
    // middle, so the trunk looks like the real one with the outer pixels sliced off.
    private static String squareParent(int h) {
        int lo = 16 - h;
        return "{\"textures\":{\"particle\":\"#side\"},\"elements\":["
                + plainBox(8 - h, 8 - h, lo, lo, 16, 16, "north", "west") + ","
                + plainBox(8, 8 - h, 0, lo, h, 16, "north", "east") + ","
                + plainBox(8, 8, 0, 0, h, h, "south", "east") + ","
                + plainBox(8 - h, 8, lo, 0, 16, h, "south", "west") + "]}";
    }

    // The box at ({@code mx}, {@code mz}) in the model that stands at x0..x1 and z0..z1 of its own
    // block, with the faces named and the ends.
    private static String plainBox(int mx, int mz, int x0, int z0, int x1, int z1,
            String... sides) {
        List<String> faces = new ArrayList<>();
        faces.add(plainFace("up", x0, z0, x1, z1));
        faces.add(plainFace("down", x0, z0, x1, z1));
        for (String side : sides) {
            faces.add(plainFace(side, x0, z0, x1, z1));
        }
        return quadrant(mx, mz, mx + (x1 - x0), mz + (z1 - z0), faces.toArray(String[]::new));
    }

    // One face with vanilla's default uv for a box over the block's whole height.
    private static String plainFace(String name, int x0, int z0, int x1, int z1) {
        int[] uv = switch (name) {
            case "down" -> new int[] {x0, 16 - z1, x1, 16 - z0};
            case "up" -> new int[] {x0, z0, x1, z1};
            case "north" -> new int[] {16 - x1, 0, 16 - x0, 16};
            case "south" -> new int[] {x0, 0, x1, 16};
            case "west" -> new int[] {z0, 0, z1, 16};
            default -> new int[] {16 - z1, 0, 16 - z0, 16};
        };
        return face(name, "[" + uv[0] + "," + uv[1] + "," + uv[2] + "," + uv[3] + "]", 0);
    }

    private static String quadrant(int x0, int z0, int x1, int z1, String... faces) {
        return "{\"from\":[" + x0 + ",0," + z0 + "],\"to\":[" + x1 + ",16," + z1
                + "],\"faces\":{" + String.join(",", faces) + "}}";
    }

    private static String face(String name, String uv, int rotation) {
        boolean vertical = name.equals("up") || name.equals("down");
        return "\"" + name + "\":{\"uv\":" + uv + ",\"texture\":\""
                + (vertical ? "#end" : "#side") + "\""
                + (rotation == 0 ? "" : ",\"rotation\":" + rotation) + "}";
    }
}
