package com.shenqianye.samsara.claim;

import java.util.Map;

/**
 * 旗帜方向扩展（设计文档 §6.2.1）。
 * <p>
 * 每个方向的扩展距离（区块数，1~16）。由图案 ID + 染料颜色构建，
 * 经旗帜朝向旋转后用于计算影响坐标。
 */
public class Extension {
    private int up;       // 北 (Z-)
    private int down;     // 南 (Z+)
    private int left;     // 西 (X-)
    private int right;    // 东 (X+)
    private int leftUp;   // 西北 (X- Z-)
    private int rightUp;  // 东北 (X+ Z-)
    private int leftDown; // 西南 (X- Z+)
    private int rightDown;// 东南 (X+ Z+)

    // ---- MC 26.2 NBT 图案 ID → 方向映射 ----
    private static final Map<String, String> PATTERN_DIR;
    static {
        var b = new java.util.HashMap<String, String>();
        b.put("ts","up");  b.put("bs","down");  b.put("ls","left"); b.put("rs","right");
        b.put("tl","leftUp"); b.put("bl","leftDown"); b.put("tr","rightUp"); b.put("br","rightDown");
        b.put("cs","center"); b.put("ms","middle"); b.put("dls","slash"); b.put("drs","backslash");
        b.put("cr","cross"); b.put("sc","straightCross"); b.put("bri","border");
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
            case "leftUp"       -> setLeftUp(strength);
            case "rightUp"      -> setRightUp(strength);
            case "leftDown"     -> setLeftDown(strength);
            case "rightDown"    -> setRightDown(strength);
            case "center"       -> { setUp(strength); setDown(strength); }
            case "middle"       -> { setLeft(strength); setRight(strength); }
            case "slash"        -> { setLeftDown(strength); setRightUp(strength); }
            case "backslash"    -> { setLeftUp(strength); setRightDown(strength); }
            case "cross"        -> { setSlash(strength); setBackslash(strength); }
            case "straightCross"-> { setCenter(strength); setMiddle(strength); }
            case "border"       -> { setCross(strength); setStraightCross(strength); }
        }
    }

    /** 根据旗帜朝向旋转整个方向集（§6.2.1 旋转校正） */
    public Extension rotate(String facing) {
        int times = switch (facing) {
            case "north"     -> 0;
            case "north_east"-> 7;
            case "east"      -> 6;
            case "south_east"-> 5;
            case "south"     -> 4;
            case "south_west"-> 3;
            case "west"      -> 2;
            case "north_west"-> 1;
            default -> 0;
        };
        for (int i = 0; i < times; i++) {
            rotate45();
        }
        return this;
    }

    /** 顺时针 45° 旋转 */
    private void rotate45() {
        int temp = up;
        up = leftUp;
        leftUp = left;
        left = leftDown;
        leftDown = down;
        down = rightDown;
        rightDown = right;
        right = rightUp;
        rightUp = temp;
    }

    // ---- Getters ----
    public int getUp()       { return up; }
    public int getDown()     { return down; }
    public int getLeft()     { return left; }
    public int getRight()    { return right; }
    public int getLeftUp()   { return leftUp; }
    public int getRightUp()  { return rightUp; }
    public int getLeftDown() { return leftDown; }
    public int getRightDown(){ return rightDown; }

    /** 是否有任意方向非零（判断是否为有效圈地旗） */
    public boolean hasAnyDirection() {
        return up > 0 || down > 0 || left > 0 || right > 0
                || leftUp > 0 || rightUp > 0 || leftDown > 0 || rightDown > 0;
    }

    // ---- 内部辅助 ----
    private void setUp(int s)        { if (s > up) up = s; }
    private void setDown(int s)      { if (s > down) down = s; }
    private void setLeft(int s)      { if (s > left) left = s; }
    private void setRight(int s)     { if (s > right) right = s; }
    private void setLeftUp(int s)    { if (s > leftUp) leftUp = s; }
    private void setRightUp(int s)   { if (s > rightUp) rightUp = s; }
    private void setLeftDown(int s)  { if (s > leftDown) leftDown = s; }
    private void setRightDown(int s) { if (s > rightDown) rightDown = s; }
    private void setCenter(int s)    { setUp(s); setDown(s); }
    private void setMiddle(int s)    { setLeft(s); setRight(s); }
    private void setSlash(int s)     { setLeftDown(s); setRightUp(s); }
    private void setBackslash(int s) { setLeftUp(s); setRightDown(s); }
    private void setCross(int s)     { setSlash(s); setBackslash(s); }
    private void setStraightCross(int s) { setCenter(s); setMiddle(s); }
}
