package dinocode.hook;

import dinocode.permission.Matcher;

import java.util.List;

/**
 * 条件表达式（ch12 F11~F15）：顶层 all_of / any_of 二选一（不混用，F11/AC16），
 * 原子条件 = payload 字段路径 + Matcher（复用权限匹配器，G4/N7）。
 */
public record Condition(CombineMode mode, List<AtomCondition> atoms) {

    public enum CombineMode {ALL_OF, ANY_OF}

    public Condition {
        atoms = atoms == null ? List.of() : List.copyOf(atoms);
    }

    /** 原子条件：payload 字段路径 + 匹配器（F12/F13）。 */
    public record AtomCondition(String field, Matcher matcher) {
    }
}
