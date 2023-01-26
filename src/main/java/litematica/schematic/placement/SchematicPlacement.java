package litematica.schematic.placement;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import com.google.common.collect.ImmutableMap;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.longs.LongSet;

import net.minecraft.util.math.BlockPos;

import malilib.overlay.message.MessageDispatcher;
import malilib.util.FileNameUtils;
import malilib.util.FileUtils;
import malilib.util.StringUtils;
import malilib.util.data.Color4f;
import malilib.util.data.EnabledCondition;
import malilib.util.data.json.JsonUtils;
import malilib.util.position.IntBoundingBox;
import litematica.Litematica;
import litematica.data.DataManager;
import litematica.data.SchematicHolder;
import litematica.materials.MaterialListBase;
import litematica.materials.MaterialListPlacement;
import litematica.schematic.ISchematic;
import litematica.schematic.ISchematicRegion;
import litematica.schematic.verifier.SchematicVerifier;
import litematica.selection.CornerDefinedBox;
import litematica.selection.SelectionBox;
import litematica.util.PositionUtils;

public class SchematicPlacement extends BasePlacement
{
    protected static int lastColor;

    protected final Map<String, SubRegionPlacement> subRegionPlacements = new HashMap<>();

    @Nullable protected Path schematicFile;
    @Nullable protected ISchematic schematic;
    @Nullable protected IntBoundingBox enclosingBox;
    @Nullable protected GridSettings gridSettings;
    @Nullable protected String placementSaveFile;
    @Nullable protected String selectedSubRegionName;

    @Nullable protected JsonObject materialListData;
    @Nullable protected MaterialListBase materialList;
    @Nullable protected SchematicVerifier verifier;

    protected boolean locked;
    protected boolean regionPlacementsModified;
    protected boolean repeatedPlacement;
    protected boolean shouldBeSaved = true;
    protected boolean valid = true;
    protected int subRegionCount;
    protected long lastSaveTime = -1;

    protected SchematicPlacement(@Nullable Path schematicFile)
    {
        this(schematicFile, BlockPos.ORIGIN, "?", true);
    }

    protected SchematicPlacement(@Nullable Path schematicFile,
                                 BlockPos origin,
                                 String name,
                                 boolean enabled)
    {
        super(name, origin);

        this.schematicFile = schematicFile;
        this.position = origin;
        this.name = name;
        this.enabled = enabled;

        this.setShouldBeSaved(schematicFile != null);
    }

    protected SchematicPlacement(@Nullable ISchematic schematic,
                                 @Nullable Path schematicFile,
                                 BlockPos origin,
                                 String name,
                                 boolean enabled)
    {
        this(schematicFile, origin, name, enabled);

        this.schematic = schematic;
        this.subRegionCount = schematic != null ? schematic.getSubRegionCount() : 0;
    }

    public boolean isLoaded()
    {
        return this.schematic != null;
    }

    public boolean isLocked()
    {
        return this.locked;
    }

    public boolean isValid()
    {
        return this.valid;
    }

    public boolean isRepeatedPlacement()
    {
        return this.repeatedPlacement;
    }

    public boolean isSavedToFile()
    {
        return this.placementSaveFile != null;
    }

    public int getSubRegionCount()
    {
        return this.subRegionCount;
    }

    public long getLastSaveTime()
    {
        return this.lastSaveTime;
    }

    /**
     * @return true if this placement should be saved by the SchematicPlacementManager
     * when it saves the list of placements.
     */
    public boolean shouldBeSaved()
    {
        return this.shouldBeSaved;
    }

    public boolean isRegionPlacementModified()
    {
        return this.regionPlacementsModified;
    }

    public boolean isSchematicInMemoryOnly()
    {
        return this.schematicFile == null;
    }

    @Nullable
    public ISchematic getSchematic()
    {
        return this.schematic;
    }

    @Nullable
    public Path getSchematicFile()
    {
        return this.schematicFile;
    }

    @Nullable
    public String getSelectedSubRegionName()
    {
        return this.selectedSubRegionName;
    }

    @Nullable
    public SubRegionPlacement getSelectedSubRegionPlacement()
    {
        return this.selectedSubRegionName != null ? this.subRegionPlacements.get(this.selectedSubRegionName) : null;
    }

