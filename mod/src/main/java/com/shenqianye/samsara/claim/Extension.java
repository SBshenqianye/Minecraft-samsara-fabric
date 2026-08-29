package com.shenqianye.samsara.claim;

import java.util.Map;

/**
 * 旗帜方向扩展（设计文档 §6.2.1，简化版：仅4方向）。
 * <p>
 * 每个方向的扩展距离（区块数，1~16）。由图案 ID + 染料颜色构建，
 * 经旗帜朝向旋转后用于计算影响坐标。
 * <p>
 * 关键：MC 图案 ID（stripe_left 等）描述的是<b>观看者视角</b>（看旗帜正面的人），
 * 而 Extension 字段（up/down/left/right）描述的是<b>旗帜纹章学视角</b>（持盾者视角）。
 * 两者左右相反、前后相反：
 * <ul>
 *   <li>观看者左 (stripe_left) = 旗帜纹章学右 → Extension.right</li>
 *   <li>观看者右 (stripe_right) = 旗帜纹章学左 → Extension.left</li>
 *   <li>观看者前/上方 (stripe_top) = 旗帜纹章学后 → Extension.down</li>
 *   <li>观看者后/下方 (stripe_bottom) = 旗帜纹章学前 → Extension.up</li>
 * </ul>
 * <p>
 * rotate(facing) 根据旗帜朝向旋转后，getInfluencedCoords 将 Extension 映射到世界坐标：
 * up→北(-Z), down→南(+Z), left→西(-X), right→东(+X)（以 north 为基准）。
 */
public class Extension {
    private int up;       // 旗帜前方（纹章学）
    private int down;     // 旗帜后方（纹章学）
    private int left;     // 旗帜左方（纹章学）
    private int right;    // 旗帜右方（纹章学）

    // ---- MC 26.2 图案 asset_id → Extension 字段映射 ----
    // pattern ID = 观看者视角；Extension 字段 = 纹章学视角（两者相反）
    private static final Map<String, String> PATTERN_DIR;
    static {
        var b = new java.util.HashMap<String, String>();
        // 观看者前 (stripe_top) → 纹章学后 (down)
        // 观看者后 (stripe_bottom) → 纹章学前 (up)
        // 观看者左 (stripe_left) → 纹章学右 (right)
        // 观看者右 (stripe_right) → 纹章学左 (left)
        b.put("stripe_top","down");      b.put("stripe_bottom","up");
        b.put("stripe_left","right");    b.put("stripe_right","left");
        // 组合图案
        b.put("stripe_center","center"); b.put("stripe_middle","middle");
        b.put("cross","cross"); b.put("straight_cross","straightCross");
        b.put("border","border");
        PATTERN_DIR = java.util.Map.copyOf(b);
    }

    /**
     * 添加一个图案的方向扩展。color 为 MC NBT Color 字段值（0-15）。
     * strength = color + 1（1~16 区块）。同一方向取最大值。
     */
    public void addDirection(String patternId, int color) {
        int strength = color + 1;
        String dir = PATTERN_DIR.get(patternId);
        if (dir == null) return;

        switch (dir) {
            case "up"           -> setUp(strength);
            case "down"         -> setDown(strength);
            case "left"         -> setLeft(strength);
            case "right"        -> setRight(strength);
            case "center"       -> { setUp(strength); setDown(strength); }
            case "middle"       -> { setLeft(strength); setRight(strength); }
            case "cross"        -> { setCenter(strength); setMiddle(strength); }
            case "straightCross"-> { setCenter(strength); setMiddle(strength); }
            case "border"       -> { setCenter(strength); setMiddle(strength); }
        }
    }

    /**
     * 根据旗帜朝向旋转整个方向集。
     * 仅支持4个正方向（N/S/E/W），旋转次数 = 从 north 顺时针到目标的90°步数。
     */
    public Extension rotate(String facing) {
        int times = switch (facing) {
            case "north" -> 0;
            case "east"  -> 1;
            case "south" -> 2;
            case "west"  -> 3;
            default -> 0;
        };
        for (int i = 0; i < times; i++) {
            rotate90();
        }
        return this;
    }

    /** 顺时针 90° 旋转（4方向版） */
    private void rotate90() {
        int temp = up;
        up = left;
        left = down;
        down = right;
        right = temp;
    }

    // ---- Getters ----
    public int getUp()    { return up; }
    public int getDown()  { return down; }
    public int getLeft()  { return left; }
    public int getRight() { return right; }

    /** 是否有任意方向非零（判断是否为有效圈地旗） */
    public boolean hasAnyDirection() {
        return up > 0 || down > 0 || left > 0 || right > 0;
    }

    // ---- 内部辅助 ----
    private void setUp(int s)    { if (s > up) up = s; }
    private void setDown(int s)  { if (s > down) down = s; }
    private void setLeft(int s)  { if (s > left) left = s; }
    private void setRight(int s) { if (s > right) right = s; }
    private void setCenter(int s){ setUp(s); setDown(s); }
    private void setMiddle(int s){ setLeft(s); setRight(s); }
}
