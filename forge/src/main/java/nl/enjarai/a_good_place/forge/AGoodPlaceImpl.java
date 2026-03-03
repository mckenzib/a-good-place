package nl.enjarai.a_good_place.forge;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.PackCompatibility;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.util.RandomSource;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.fml.loading.FMLLoader;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.RenderTypeHelper;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.data.loading.DatagenModLoader;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.forgespi.locating.IModFile;
import net.neoforged.neoforge.resource.PathPackResources;
import nl.enjarai.a_good_place.AGoodPlace;
import nl.enjarai.a_good_place.pack.AnimationsManager;
import nl.enjarai.a_good_place.pack.state_tests.BlockStatePredicateType;
import nl.enjarai.a_good_place.particles.BlocksParticlesManager;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;


@Mod(AGoodPlaceImpl.MOD_ID)
public class AGoodPlaceImpl {
    public static final String MOD_ID = AGoodPlace.MOD_ID;

    public AGoodPlaceImpl(IEventBus modEventBus) {
        if (FMLEnvironment.dist == Dist.CLIENT) {

            modEventBus.addListener(this::onSetup);

            addClientReloadListener(AnimationsManager::new, AGoodPlace.res("animations"), modEventBus);

            boolean firstInstall = AGoodPlace.copySamplePackIfNotPresent();
            NeoForge.EVENT_BUS.register(this);

            registerOptionalTexturePack(AGoodPlace.res("default_animations"),
                    Component.nullToEmpty("Default Place Animations"), firstInstall, modEventBus);

            BlockStatePredicateType.init();
            AGoodPlace.IS_DEV = !FMLLoader.isProduction();
        }
    }

    public void onSetup(FMLClientSetupEvent event) {
       AGoodPlace.onSetup(null);
    }

    @SubscribeEvent
    public void onLevelLoad(ClientPlayerNetworkEvent.LoggingIn event) {
        AnimationsManager.populateTags(event.getPlayer().level().registryAccess());
    }

    @SubscribeEvent
    public void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel().isClientSide()) {
            BlocksParticlesManager.clear();
        }
    }

    @SubscribeEvent
    public void onRenderWorld(RenderLevelStageEvent event) {
        if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_ENTITIES) {
            BlocksParticlesManager.renderParticles(event.getPoseStack(), event.getPartialTick().getGameTimeDeltaPartialTick(false));
        }
    }

    @SubscribeEvent
    public void onClientTick(LevelTickEvent.Post tickEvent) {
        if (tickEvent.getLevel().isClientSide()) {
            BlocksParticlesManager.tickParticles((ClientLevel) tickEvent.getLevel());
        }
    }

    public static void renderBlock(BakedModel model, long seed, PoseStack poseStack, MultiBufferSource buffer, BlockState state,
                                   Level level, BlockPos pos, BlockRenderDispatcher dispatcher) {
        //same as ForgeHooksClient.renderPistonMovedBlocks (what pistons use)
        for (var renderType : model.getRenderTypes(state, RandomSource.create(seed), ModelData.EMPTY)) {
            VertexConsumer vertexConsumer = buffer.getBuffer(RenderTypeHelper.getMovingBlockRenderType(renderType));
            dispatcher.getModelRenderer().tesselateBlock(level, model, state, pos, poseStack, vertexConsumer, false,
                    RandomSource.create(), state.getSeed(pos), OverlayTexture.NO_OVERLAY, ModelData.EMPTY, renderType);
        }
    }

    public static void addClientReloadListener(Supplier<PreparableReloadListener> listener, ResourceLocation location, IEventBus modEventBus) {
        Consumer<RegisterClientReloadListenersEvent> eventConsumer = (event) -> {
            event.registerReloadListener(listener.get());
        };
        modEventBus.addListener(eventConsumer);
    }

    public static void registerOptionalTexturePack(ResourceLocation folderName, Component displayName, boolean defaultEnabled, IEventBus modEventBus) {
        registerResourcePack(PackType.CLIENT_RESOURCES,
                () -> {
                    IModFile file = ModList.get().getModFileById(folderName.getNamespace()).getFile();
                    try (PathPackResources pack = new PathPackResources(
                            folderName.toString(),
                            true,
                            file.findResource("resourcepacks/" + folderName.getPath()))) {
                        var metadata = Objects.requireNonNull(pack.getMetadataSection(PackMetadataSection.TYPE));
                        return Pack.create(
                                folderName.toString(),
                                displayName,
                                defaultEnabled,
                                (s) -> pack,
                                new Pack.Info(metadata.getDescription(), PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), false),
                                PackType.CLIENT_RESOURCES,
                                Pack.Position.TOP,
                                false,
                                PackSource.BUILT_IN);
                    } catch (Exception ee) {
                        if (!DatagenModLoader.isRunningDataGen()) ee.printStackTrace();
                    }
                    return null;
                },
                modEventBus
        );
    }

    public static void registerResourcePack(PackType packType, @Nullable Supplier<Pack> packSupplier, IEventBus modEventBus) {
        if (packSupplier == null) return;
        Consumer<AddPackFindersEvent> consumer = event -> {
            if (event.getPackType() == packType) {
                var p = packSupplier.get();
                if (p != null) {
                    event.addRepositorySource(infoConsumer -> infoConsumer.accept(packSupplier.get()));
                }
            }
        };
        modEventBus.addListener(consumer);
    }


}
