package com.shenqianye.samsara.track;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 回放马的“古老拉丁风格”名字生成器（纯 Java，无 Minecraft 依赖）。
 * <p>
 * 名字由两三段拉丁/影语词根拼接，首字母大写，例如 {@code Aeternvellis}、
 * {@code Umbranox}、{@code Vesperferrum}。只用于捕捉后的马匹名牌。
 */
public final class AncientName {
    private static final String[] STEMS = {
            "Aetern", "Umbr", "Vesper", "Nox", "Lumin", "Somn", "Tenebr",
            "Argent", "Aur", "Ciner", "Lun", "Sax", "Vort", "Obsid", "Pallid",
            "Fer", "Ign", "Glaci", "Sylv", "Mar", "Ast", "Vent", "Corv"
    };
    private static final String[] JOINERS = {
            "a", "e", "i", "o", "u", "ae", "ia", "io", "y", "ei"
    };
    private static final String[] ENDINGS = {
            "vellis", "ferrum", "nox", "ion", "or", "us", "a", "um", "is", "ex",
            "yx", "ara", "orix", "eth", "alis", "arum", "entis", "urnus"
    };
    /** 名牌长度上限（含首字母；避免客户端截断）。 */
    public static final int MAX_LENGTH = 32;

    private AncientName() {
    }

    public static String generate() {
        return generate(ThreadLocalRandom.current().nextLong());
    }

    /** 确定性生成（测试/复现用）。 */
    public static String generate(long seed) {
        Random random = new Random(seed);
        String stem = STEMS[random.nextInt(STEMS.length)];
        String joiner = JOINERS[random.nextInt(JOINERS.length)];
        String ending = ENDINGS[random.nextInt(ENDINGS.length)];
        String name = stem + joiner + ending;
        if (name.length() > MAX_LENGTH) {
            name = name.substring(0, MAX_LENGTH);
        }
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
