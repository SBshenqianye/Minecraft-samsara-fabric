package com.shenqianye.chunkmanager.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.bukkit.DyeColor;
import org.bukkit.block.banner.PatternType;
import org.jetbrains.annotations.NotNull;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class Extension {
    private int Up;
    private int Down;
    private int Left;
    private int Right;
    private int LeftUp;
    private int RightUp;
    private int LeftDown;
    private int RightDown;

    public void addDirection(@NotNull PatternType patternType, @NotNull DyeColor dyeColor) {
        int strength = dyeColor.ordinal() + 1;
        switch (patternType) {
            case STRIPE_TOP -> {
                setUpSide(strength);
            }
            case STRIPE_BOTTOM -> {
                setDownSide(strength);
            }
            case STRIPE_LEFT -> {
                setLeftSide(strength);
            }
            case STRIPE_RIGHT -> {
                setRightSide(strength);
            }
            case SQUARE_TOP_LEFT -> {
                setTopLeftSide(strength);
            }
            case SQUARE_BOTTOM_LEFT -> {
                setBottomLeftSide(strength);
            }
            case SQUARE_TOP_RIGHT -> {
                setTopRightSide(strength);
            }
            case SQUARE_BOTTOM_RIGHT -> {
                setBottomRightSide(strength);
            }
            case STRIPE_CENTER -> {
                // center是竖条
                setCenter(strength);
            }
            case STRIPE_MIDDLE -> {
                // middle是横条
                setMiddle(strength);
            }
            case STRIPE_DOWNLEFT -> {
                setSlash(strength);
            }
            case STRIPE_DOWNRIGHT -> {
                setBackslash(strength);
            }
            case CROSS -> {
                setCross(strength);
            }
            case STRAIGHT_CROSS -> {
                setStraightCross(strength);
            }
            case BORDER -> {
                setBorder(strength);
            }
        }
    }

    public String toString(){
        return String.format(
        """
            +--+--+--+
            | %d  | %d  | %d  |
            +--+--+--+
            | %d  |    | %d  |
            +--+--+--+
            | %d  | %d  | %d  |
            +--+--+--+
        """,
        LeftUp,     Up,     RightUp,
        Left,               Right,
        LeftDown,   Down,   RightDown);
    }

    private void setUpSide(int strength){
        if (strength > Up) Up = strength;
    }

    private void setDownSide(int strength){
        if (strength > Down) Down = strength;
    }

    private void setLeftSide(int strength){
        if (strength > Left) Left = strength;
    }

    private void setRightSide(int strength){
        if (strength > Right) Right = strength;
    }

    private void setTopLeftSide(int strength){
        if (strength > LeftUp) LeftUp = strength;
    }

    private void setBottomLeftSide(int strength){
        if (strength > LeftDown) LeftDown = strength;
    }

    private void setTopRightSide(int strength){
        if (strength > RightUp) RightUp = strength;
    }

    private void setBottomRightSide(int strength){
        if (strength > RightDown) RightDown = strength;
    }

    private void setCenter(int strength){
        setUpSide(strength);
        setDownSide(strength);
    }

    private void setMiddle(int strength){
        setLeftSide(strength);
        setRightSide(strength);
    }

    private void setSlash(int strength){
        setBottomLeftSide(strength);
        setTopRightSide(strength);
    }

    private void setBackslash(int strength){
        setTopLeftSide(strength);
        setBottomRightSide(strength);
    }

    private void setCross(int strength){
        setSlash(strength);
        setBackslash(strength);
    }

    private void setStraightCross(int strength){
        setCenter(strength);
        setMiddle(strength);
    }

    private void setBorder(int strength){
        setCross(strength);
        setStraightCross(strength);
    }
}