    public GridSettings getGridSettings()
    {
        if (this.gridSettings == null)
        {
            this.gridSettings = new GridSettings();
            this.updateEnclosingBox();
            this.gridSettings.setDefaultSize(PositionUtils.getAreaSizeFromBox(this.enclosingBox));
        }

        return this.gridSettings;
    }

    public IntBoundingBox getEnclosingBox()
    {
        if (this.enclosingBox == null)
        {
            this.updateEnclosingBox();
        }

        return this.enclosingBox;
    }

    public void setSchematicFile(@Nullable Path schematicFile)
    {
        this.schematicFile = schematicFile;
    }

    public boolean loadAndSetSchematicFromFile(Path file)
    {
        this.schematicFile = file;
        this.schematic = SchematicHolder.getInstance().getOrLoad(file);

        if (this.schematic != null)
        {
            this.resetEnclosingBox();
            this.checkAreSubRegionsModified();
            this.subRegionCount = this.schematic.getSubRegionCount();

            return true;
        }

        return false;
    }

    protected void loadSchematicFromFileIfEnabled()
    {
        if (this.enabled && this.schematicFile != null &&
            this.loadAndSetSchematicFromFile(this.schematicFile) == false)
        {
            this.enabled = false;
        }
    }

    public void setName(String name)
    {
        this.name = name;
    }

    public void setShouldBeSaved(boolean shouldBeSaved)
    {
        this.shouldBeSaved = shouldBeSaved;
    }

    public void toggleRenderEnclosingBox()
    {
        this.renderEnclosingBox = ! this.renderEnclosingBox;
    }

    public void toggleLocked()
    {
        this.locked = ! this.locked;
    }

    void setOrigin(BlockPos origin)
    {
        this.position = origin;
    }

    public void setSelectedSubRegionName(@Nullable String name)
    {
        this.selectedSubRegionName = name;
    }

    public void invalidate()
    {
        this.valid = false;
    }

    public MaterialListBase getMaterialList()
    {
        if (this.materialList == null)
        {
            if (this.materialListData != null)
            {
                this.materialList = MaterialListPlacement.createFromJson(this.materialListData, this);
            }
            else
            {
                this.materialList = new MaterialListPlacement(this, true);
            }
        }

        return this.materialList;
    }

    @Nullable
    public SubRegionPlacement getSubRegion(String areaName)
    {
        return this.subRegionPlacements.get(areaName);
    }

    public List<SubRegionPlacement> getAllSubRegions()
    {
        return new ArrayList<>(this.subRegionPlacements.values());
    }

    public ImmutableMap<String, SubRegionPlacement> getEnabledSubRegions()
    {
        ImmutableMap.Builder<String, SubRegionPlacement> builder = ImmutableMap.builder();

        for (Map.Entry<String, SubRegionPlacement> entry : this.subRegionPlacements.entrySet())
        {
            SubRegionPlacement placement = entry.getValue();

            if (placement.matchesRequirement(EnabledCondition.ENABLED))
            {
                builder.put(entry.getKey(), entry.getValue());
            }
        }

        return builder.build();
    }

    public ImmutableMap<String, SelectionBox> getSubRegionBoxes(EnabledCondition condition)
    {
        if (this.isLoaded() == false)
        {
            return ImmutableMap.of();
        }

        ImmutableMap.Builder<String, SelectionBox> builder = ImmutableMap.builder();
        Map<String, ISchematicRegion> subRegions = this.schematic.getRegions();

        for (Map.Entry<String, SubRegionPlacement> entry : this.subRegionPlacements.entrySet())
        {
            SubRegionPlacement placement = entry.getValue();

            if (placement.matchesRequirement(condition))
            {
                String name = entry.getKey();
                ISchematicRegion region = subRegions.get(name);

                if (region != null)
                {
                    BlockPos boxOriginRelative = placement.getPosition();
                    BlockPos boxOriginAbsolute = PositionUtils.getTransformedBlockPos(boxOriginRelative, this.mirror, this.rotation).add(this.position);
                    BlockPos pos2 = new BlockPos(PositionUtils.getRelativeEndPositionFromAreaSize(region.getSize()));
                    pos2 = PositionUtils.getTransformedBlockPos(pos2, this.mirror, this.rotation);
                    pos2 = PositionUtils.getTransformedBlockPos(pos2, placement.getMirror(), placement.getRotation()).add(boxOriginAbsolute);

                    builder.put(name, new SelectionBox(boxOriginAbsolute, pos2, name));
                }
                else
                {
                    Litematica.logger.warn("SchematicPlacement.getSubRegionBoxes(): Sub-region '{}' not found in the schematic '{}'", name, this.schematic.getMetadata().getName());
                }
            }
        }

        return builder.build();
    }

