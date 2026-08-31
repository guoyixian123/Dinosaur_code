package dinocode.tool;

/**
 * 工具执行结果——永远以值返回，从不抛异常（F9/N4）。
 */
public record Result(String content, boolean isError) {

    public static Result ok(String content) {
        return new Result(content, false);
    }

    public static Result error(String content) {
        return new Result(content, true);
    }
}
