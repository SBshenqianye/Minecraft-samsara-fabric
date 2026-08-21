package com.shenqianye.chunkmanager.pojo;

import java.util.ArrayList;
import java.util.List;

public class Shape {
    protected List<LineSegment> segments;

    public Shape() {
        segments = new ArrayList<>();
    }

    public void addSegment(LineSegment segment) {
        segments.add(segment);
    }

    public List<LineSegment> getSegments() {
        return segments;
    }

    // 可以添加其他方法，比如计算不规则面的面积
}