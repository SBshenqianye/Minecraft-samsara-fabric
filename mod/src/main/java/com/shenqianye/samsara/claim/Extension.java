package com.shenqianye.samsara.claim;

import java.util.Map;

/**
 * 旗帜方向扩展（设计文档 §6.2.1）——**8 方向模型**。
 * <p>
 * 每个方向的扩展距离（区块数，1~16）。由图案 ID + 染料颜色构建，
 * 经旗帜朝向旋转后用于计算影响坐标。
 *
 * <h3>视角约定（用户 2026-08-22 校正）</h3>
 * MC 图案 ID（stripe_left 等）描述的是<b>观看者视角</b>（看旗帜正面的人），
 * 而本类的字段描述的是<b>旗帜纹章学视角</b>（持盾者视角）。
 * 两者左右相反、前后相反，合起来就是一次 <b>180° 点对称</b>：
 * <ul>
 *   <li>观看者左 = 纹章学右；观看者右 = 纹章学左</li>
 *   <li>观看者前（图案顶部）= 纹章学后；观看者后（图案底部）= 纹章学前</li>
 * </ul>
 * 该规则同样适用于四个斜向（角图案）：观看者"前左"= 纹章学"后右"，以此类推。
 * 斜向条纹（`stripe_downleft`/`stripe_downright`）与 `cross`/`straight_cross`/`border`
 * 在 180° 点对称下<b>集合不变</b>（斜线本身关于中心点对称），因此无需换算。
 *
 * <h3>字段 → 世界方向（facing = north 时，即未旋转）</h3>
 * up→北(-Z)、right→东(+X)、down→南(+Z)、left→西(-X)、
 * upRight→东北、downRight→东南、downLeft→西南、upLeft→西北。
 * <p>
 * {@link #rotate(String)} 按旗帜朝向旋转整个方向集（仅支持 4 个正朝向，见用户 2026-09-10 裁决）。
 */
public class Extension {
    // ---- 4 正方向 ----
    private int up;         // 旗帜前方（纹章学）→ 北
    private int right;      // 旗帜右方 → 东
    private int down;       // 旗帜后方 → 南
    private int left;       // 旗帜左方 → 西
    // ---- 4 斜方向 ----
    private int upRight;    // 前右 → 东北
    private int downRight;  // 后右 → 东南
    private int downLeft;   // 后左 → 西南
    private int upLeft;     // 前左 → 西北