    public ImmutableMap<String, SelectionBox> getSubRegionBoxFor(String regionName, EnabledCondition condition)
    {
        ImmutableMap.Builder<String, SelectionBox> builder = ImmutableMap.builder();
        SubRegionPlacement placement = this.subRegionPlacements.get(regionName);

        if (this.isLoaded() && placement != null && placement.matchesRequirement(condition))
        {
            Map<String, ISchematicRegion> subRegions = this.schematic.getRegions();
            ISchematicRegion region = subRegions.get(regionName);

            if (region != null)
            {
                BlockPos boxOriginRelative = placement.getPosition();
                BlockPos boxOriginAbsolute = PositionUtils.getTransformedBlockPos(boxOriginRelative, this.mirror, this.rotation).add(this.position);
                BlockPos pos2 = new BlockPos(PositionUtils.getRelativeEndPositionFromAreaSize(region.getSize()));
                pos2 = PositionUtils.getTransformedBlockPos(pos2, this.mirror, this.rotation);
                pos2 = PositionUtils.getTransformedBlockPos(pos2, placement.getMirror(), placement.getRotation()).add(boxOriginAbsolute);

                builder.put(regionName, new SelectionBox(boxOriginAbsolute, pos2, regionName));
            }
            else
            {
                Litematica.logger.warn("SchematicPlacement.getSubRegionBoxFor(): Sub-region '{}' not found in the schematic '{}'", regionName, this.schematic.getMetadata().getName());
            }
        }

        return builder.build();
    }

    public Set<String> getSubRegionNamesTouchingChunk(int chunkX, int chunkZ)
    {
        ImmutableMap<String, SelectionBox> map = this.getSubRegionBoxes(EnabledCondition.ENABLED);
        final int chunkXMin = chunkX << 4;
        final int chunkZMin = chunkZ << 4;
        final int chunkXMax = chunkXMin + 15;
        final int chunkZMax = chunkZMin + 15;
        Set<String> set = new HashSet<>();

        for (Map.Entry<String, SelectionBox> entry : map.entrySet())
        {
            SelectionBox box = entry.getValue();
            final int boxXMin = Math.min(box.getCorner1().getX(), box.getCorner2().getX());
            final int boxZMin = Math.min(box.getCorner1().getZ(), box.getCorner2().getZ());
            final int boxXMax = Math.max(box.getCorner1().getX(), box.getCorner2().getX());
            final int boxZMax = Math.max(box.getCorner1().getZ(), box.getCorner2().getZ());

            boolean notOverlapping = boxXMin > chunkXMax || boxZMin > chunkZMax || boxXMax < chunkXMin || boxZMax < chunkZMin;

            if (notOverlapping == false)
            {
                set.add(entry.getKey());
            }
        }

        return set;
    }

    public ImmutableMap<String, IntBoundingBox> getBoxesWithinChunk(int chunkX, int chunkZ)
    {
        ImmutableMap<String, SelectionBox> subRegions = this.getSubRegionBoxes(EnabledCondition.ENABLED);
        return PositionUtils.getBoxesWithinChunk(chunkX, chunkZ, subRegions);
    }

    @Nullable
    public IntBoundingBox getBoxWithinChunkForRegion(String regionName, int chunkX, int chunkZ)
    {
        CornerDefinedBox box = this.getSubRegionBoxFor(regionName, EnabledCondition.ENABLED).get(regionName);
        return box != null ? PositionUtils.getBoundsWithinChunkForBox(box, chunkX, chunkZ) : null;
    }

    public LongSet getTouchedChunks()
    {
        return PositionUtils.getTouchedChunks(this.getSubRegionBoxes(EnabledCondition.ENABLED));
    }

    protected void checkAreSubRegionsModified()
    {
        if (this.isLoaded() == false)
        {
            return;
        }

        Map<String, ISchematicRegion> subRegions = this.schematic.getRegions();

        if (subRegions.size() != this.subRegionPlacements.size())
        {
            this.regionPlacementsModified = true;
            return;
        }

        for (Map.Entry<String, ISchematicRegion> entry : subRegions.entrySet())
        {
            SubRegionPlacement placement = this.subRegionPlacements.get(entry.getKey());

            if (placement == null || placement.isRegionPlacementModified(entry.getValue().getPosition()))
            {
                this.regionPlacementsModified = true;
                return;
            }
        }

        this.regionPlacementsModified = false;
    }

