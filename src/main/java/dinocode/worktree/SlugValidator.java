package dinocode.worktree;

/**
 * worktree slug 校验与命名映射（ch14 F1/F2/T1）。
 */
public final class SlugValidator {

    public static final int MAX_LENGTH = 64;
    private static final String VALID_SEGMENT = "^[a-zA-Z0-9._-]+$";

    private SlugValidator() {
    }

    /**
     * 校验 slug：非空、长度 ≤ 64、按 / 切段后每段合法、显式拒绝 . 与 .. 段（F1）。
     * 错误分类抛 {@link IllegalArgumentException}——在任何 git 命令或路径拼接之前先跑。
     */
    public static void validate(String slug) {
        if (slug == null || slug.isBlank()) {
            throw new IllegalArgumentException("worktree slug cannot be empty");
        }
        if (slug.length() > MAX_LENGTH) {
            throw new IllegalArgumentException(
                    "worktree slug too long (max " + MAX_LENGTH + " chars): " + slug);
        }
        for (String segment : slug.split("/")) {
            if (segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException(
                        "worktree slug contains illegal path segment: " + segment);
            }
            if (!segment.matches(VALID_SEGMENT)) {
                throw new IllegalArgumentException(
                        "worktree slug contains illegal characters: " + segment);
            }
        }
    }

    /** slug → 目录名：/ 替换为 +（git 安全但不在 slug 字符集），避免嵌套冲突（F2）。 */
    public static String flatten(String slug) {
        return slug.replace('/', '+');
    }

    /** slug → 分支名：统一加 worktree- 前缀，便于从 git branch 输出识别（F2）。 */
    public static String branchName(String slug) {
        return "worktree-" + flatten(slug);
    }
}
