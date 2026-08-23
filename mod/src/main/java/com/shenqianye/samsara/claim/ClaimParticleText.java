package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.rewind.DiffRecorder;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 粒子文字渲染（§6.7 增强）：用 {@code DiffRecorder.FONT_5X7} 点阵字体，
 * 在指定世界坐标处用粒子「写」出文字，替代旧版 ArmorStand 浮动文字。
 * <p>
 * 特点：纯粒子、无实体、随玩家距离自然淡出；适合显示圈地尺寸/方向等简短信息。
 */
public final class ClaimParticleText {

    private ClaimParticleText() {
    }

    /**
     * 在 (cx, cy, cz) 处用粒子写出文字（居中：以起始点为左上角）。
     * 文字固定在 XY 平面（面朝 +Z 方向），不随玩家旋转。
     */
    public static void draw(ServerLevel level, ServerPlayer player,
                            double cx, double cy, double cz,
                            String text, int gap,
                            ParticleOptions color) {
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
                        double px = cx + (cursorX + col);
                        double py = cy - row;
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
     * 在 (cx, cy, cz) 处用粒子写出文字，<b>始终朝向玩家</b>（billboard 效果）。
     * 文字在垂直平面内渲染，水平轴自动旋转到面向玩家的方向；垂直轴始终为 Y 轴。
     * 文字以 (cx, cy, cz) 为中心水平居中。
     *
     * @param cx  中心 X（方块坐标）
     * @param cy  顶部 Y（方块坐标；行0=顶部，向下画）
     * @param cz  中心 Z（方块坐标）
     */
    public static void drawFacing(ServerLevel level, ServerPlayer player,
                                  double cx, double cy, double cz,
                                  String text, int gap,
                                  ParticleOptions color) {
        if (text == null || text.isEmpty()) return;
        // 玩家相对旗帜的方向角（XZ 平面），文字平面垂直于视线
        double dx = player.getX() - cx;
        double dz = player.getZ() - cz;
        double angle = Math.atan2(dz, dx) - Math.PI / 2; // -90° → 垂直于视线且正面朝玩家
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);

        // 计算文字总宽度用于居中
        int charCount = text.length();
        double totalWidth = (charCount - 1) * gap + 4; // 最后一个字符宽 5 列，取 4 作为右边界
        double halfW = totalWidth / 2.0;

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
                        // 局部坐标：localX 沿文字水平方向（已居中），localY 向上
                        double localX = (cursorX + col) - halfW;
                        double localY = -row;
                        // 旋转到面向玩家的 XZ 方向
                        double px = cx + localX * cos;
                        double py = cy + localY;
                        double pz = cz + localX * sin;
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