    protected void resetEnclosingBox()
    {
        this.enclosingBox = null;
    }

    protected void updateEnclosingBox()
    {
        ImmutableMap<String, SelectionBox> boxes = this.getSubRegionBoxes(EnabledCondition.ANY);

        if (boxes.isEmpty())
        {
            this.enclosingBox = IntBoundingBox.ORIGIN;
            return;
        }

        this.enclosingBox = PositionUtils.getEnclosingBox(boxes.values());

        if (this.gridSettings != null)
        {
            this.gridSettings.setDefaultSize(PositionUtils.getAreaSizeFromBox(this.enclosingBox));
        }
    }

    /**
     * Moves the sub-region to the given <b>world position</b>.
     */
    void moveSubRegionTo(String regionName, BlockPos newPos)
    {
        SubRegionPlacement subRegion = this.subRegionPlacements.get(regionName);

        if (subRegion != null)
        {
            // The input argument position is an absolute position, so need to convert to relative position here
            newPos = newPos.subtract(this.position);
            // The absolute-based input position needs to be transformed if the entire placement has been rotated or mirrored
            newPos = PositionUtils.getReverseTransformedBlockPos(newPos, this.mirror, this.rotation);

            subRegion.setPosition(newPos);
            this.resetEnclosingBox();
        }
    }

    void setSubRegionsEnabledState(boolean state, Collection<SubRegionPlacement> subRegions)
    {
        for (SubRegionPlacement subRegion : subRegions)
        {
            // Check that the sub-region is actually from this placement
            subRegion = this.subRegionPlacements.get(subRegion.getName());

            if (subRegion != null)
            {
                subRegion.setEnabled(state);
            }
        }
    }

    void resetSubRegionToSchematicValues(String regionName)
    {
        SubRegionPlacement placement = this.subRegionPlacements.get(regionName);

        if (placement != null)
        {
            placement.resetToOriginalValues();
            this.resetEnclosingBox();
        }
    }

    void resetAllSubRegionsToSchematicValues()
    {
        if (this.isLoaded() == false)
        {
            return;
        }

        this.subRegionPlacements.clear();
        this.regionPlacementsModified = false;

        for (Map.Entry<String, ISchematicRegion> entry : this.schematic.getRegions().entrySet())
        {
            String name = entry.getKey();
            this.subRegionPlacements.put(name, new SubRegionPlacement(entry.getValue().getPosition(), name));
        }

        this.resetEnclosingBox();
    }

    protected void copyBaseSettingsFrom(SchematicPlacement other)
    {
        this.subRegionPlacements.clear();

        other.subRegionPlacements.forEach((key, value) -> this.subRegionPlacements.put(key, value.copy()));

        this.name = other.name;
        this.position = other.position;
        this.rotation = other.rotation;
        this.mirror = other.mirror;
        this.enabled = other.enabled;
        this.ignoreEntities = other.ignoreEntities;

        this.boundingBoxColor = other.boundingBoxColor;
        this.coordinateLockMask = other.coordinateLockMask;
        this.enclosingBox = other.enclosingBox;
        this.locked = other.locked;
        this.regionPlacementsModified = other.regionPlacementsModified;
        this.renderEnclosingBox = other.renderEnclosingBox;
        this.shouldBeSaved = other.shouldBeSaved;
    }

    protected void copyGridSettingsFrom(SchematicPlacement other)
    {
        if (other.gridSettings != null)
        {
            this.getGridSettings().copyFrom(other.gridSettings);
        }
        else
        {
            this.gridSettings = null;
        }
    }

    public SchematicPlacement copy()
    {
        SchematicPlacement copy = new SchematicPlacement(this.schematic, this.schematicFile,
                                                         this.position, this.name, this.enabled);
        copy.copyBaseSettingsFrom(this);
        copy.copyGridSettingsFrom(this);
        return copy;
    }

    public SchematicPlacement createRepeatedCopy()
    {
        SchematicPlacement copy = new SchematicPlacement(this.schematic, this.schematicFile,
                                                         this.position, this.name, this.enabled);
        copy.copyBaseSettingsFrom(this);
        copy.repeatedPlacement = true;
        return copy;
    }

