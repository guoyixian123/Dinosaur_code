package dinocode.permission;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 危险命令黑名单单测（ch06 F1/N1/AC1）。
 */
class BlacklistTest {

    @Test
    void hitsKnownDangerousCommands() {
        assertTrue(Blacklist.hits("rm -rf /"));
        assertTrue(Blacklist.hits("rm -fr ~"));
        assertTrue(Blacklist.hits("rm -rf $HOME"));
        assertTrue(Blacklist.hits("cd /tmp && rm -rf /"));
        assertTrue(Blacklist.hits("dd if=/dev/zero of=/dev/sda"));
        assertTrue(Blacklist.hits("dd if=/dev/zero of=/dev/nvme0n1"));
        assertTrue(Blacklist.hits(":(){ :|:& };:"));
        assertTrue(Blacklist.hits("mkfs.ext4 /dev/sda1"));
        assertTrue(Blacklist.hits("echo x > /dev/sda"));
        assertTrue(Blacklist.hits("chmod -R 777 /"));
    }

    @Test
    void allowsBenignCommands() {
        assertFalse(Blacklist.hits("rm -rf ./build"));
        assertFalse(Blacklist.hits("rm build/output.txt"));
        assertFalse(Blacklist.hits("git status"));
        assertFalse(Blacklist.hits("ls -la"));
        assertFalse(Blacklist.hits("mvn test"));
        assertFalse(Blacklist.hits("echo hello > out.txt"));
        assertFalse(Blacklist.hits(""));
    }

    @Test
    void describeReturnsMatchingPattern() {
        assertTrue(Blacklist.describe("rm -rf /").contains("rm"));
        assertEquals2(Blacklist.describe("git status"));
    }

    private static void assertEquals2(String s) {
        org.junit.jupiter.api.Assertions.assertEquals("", s);
    }
}
