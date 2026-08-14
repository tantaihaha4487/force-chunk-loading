package com.tantaihaha.forcechunkloading.force_chunk_loading;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Persistent marker positions for one server dimension. */
public final class ChunkLoadData extends SavedData {
    private static final Codec<ChunkLoadData> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            BlockPos.CODEC.listOf().fieldOf("positions").forGetter(ChunkLoadData::positionsAsList)
    ).apply(instance, ChunkLoadData::new));

    public static final SavedDataType<ChunkLoadData> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath(ForceChunkLoading.MOD_ID, "chunk_load_positions"),
            ChunkLoadData::new,
            CODEC,
            null
    );

    private final Set<BlockPos> positions;

    public ChunkLoadData() {
        this(List.of());
    }

    private ChunkLoadData(Collection<BlockPos> positions) {
        this.positions = new LinkedHashSet<>();
        positions.forEach(position -> this.positions.add(new BlockPos(position)));
    }

    public Set<BlockPos> positions() {
        return Set.copyOf(positions);
    }

    public boolean add(BlockPos position) {
        boolean changed = positions.add(new BlockPos(position));
        if (changed) {
            setDirty();
        }
        return changed;
    }

    public boolean remove(BlockPos position) {
        boolean changed = positions.remove(position);
        if (changed) {
            setDirty();
        }
        return changed;
    }

    private List<BlockPos> positionsAsList() {
        return new ArrayList<>(positions);
    }
}