    public SchematicPlacement copyAsUnloaded()
    {
        SchematicPlacement copy = new SchematicPlacement(null, this.schematicFile,
                                                         this.position, this.name, this.enabled);
        copy.copyBaseSettingsFrom(this);
        copy.copyGridSettingsFrom(this);
        return copy;
    }

    public boolean fullyLoadPlacement()
    {
        if (this.schematicFile != null && this.schematic == null)
        {
            if (this.loadAndSetSchematicFromFile(this.schematicFile) == false)
            {
                MessageDispatcher.error().translate("litematica.error.schematic_load.failed",
                                                    this.schematicFile.toAbsolutePath().toString());
            }
        }

        return false;
    }

    protected void setBoundingBoxColorToNext()
    {
        this.setBoundingBoxColor(getNextBoxColor());
    }

    public boolean wasModifiedSinceSaved()
    {
        if (this.placementSaveFile != null)
        {
            Path file = getSaveDirectory().resolve(this.placementSaveFile);
            JsonElement el = JsonUtils.parseJsonFile(file);

            if (el != null && el.isJsonObject())
            {
                JsonObject objOther = el.getAsJsonObject();
                JsonObject objThis = this.toJson();

                // Ignore some stuff that doesn't matter
                this.removeNonImportantPropsForModifiedSinceSavedCheck(objOther);
                this.removeNonImportantPropsForModifiedSinceSavedCheck(objThis);

                return objOther.equals(objThis) == false;
            }

            return true;
        }

        return false;
    }

    public boolean saveToFileIfChanged()
    {
        if (this.shouldBeSaved == false)
        {
            MessageDispatcher.warning().translate("litematica.message.error.schematic_placement.save.should_not_save");
            return false;
        }

        Path file;

        if (this.placementSaveFile != null)
        {
            file = getSaveDirectory().resolve(this.placementSaveFile);
        }
        else
        {
            file = this.getAvailableFileName();
        }

        if (file == null)
        {
            MessageDispatcher.error().translate("litematica.message.error.schematic_placement.save.failed_to_get_save_file");
            return false;
        }

        if (this.placementSaveFile == null || Files.exists(file) == false || this.wasModifiedSinceSaved())
        {
            JsonObject obj = this.toJson();

            if (obj != null)
            {
                return this.saveToFile(file, obj);
            }
            else
            {
                MessageDispatcher.error().translate("litematica.message.error.schematic_placement.save.failed_to_serialize");
                return false;
            }
        }
        else
        {
            MessageDispatcher.warning().translate("litematica.message.error.schematic_placement.save.no_changes");
        }

        return true;
    }

    protected boolean saveToFile(Path file, JsonObject obj)
    {
        obj.addProperty("last_save_time", System.currentTimeMillis());

        if (JsonUtils.writeJsonToFile(obj, file))
        {
            if (this.placementSaveFile == null)
            {
                this.placementSaveFile = file.getFileName().toString();
            }

            MessageDispatcher.generic("litematica.gui.label.schematic_placement.saved_to_file",
                                      file.getFileName().toString());

            return true;
        }

        return false;
    }

    public JsonObject getSettingsShareJson()
    {
        JsonObject obj = this.toJson();
        this.removeNonImportantPlacementPropsForSharing(obj);
        return obj;
    }

    @Override
    @Nullable
    public JsonObject toJson()
    {
        if (this.schematicFile == null)
        {
            // If this placement is for an in-memory-only schematic, then there is no point in saving
            // this placement, as the schematic can't be automatically loaded anyway.
            return null;
        }

        JsonObject obj = super.toJson();

        obj.addProperty("schematic", this.schematicFile.toAbsolutePath().toString());

        JsonUtils.addIfNotEqual(obj, "bb_color", this.boundingBoxColor.intValue, 0);
        JsonUtils.addIfNotEqual(obj, "locked", this.locked, false);
        // The region count is for the lightly loaded placements where it can't be read from the schematic
        JsonUtils.addIfNotEqual(obj, "region_count", this.subRegionCount, 0);
        JsonUtils.addStringIfNotNull(obj, "selected_region", this.selectedSubRegionName);
        JsonUtils.addStringIfNotNull(obj, "storage_file", this.placementSaveFile);

        JsonUtils.addElementIfNotNull(obj, "material_list_data", this.materialListData);

        if (this.gridSettings != null &&
            this.gridSettings.isInitialized() &&
            this.gridSettings.isAtDefaultValues() == false)
        {
            obj.add("grid", this.gridSettings.toJson());
        }

        // FIXME which one is needed?
        if (this.materialList != null)
        {
            obj.add("material_list", this.materialList.toJson());
        }

        if (this.regionPlacementsModified && this.subRegionPlacements.isEmpty() == false)
        {
            JsonArray arr = new JsonArray();

            for (Map.Entry<String, SubRegionPlacement> entry : this.subRegionPlacements.entrySet())
            {
                JsonObject placementObj = new JsonObject();
                placementObj.addProperty("name", entry.getKey());
                placementObj.add("placement", entry.getValue().toJson());
                arr.add(placementObj);
            }

            obj.add("placements", arr);
        }

        return obj;
    }

