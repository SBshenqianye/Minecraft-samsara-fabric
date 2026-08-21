package com.shenqianye.chunkmanager.pojo;

public class LineSegment {
    protected Point start;
    protected Point end;

    public LineSegment(Point start, Point end) {
        this.start = start;
        this.end = end;
    }

    public Point getStart() {
        return start;
    }

    public Point getEnd() {
        return end;
    }

    // 可以添加其他方法，比如计算线段的长度
}