package dinocode.permission;

/**
 * 工具类别（ch06 F5）：模式兜底矩阵按类别给出 Allow/Ask。
 */
public enum Category {
    READ,   // 读文件 / 找文件 / 搜内容
    WRITE,  // 写文件 / 改文件
    EXEC    // 命令执行（含未知工具——N7 最严处理）
}