    protected void removeNonImportantPropsForModifiedSinceSavedCheck(JsonObject obj)
    {
        obj.remove("enabled");
        obj.remove("locked");
        obj.remove("locked_coords");
        obj.remove("material_list");
        obj.remove("render_enclosing_box");
        obj.remove("selected_region");
        obj.remove("storage_file");
        obj.remove("last_save_time");
    }

    protected void removeNonImportantPlacementPropsForSharing(JsonObject obj)
    {
        this.removeNonImportantPropsForModifiedSinceSavedCheck(obj);

        obj.remove("bb_color");
        obj.remove("region_count");
        obj.remove("schematic");

        if (this.schematic != null && this.schematic.getMetadata().getName().equals(this.name))
        {
            obj.remove("name");
        }
    }

    protected JsonObject getSharedSettingsPropertiesToLoad(JsonObject objIn)
    {
        JsonObject obj = new JsonObject();

        JsonUtils.copyPropertyIfExists(objIn, obj, "name");
        JsonUtils.copyPropertyIfExists(objIn, obj, "pos");
        JsonUtils.copyPropertyIfExists(objIn, obj, "origin");
        JsonUtils.copyPropertyIfExists(objIn, obj, "rotation");
        JsonUtils.copyPropertyIfExists(objIn, obj, "mirror");
        JsonUtils.copyPropertyIfExists(objIn, obj, "ignore_entities");
        JsonUtils.copyPropertyIfExists(objIn, obj, "placements");
        JsonUtils.copyPropertyIfExists(objIn, obj, "grid");

        return obj;
    }

    public boolean loadFromSharedSettings(JsonObject obj)
    {
        obj = this.getSharedSettingsPropertiesToLoad(obj);
        return this.readFromJson(obj);
    }

    public boolean readFromJson(JsonObject obj)
    {
        String originKey = obj.has("pos") ? "pos" : "origin";
        BlockPos origin = JsonUtils.getBlockPos(obj, originKey);

        if (origin == null)
        {
            MessageDispatcher.error().translate("litematica.error.schematic_placements.settings_load.missing_data");
            String name = this.schematicFile != null ? this.schematicFile.toAbsolutePath().toString() : "<null>";
            Litematica.logger.warn("Failed to load schematic placement for '{}', invalid origin position", name);
            return false;
        }

        this.position = origin;
        this.name = JsonUtils.getStringOrDefault(obj, "name", this.name);
        this.rotation = JsonUtils.getRotation(obj, "rotation");
        this.mirror = JsonUtils.getMirror(obj, "mirror");
        this.ignoreEntities = JsonUtils.getBooleanOrDefault(obj, "ignore_entities", this.ignoreEntities);
        this.coordinateLockMask = JsonUtils.getIntegerOrDefault(obj, "locked_coords", this.coordinateLockMask);

        this.enabled = JsonUtils.getBooleanOrDefault(obj, "enabled", this.enabled);
        this.lastSaveTime = JsonUtils.getLongOrDefault(obj, "last_save_time", this.lastSaveTime);
        this.locked = JsonUtils.getBooleanOrDefault(obj, "locked", this.locked);
        this.placementSaveFile = JsonUtils.getStringOrDefault(obj, "storage_file", this.placementSaveFile);
        this.renderEnclosingBox = JsonUtils.getBooleanOrDefault(obj, "render_enclosing_box", this.renderEnclosingBox);
        this.selectedSubRegionName = JsonUtils.getStringOrDefault(obj, "selected_region", this.selectedSubRegionName);
        this.subRegionCount = JsonUtils.getIntegerOrDefault(obj, "region_count", this.subRegionCount);

        this.setBoundingBoxColor(JsonUtils.getIntegerOrDefault(obj, "bb_color", this.boundingBoxColor.intValue));
        JsonUtils.getObjectIfExists(obj, "material_list", o -> this.materialListData = o);

        if (JsonUtils.hasArray(obj, "placements"))
        {
            JsonArray placementArr = obj.get("placements").getAsJsonArray();

            for (int i = 0; i < placementArr.size(); ++i)
            {
                JsonElement el = placementArr.get(i);

                if (el.isJsonObject())
                {
                    JsonObject placementObj = el.getAsJsonObject();

                    if (JsonUtils.hasString(placementObj, "name") &&
                        JsonUtils.hasObject(placementObj, "placement"))
                    {
                        SubRegionPlacement placement = SubRegionPlacement.fromJson(placementObj.get("placement").getAsJsonObject());

                        if (placement != null)
                        {
                            String placementName = placementObj.get("name").getAsString();
                            this.subRegionPlacements.put(placementName, placement);
                        }
                    }
                }
            }
        }

        // Note: This needs to be after reading the sub-regions, so that the enclosing box can be calculated
        // and the grid's default size set correctly.
        JsonUtils.getObjectIfExists(obj, "grid", this.getGridSettings()::fromJson);

        return true;
    }