    // ---- MC 26.2 图案 asset_id → Extension 字段映射（共 15 种） ----
    // pattern ID = 观看者视角；Extension 字段 = 纹章学视角（180° 点对称）
    private static final Map<String, String> PATTERN_DIR;
    static {
        var b = new java.util.HashMap<String, String>();
        // 4 条单向条纹：观看者前/后/左/右 → 纹章学 后/前/右/左
        b.put("stripe_top", "down");
        b.put("stripe_bottom", "up");
        b.put("stripe_left", "right");
        b.put("stripe_right", "left");
        // 4 个角图案：观看者 前左/前右/后左/后右 → 纹章学 后右/后左/前右/前左
        b.put("square_top_left", "downRight");
        b.put("square_top_right", "downLeft");
        b.put("square_bottom_left", "upRight");
        b.put("square_bottom_right", "upLeft");
        // 2 条斜向条纹：关于中心点对称，集合不变
        b.put("stripe_downleft", "slash");          // 后左 + 前右
        b.put("stripe_downright", "backslash");    // 前左 + 后右
        // 组合图案
        b.put("stripe_center", "center");           // 竖条：前 + 后
        b.put("stripe_middle", "middle");           // 横条：左 + 右
        b.put("cross", "cross");                    // 4 个斜方向
        b.put("straight_cross", "straightCross");   // 4 个正方向
        b.put("border", "border");                  // 全部 8 方向
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
            case "up"            -> setUp(strength);
            case "upRight"       -> setUpRight(strength);
            case "right"         -> setRight(strength);
            case "downRight"     -> setDownRight(strength);
            case "down"          -> setDown(strength);
            case "downLeft"      -> setDownLeft(strength);
            case "left"          -> setLeft(strength);
            case "upLeft"        -> setUpLeft(strength);
            case "center"        -> { setUp(strength); setDown(strength); }          // 竖条
            case "middle"        -> { setLeft(strength); setRight(strength); }        // 横条
            case "slash"         -> { setDownLeft(strength); setUpRight(strength); }  // ↙↗
            case "backslash"     -> { setUpLeft(strength); setDownRight(strength); }  // ↖↘
            case "cross"         -> { setSlash(strength); setBackslash(strength); }   // 4 斜
            case "straightCross" -> { setCenter(strength); setMiddle(strength); }     // 4 正
            case "border"        -> { setCross(strength); setStraightCross(strength); } // 全部 8
            default -> { }
        }
    }

    /**
     * 根据旗帜朝向旋转整个方向集。
     * 仅支持 4 个正朝向（N/S/E/W）——用户 2026-09-10 裁决：保护区域只按正东南西北管理。
     * 旋转次数 = 从 north 顺时针到目标的 90° 步数。
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

    /**
     * 顺时针 90° 旋转（8 方向版）。
     * <p>
     * 语义：方向集整体顺时针转 90°，即"原来指向北的那条，现在指向东"。
     * 因此新字段值取自"顺时针方向的下一个旧字段"：
     * 新北←旧西、新东北←旧西北、新东←旧北、新东南←旧东北、
     * 新南←旧东、新西南←旧东南、新西←旧南、新西北←旧西南。
     * <p>
     * 与旧的 4 方向版完全兼容（up←left、left←down、down←right、right←up）。
     * 全部 8 个旧值先取出再赋值，避免自赋值串扰。
     */
    private void rotate90() {
        int oUp = up, oUpRight = upRight, oRight = right, oDownRight = downRight;
        int oDown = down, oDownLeft = downLeft, oLeft = left, oUpLeft = upLeft;

        up        = oLeft;       // 北   ← 西
        upRight   = oUpLeft;     // 东北 ← 西北
        right     = oUp;         // 东   ← 北
        downRight = oUpRight;    // 东南 ← 东北
        down      = oRight;      // 南   ← 东
        downLeft  = oDownRight;  // 西南 ← 东南
        left      = oDown;       // 西   ← 南
        upLeft    = oDownLeft;   // 西北 ← 西南
    }

    // ---- Getters ----
    public int getUp()        { return up; }
    public int getUpRight()   { return upRight; }
    public int getRight()     { return right; }
    public int getDownRight() { return downRight; }
    public int getDown()      { return down; }
    public int getDownLeft()  { return downLeft; }
    public int getLeft()      { return left; }
    public int getUpLeft()    { return upLeft; }

    /** 是否有任意方向非零（判断是否为有效圈地旗） */
    public boolean hasAnyDirection() {
        return up > 0 || upRight > 0 || right > 0 || downRight > 0
                || down > 0 || downLeft > 0 || left > 0 || upLeft > 0;
    }

    /** 非零方向的条数（用于报告/调试，如 border = 8） */
    public int directionCount() {
        int n = 0;
        if (up > 0) n++;
        if (upRight > 0) n++;
        if (right > 0) n++;
        if (downRight > 0) n++;
        if (down > 0) n++;
        if (downLeft > 0) n++;
        if (left > 0) n++;
        if (upLeft > 0) n++;
        return n;
    }

    // ---- 内部辅助（一律取最大值） ----
    private void setUp(int s)        { if (s > up) up = s; }
    private void setUpRight(int s)   { if (s > upRight) upRight = s; }
    private void setRight(int s)     { if (s > right) right = s; }
    private void setDownRight(int s) { if (s > downRight) downRight = s; }
    private void setDown(int s)      { if (s > down) down = s; }
    private void setDownLeft(int s)  { if (s > downLeft) downLeft = s; }
    private void setLeft(int s)      { if (s > left) left = s; }
    private void setUpLeft(int s)    { if (s > upLeft) upLeft = s; }

    private void setCenter(int s)       { setUp(s); setDown(s); }
    private void setMiddle(int s)       { setLeft(s); setRight(s); }
    private void setSlash(int s)        { setDownLeft(s); setUpRight(s); }
    private void setBackslash(int s)    { setUpLeft(s); setDownRight(s); }
    private void setCross(int s)        { setSlash(s); setBackslash(s); }
    private void setStraightCross(int s){ setCenter(s); setMiddle(s); }
    private void setBorder(int s)       { setCross(s); setStraightCross(s); }
}
