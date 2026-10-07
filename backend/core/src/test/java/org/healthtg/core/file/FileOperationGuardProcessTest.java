package org.healthtg.core.file;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

class FileOperationGuardProcessTest {
    @TempDir Path root;
    @Test void separateJvmWaitsForSameFileAndCrashReleasesLockWithoutDeletingIt() throws Exception {
        UUID id=UUID.randomUUID();Path firstFlag=root.resolve("first"),secondFlag=root.resolve("second");
        Process first=start(id,firstFlag),second=null;
        try {
            awaitFlag(firstFlag,first);
            second=start(id,secondFlag);
            assertFalse(second.waitFor(400,TimeUnit.MILLISECONDS));assertFalse(Files.exists(secondFlag));
            first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));
            awaitFlag(secondFlag,second);
            second.getOutputStream().write(1);second.getOutputStream().flush();
            assertTrue(second.waitFor(10,TimeUnit.SECONDS));assertEquals(0,second.exitValue());
            assertTrue(Files.exists(root.resolve(".locks").resolve(id+".lock")));
            assertEquals("acquired",new FileOperationGuard(root.toString()).withFile(id,()->"acquired"));
        } finally {first.destroyForcibly();if(second!=null)second.destroyForcibly();}
    }
    Process start(UUID id,Path flag) throws Exception {
        String classpath=Path.of(FileOperationGuardProcessTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
                +File.pathSeparator+Path.of(FileOperationGuard.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        return new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString(),"-cp",classpath,
                LockProcess.class.getName(),root.toString(),id.toString(),flag.toString()).redirectError(root.resolve(flag.getFileName()+".err").toFile()).start();
    }
    static void awaitFlag(Path flag,Process process) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
        while(!Files.exists(flag)&&process.isAlive()&&System.nanoTime()<deadline)Thread.sleep(20);
        assertTrue(Files.exists(flag),"Child must reach the protected operation");
    }
    public static class LockProcess {
        public static void main(String[] args) {
            new FileOperationGuard(args[0]).withFile(UUID.fromString(args[1]),()->{
                try {Files.writeString(Path.of(args[2]),"acquired");System.in.read();return null;}
                catch(java.io.IOException e){throw new java.io.UncheckedIOException(e);}
            });
        }
    }
}
