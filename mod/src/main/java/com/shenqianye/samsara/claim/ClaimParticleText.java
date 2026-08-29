package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.rewind.DiffRecorder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 粒子文字渲染：用 {@code DiffRecorder.FONT_5X7} 点阵字体，
 * 在指定世界坐标处用粒子「写」出文字。
 * <p>
 * 特点：纯粒子、无实体、随玩家距离自然淡出；支持四元数旋转避免万向节锁。
 */
public final class ClaimParticleText {

    /** 默认缩放因子 */
    private static final double DEFAULT_SCALE = 1.5;

    private ClaimParticleText() {
    }

    /**
     * 在 (cx, cy, cz) 处用粒子写出文字（固定平面，不随玩家旋转）。
     */
    public static void draw(ServerLevel level, ServerPlayer player,
                            double cx, double cy, double cz,
                            String text, int gap,
                            ParticleOptions color) {
        drawScaled(level, player, cx, cy, cz, text, gap, color, DEFAULT_SCALE);
    }

    /**
     * 在 (cx, cy, cz) 处用粒子写出文字（可指定缩放因子，固定平面）。
     */
    public static void drawScaled(ServerLevel level, ServerPlayer player,
                                   double cx, double cy, double cz,
                                   String text, int gap,
                                   ParticleOptions color, double scale) {
        if (text == null || text.isEmpty()) return;
        int cursorX = 0;
        for (char ch : text.toUpperCase().toCharArray()) {
            int fi = ch - ' ';
            int[] glyph = (fi >= 0 && fi < DiffRecorder.FONT_5X7.length)
                    ? DiffRecorder.FONT_5X7[fi] : null;
            if (glyph == null) {
                cursorX += gap;
                continue;
            }
            for (int row = 0; row < glyph.length; row++) {
                int bits = glyph[row];
                for (int col = 0; col < 5; col++) {
                    if ((bits & (1 << col)) != 0) {
                        double px = cx + (cursorX + col) * scale;
                        double py = cy - row * scale;
                        double pz = cz;
                        level.sendParticles(player, color,
                                true, true, px, py, pz,
                                1, 0.0, 0.0, 0.0, 0.0);
                    }
                }
            }
            cursorX += gap;
        }
    }

    /**
     * 在 (cx, cy, cz) 处用粒子写出文字，<b>始终朝向玩家</b>（四元数旋转，无万向节锁）。
     * 文字以 (cx, cy, cz) 为中心。
     *
     * @param cx  中心 X
     * @param cy  中心 Y
     * @param cz  中心 Z
     */
    public static void drawFacingScaled(ServerLevel level, ServerPlayer player,
                                         double cx, double cy, double cz,
                                         String text, int gap,
                                         ParticleOptions color, double scale) {
        if (text == null || text.isEmpty()) return;

        // 计算文字总宽度用于居中
        int charCount = text.length();
        double totalWidth = (charCount - 1) * gap * scale + 4 * scale;
        double halfW = totalWidth / 2.0;

        // 构建朝向玩家的四元数旋转
        // 方向向量：从文字中心指向玩家眼睛
        double dirX = player.getX() - cx;
        double dirY = player.getEyeY() - cy;
        double dirZ = player.getZ() - cz;
        float len = (float) Math.sqrt(dirX * dirX + dirY * dirY + dirZ * dirZ);
        if (len < 0.01f) return;

        // 世界 up 向量
        Vector3f worldUp = new Vector3f(0, 1, 0);
        // 朝向向量（归一化）
        Vector3f forward = new Vector3f((float)(dirX/len), (float)(dirY/len), (float)(dirZ/len));
        // right = forward × worldUp
        Vector3f right = new Vector3f(forward).cross(worldUp).normalize();
        // 如果 forward 几乎平行于 worldUp，用备用 up
        if (right.lengthSquared() < 0.001f) {
            worldUp = new Vector3f(0, 0, -1);
            right = new Vector3f(forward).cross(worldUp).normalize();
        }
        // up = right × forward
        Vector3f up = new Vector3f(right).cross(forward).normalize();

        int cursorX = 0;
        for (char ch : text.toUpperCase().toCharArray()) {
            int fi = ch - ' ';
            int[] glyph = (fi >= 0 && fi < DiffRecorder.FONT_5X7.length)
                    ? DiffRecorder.FONT_5X7[fi] : null;
            if (glyph == null) {
                cursorX += gap;
                continue;
            }
            for (int row = 0; row < glyph.length; row++) {
                int bits = glyph[row];
                for (int col = 0; col < 5; col++) {
                    if ((bits & (1 << col)) != 0) {
                        // 局部坐标：localX 沿文字水平方向，localY 沿文字垂直方向
                        float localX = (float)(((cursorX + col) * scale) - halfW);
                        float localY = (float)(-row * scale);
                        // 旋转到世界坐标：pos = center + localX * right + localY * up
                        double px = cx + localX * right.x + localY * up.x;
                        double py = cy + localX * right.y + localY * up.y;
                        double pz = cz + localX * right.z + localY * up.z;
                        level.sendParticles(player, color,
                                true, true, px, py, pz,
                                1, 0.0, 0.0, 0.0, 0.0);
                    }
                }
            }
            cursorX += gap;
        }
    }
}
