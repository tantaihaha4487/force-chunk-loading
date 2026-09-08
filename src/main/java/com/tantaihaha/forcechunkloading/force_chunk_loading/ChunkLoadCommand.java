package com.tantaihaha.forcechunkloading.force_chunk_loading;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Administrative inspection and maintenance commands for chunk loaders. */
public final class ChunkLoadCommand {
    private static final int PAGE_SIZE = 8;

    private ChunkLoadCommand() {
    }

    public static void initialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> register(dispatcher));
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("chunkloader")
                .requires(ChunkLoadCommand::hasPermission);

        root.then(Commands.literal("status").executes(ChunkLoadCommand::status));
        addPaged(root, "list", ChunkLoadCommand::list);
        addPaged(root, "orphans", ChunkLoadCommand::orphans);
        addPaged(root, "check", ChunkLoadCommand::orphans);

        root.then(Commands.literal("remove")
                .then(Commands.argument("dimension", DimensionArgument.dimension())
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("y", IntegerArgumentType.integer())
                                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                                .executes(ChunkLoadCommand::remove))))));
        root.then(Commands.literal("reload").executes(ChunkLoadCommand::reload));
        dispatcher.register(root);
    }

    private static void addPaged(
            LiteralArgumentBuilder<CommandSourceStack> root,
            String name,
            PageHandler handler
    ) {
        root.then(Commands.literal(name)
                .executes(context -> handler.run(context, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(context -> handler.run(
                                context,
                                IntegerArgumentType.getInteger(context, "page")
                        ))));
    }

    private static boolean hasPermission(CommandSourceStack source) {
        return source.permissions() instanceof LevelBasedPermissionSet permissions
                && permissions.level().isEqualOrHigherThan(PermissionLevel.GAMEMASTERS);
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<ServerLevel> levels = levels(source.getServer());
        if (levels.isEmpty()) {
            source.sendSuccess(() -> Component.literal("No dimensions are loaded."), false);
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Chunk loader status:"), false);
        for (ServerLevel level : levels) {
            List<ChunkLoadService.MarkerInfo> markers = ChunkLoadService.inspectMarkers(level);
            int active = count(markers, ChunkLoadService.MarkerStatus.ACTIVE);
            int orphan = count(markers, ChunkLoadService.MarkerStatus.ORPHAN);
            int unloaded = count(markers, ChunkLoadService.MarkerStatus.UNLOADED);
            int forced = level.getChunkSource().getForceLoadedChunks().size();
            int pending = ChunkLoadService.pendingScanCount(level);
            String line = "%s: markers=%d (active=%d, orphan=%d, unloaded=%d), forcedChunks=%d, pendingScans=%d"
                    .formatted(dimensionName(level), markers.size(), active, orphan, unloaded, forced, pending);
            source.sendSuccess(() -> Component.literal(line), false);
        }
        return levels.size();
    }

    private static int list(CommandContext<CommandSourceStack> context, int page) {
        List<MarkerEntry> entries = entries(context.getSource().getServer());
        if (entries.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("No persisted chunk loader markers."), false);
            return 0;
        }

        int pages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page > pages) {
            context.getSource().sendFailure(Component.literal("Page " + page + " does not exist (pages: " + pages + ")."));
            return 0;
        }
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, entries.size());
        context.getSource().sendSuccess(
                () -> Component.literal("Chunk loader markers (page " + page + "/" + pages + "):"),
                false
        );
        for (int index = from; index < to; index++) {
            MarkerEntry entry = entries.get(index);
            context.getSource().sendSuccess(() -> Component.literal(entry.format()), false);
        }
        return to - from;
    }

    private static int orphans(CommandContext<CommandSourceStack> context, int page) {
        List<MarkerEntry> entries = entries(context.getSource().getServer()).stream()
                .filter(entry -> entry.info().status() == ChunkLoadService.MarkerStatus.ORPHAN)
                .toList();
        int unresolved = entries(context.getSource().getServer()).stream()
                .filter(entry -> entry.info().status() == ChunkLoadService.MarkerStatus.UNLOADED)
                .mapToInt(ignored -> 1)
                .sum();
        if (entries.isEmpty()) {
            String message = unresolved == 0
                    ? "No orphan markers found."
                    : "No confirmed orphan markers; " + unresolved + " marker(s) are in unloaded chunks.";
            context.getSource().sendSuccess(() -> Component.literal(message), false);
            return 0;
        }

        int pages = (entries.size() + PAGE_SIZE - 1) / PAGE_SIZE;
        if (page > pages) {
            context.getSource().sendFailure(Component.literal("Page " + page + " does not exist (pages: " + pages + ")."));
            return 0;
        }
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, entries.size());
        context.getSource().sendSuccess(
                () -> Component.literal("Orphan markers (page " + page + "/" + pages + "):"),
                false
        );
        for (int index = from; index < to; index++) {
            MarkerEntry entry = entries.get(index);
            context.getSource().sendSuccess(() -> Component.literal(entry.format()), false);
        }
        if (unresolved > 0) {
            context.getSource().sendSuccess(
                    () -> Component.literal(unresolved + " marker(s) could not be checked because their chunks are unloaded."),
                    false
            );
        }
        return to - from;
    }

    private static int remove(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        Identifier identifier = context.getArgument("dimension", Identifier.class);
        ServerLevel level = source.getServer().getLevel(ResourceKey.create(Registries.DIMENSION, identifier));
        if (level == null) {
            source.sendFailure(Component.literal("Unknown dimension: " + identifier));
            return 0;
        }

        BlockPos position = new BlockPos(
                IntegerArgumentType.getInteger(context, "x"),
                IntegerArgumentType.getInteger(context, "y"),
                IntegerArgumentType.getInteger(context, "z")
        );
        ChunkLoadService.RemoveResult result = ChunkLoadService.removeMarker(level, position);
        switch (result) {
            case REMOVED_MARKER -> source.sendSuccess(
                    () -> Component.literal("Removed chunk loader marker at " + location(level, position) + "."),
                    true
            );
            case REMOVED_ORPHAN -> source.sendSuccess(
                    () -> Component.literal("Removed orphan marker record at " + location(level, position) + "."),
                    true
            );
            case NOT_TRACKED -> source.sendFailure(Component.literal("No persisted marker at " + location(level, position) + "."));
            case CHUNK_UNLOADED -> source.sendFailure(Component.literal(
                    "Cannot remove " + location(level, position) + ": its chunk is not loaded."
            ));
        }
        return result == ChunkLoadService.RemoveResult.REMOVED_MARKER
                || result == ChunkLoadService.RemoveResult.REMOVED_ORPHAN ? 1 : 0;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ForceChunkConfig.ReloadResult result = ForceChunkLoading.reloadConfig();
        if (!result.success()) {
            source.sendFailure(Component.literal("Config reload failed; the previous configuration remains active: " + result.error()));
            return 0;
        }

        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.literal("Configuration loaded. Reloading server data and recipes..."), true);
        server.reloadResources(server.getPackRepository().getSelectedIds()).whenCompleteAsync((ignored, error) -> {
            if (error == null) {
                source.sendSuccess(() -> Component.literal("Configuration and recipes reloaded."), true);
            } else {
                source.sendFailure(Component.literal("Configuration loaded, but data reload failed: " + error.getMessage()));
            }
        }, server);
        return 1;
    }

    private static List<MarkerEntry> entries(MinecraftServer server) {
        List<MarkerEntry> entries = new ArrayList<>();
        for (ServerLevel level : levels(server)) {
            for (ChunkLoadService.MarkerInfo info : ChunkLoadService.inspectMarkers(level)) {
                entries.add(new MarkerEntry(level, info));
            }
        }
        entries.sort(Comparator.comparing(MarkerEntry::dimension)
                .thenComparingInt(entry -> entry.info().position().getX())
                .thenComparingInt(entry -> entry.info().position().getY())
                .thenComparingInt(entry -> entry.info().position().getZ()));
        return entries;
    }

    private static List<ServerLevel> levels(MinecraftServer server) {
        List<ServerLevel> levels = new ArrayList<>();
        server.getAllLevels().forEach(levels::add);
        levels.sort(Comparator.comparing(ChunkLoadCommand::dimensionName));
        return levels;
    }

    private static int count(
            List<ChunkLoadService.MarkerInfo> markers,
            ChunkLoadService.MarkerStatus status
    ) {
        return (int) markers.stream().filter(info -> info.status() == status).count();
    }

    private static String dimensionName(ServerLevel level) {
        return level.dimension().identifier().toString();
    }

    private static String location(ServerLevel level, BlockPos position) {
        ChunkPos chunk = ChunkPos.containing(position);
        return dimensionName(level) + " " + position.getX() + " " + position.getY() + " " + position.getZ()
                + " (chunk " + chunk.x() + "," + chunk.z() + ")";
    }

    @FunctionalInterface
    private interface PageHandler {
        int run(CommandContext<CommandSourceStack> context, int page);
    }

    private record MarkerEntry(ServerLevel level, ChunkLoadService.MarkerInfo info) {
        private String dimension() {
            return dimensionName(level);
        }

        private String format() {
            BlockPos position = info.position();
            ChunkPos chunk = ChunkPos.containing(position);
            return "%s %d %d %d (chunk %d,%d) [%s]".formatted(
                    dimension(),
                    position.getX(),
                    position.getY(),
                    position.getZ(),
                    chunk.x(),
                    chunk.z(),
                    info.status().name().toLowerCase()
            );
        }
    }
}
