package com.shenqianye.chunkmanager.pojo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@AllArgsConstructor
@NoArgsConstructor
@Data
public class Point {
    protected double X;
    protected double Y;

    public double getX() {
        return X;
    }

    public double getY() {
        return Y;
    }
}