    public static Path getSaveDirectory()
    {
        String worldName = StringUtils.getWorldOrServerNameOrDefault("__fallback");
        Path dir = DataManager.getDataBaseDirectory("placements").resolve(worldName);

        if (FileUtils.createDirectoriesIfMissing(dir) == false)
        {
            String key = "litematica.message.error.schematic_placement.failed_to_create_directory";
            MessageDispatcher.error().translate(key, dir.toAbsolutePath().toString());
        }

        return dir;
    }

    @Nullable
    protected Path getAvailableFileName()
    {
        if (this.getSchematicFile() == null)
        {
            return null;
        }

        Path dir = getSaveDirectory();
        String schName = FileNameUtils.getFileNameWithoutExtension(this.getSchematicFile().getFileName().toString());
        String nameBase = FileNameUtils.generateSafeFileName(schName);
        int id = 1;
        String name = String.format("%s_%03d.json", nameBase, id);
        Path file = dir.resolve(name);

        while (Files.exists(file))
        {
            ++id;
            name = String.format("%s_%03d.json", nameBase, id);
            file = dir.resolve(name);
        }

        return file;
    }

    protected static int getNextBoxColor()
    {
        int color = Color4f.getColorFromHue(lastColor);
        lastColor += 40;
        return color;
    }

    @Nullable
    public static SchematicPlacement createFromJson(JsonObject obj)
    {
        if (JsonUtils.hasString(obj, "schematic"))
        {
            Path schematicFile = Paths.get(JsonUtils.getString(obj, "schematic"));
            SchematicPlacement placement = new SchematicPlacement(schematicFile);

            if (placement.readFromJson(obj) == false)
            {
                return null;
            }

            if (JsonUtils.hasInteger(obj, "bb_color"))
            {
                placement.setBoundingBoxColor(JsonUtils.getInteger(obj, "bb_color"));
            }
            else
            {
                placement.setBoundingBoxColorToNext();
            }

            placement.loadSchematicFromFileIfEnabled();

            return placement;
        }

        return null;
    }

    @Nullable
    public static SchematicPlacement createFromFile(Path file)
    {
        JsonElement el = JsonUtils.parseJsonFile(file);

        if (el != null && el.isJsonObject())
        {
            SchematicPlacement placement = createFromJson(el.getAsJsonObject());

            if (placement != null)
            {
                placement.placementSaveFile = file.getFileName().toString();
            }

            return placement;
        }

        return null;
    }

    public static SchematicPlacement create(ISchematic schematic, BlockPos origin, String name, boolean enabled)
    {
        return create(schematic, origin, name, enabled, true);
    }

    public static SchematicPlacement create(ISchematic schematic, BlockPos origin, String name, boolean enabled,
                                            boolean offsetToInFrontOfPlayer)
    {
        SchematicPlacement placement = new SchematicPlacement(schematic, schematic.getFile(), origin, name, enabled);

        placement.setBoundingBoxColorToNext();
        placement.resetAllSubRegionsToSchematicValues();

        if (offsetToInFrontOfPlayer)
        {
            placement.position = PositionUtils.getPlacementPositionOffsetToInFrontOfPlayer(origin, placement);
        }

        return placement;
    }
}