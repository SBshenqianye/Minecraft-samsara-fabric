package com.shenqianye.samsara.output;

/**
 * 服务端渲染后的可序列化片段。Web 预览只按这些片段显示，不在前端另写格式逻辑。
 */
public record OutputPreviewFragment(String kind, String text, String color, boolean bold,
                                    boolean italic, boolean underline, boolean strikethrough,
                                    boolean clickable, String clickCommand, String clickMode,
                                    String hover) {
    public static OutputPreviewFragment text(String kind, String text, String color) {
        return new OutputPreviewFragment(kind, text, color, false, false, false, false,
                false, "", "", "");
    }
}
