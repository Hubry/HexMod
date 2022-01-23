package at.petrak.hexcasting.common.items;

import at.petrak.hexcasting.client.ClientTickCounter;
import at.petrak.hexcasting.client.RenderLib;
import at.petrak.hexcasting.hexmath.HexPattern;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import com.mojang.datafixers.util.Either;
import com.mojang.math.Matrix4f;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TranslatableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderTooltipEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

public class ItemScroll extends Item {
    public static final String TAG_OP_ID = "op_id";
    public static final String TAG_PATTERN = "pattern";

    public ItemScroll(Properties pProperties) {
        super(pProperties);
    }

    @Override
    public Component getName(ItemStack pStack) {
        var tag = pStack.getOrCreateTag();
        if (tag.contains(TAG_OP_ID)) {
            return new TranslatableComponent("item.hexcasting.scroll.of",
                I18n.get("hexcasting.spell." + ResourceLocation.tryParse(tag.getString(TAG_OP_ID))));
        } else if (tag.contains(TAG_PATTERN)) {
            return new TranslatableComponent("item.hexcasting.scroll");
        } else {
            return new TranslatableComponent("item.hexcasting.scroll.empty");
        }
    }

    @SubscribeEvent
    @OnlyIn(Dist.CLIENT)
    public static void makeTooltip(RenderTooltipEvent.GatherComponents evt) {
        ItemStack stack = evt.getItemStack();
        if (!stack.isEmpty() && stack.getItem() instanceof ItemScroll) {
            var tooltip = evt.getTooltipElements();

            var tag = stack.getOrCreateTag();
            HexPattern pattern = null;
            if (tag.contains(TAG_PATTERN)) {
                pattern = HexPattern.DeserializeFromNBT(tag.getCompound(TAG_PATTERN));
            }

            tooltip.add(Either.right(new TooltipGreeble(pattern)));
        }
    }

    // https://github.com/VazkiiMods/Quark/blob/master/src/main/java/vazkii/quark/content/client/tooltip/MapTooltips.java
    // yoink
    public static class TooltipGreeble implements ClientTooltipComponent, TooltipComponent {
        private static final ResourceLocation MAP_BG = new ResourceLocation(
            "minecraft:textures/map/map_background.png");
        private static final float SIZE = 96f;

        @Nullable
        private final HexPattern pattern;
        @Nullable
        private final List<Vec2> zappyPoints;
        @Nullable
        private final List<Vec2> pathfinderDots;
        private final float scale;

        public TooltipGreeble(@Nullable HexPattern pattern) {
            this.pattern = pattern;
            if (this.pattern != null) {
                // Do two passes: one with a random size to find a good COM and one with the real calculations

                var com1 = this.pattern.getCenter(1);
                var lines1 = this.pattern.toLines(1, com1.negated());


                var maxDist = -1f;
                for (var dot : lines1) {
                    var dist = Mth.sqrt(dot.distanceToSqr(com1));
                    if (dist > maxDist) {
                        maxDist = dist;
                    }
                }
                this.scale = Math.min(10, this.getHeight() / 2.1f / maxDist);

                var com2 = this.pattern.getCenter(this.scale);
                var lines2 = this.pattern.toLines(this.scale, com2.negated());
                this.zappyPoints = RenderLib.makeZappy(lines2, 10f, 0.8f, 0f);
                this.pathfinderDots = lines2.stream().distinct().collect(Collectors.toList());
            } else {
                this.zappyPoints = null;
                this.pathfinderDots = null;
                this.scale = 0f;
            }
        }

        @Override
        public void renderImage(Font font, int mouseX, int mouseY, PoseStack ps, ItemRenderer pItemRenderer,
            int pBlitOffset) {
            var width = this.getWidth(font);
            var height = this.getHeight();

            // far as i can tell "mouseX" and "mouseY" are actually the positions of the corner of the tooltip
            ps.pushPose();
            ps.translate(mouseX, mouseY, 500);
            RenderSystem.enableBlend();
            renderBG(ps);

            // renderText happens *before* renderImage for some asinine reason
            if (this.pattern != null) {
//                RenderSystem.disableBlend();
                ps.translate(0, 0, 100);

                RenderSystem.setShader(GameRenderer::getPositionColorShader);
                RenderSystem.disableCull();
                RenderSystem.blendFunc(GlStateManager.SourceFactor.SRC_ALPHA,
                    GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA);
                ps.translate(width / 2f, height / 2f, 1);

                var mat = ps.last().pose();
                RenderLib.drawLineSeq(mat, this.zappyPoints, 5f, 0,
                    210, 200, 200, 255, null);
                RenderLib.drawLineSeq(mat, this.zappyPoints, 2f, 0,
                    200, 190, 190, 200,
                    ClientTickCounter.getTickCount() / 40f, 0.5f);
                RenderLib.drawSpot(mat, this.zappyPoints.get(0), 2.5f, 1f, 0.1f, 0.15f, 0.6f);

                for (var dot : this.pathfinderDots) {
                    RenderLib.drawSpot(mat, dot, 1.5f, 0.82f, 0.8f, 0.8f, 0.5f);
                }
            }

            ps.popPose();
        }

        private static void renderBG(PoseStack ps) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
            RenderSystem.setShaderTexture(0, MAP_BG);


            // i wish i liked mobius front enough ot get to the TIS puzzles
            BufferBuilder buffer = Tesselator.getInstance().getBuilder();
            Matrix4f neo = ps.last().pose();

            buffer.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            buffer.vertex(neo, 0, 0, 0.0F).uv(0.0F, 0.0F).endVertex();
            buffer.vertex(neo, 0, SIZE, 0.0F).uv(0.0F, 1.0f).endVertex();
            buffer.vertex(neo, SIZE, SIZE, 0.0F).uv(1.0F, 1.0f).endVertex();
            buffer.vertex(neo, SIZE, 0, 0.0F).uv(1.0F, 0.0F).endVertex();
            buffer.end();
            BufferUploader.end(buffer);


        }

        @Override
        public int getWidth(Font pFont) {
            return (int) SIZE;
        }

        @Override
        public int getHeight() {
            return (int) SIZE;
        }
    }
}