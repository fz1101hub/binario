import java.io.File;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.runner.Description;
import org.junit.runner.JUnitCore;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

/**
 * Minimal JUnit 4 runner for the SDK-free harness.
 *
 * Usage: java ... RunAll <class-name | @file-with-class-names | dir:/path/to/test-classes>...
 *
 * A "dir:" argument is scanned for top-level classes that carry at least one @Test method
 * (or an @RunWith annotation); helper classes without tests are skipped.
 * Output: one line per failure
 *   FAIL\t<class>\t<test>\t<exception message> @ <first stack frame in it.binario>
 * and a final
 *   TOTAL\trun=<n> fail=<n> ignored=<n>
 * Exit status 0 only when every test passed.
 */
public class RunAll {
    public static void main(String[] args) throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("@")) {
                for (String line : Files.readAllLines(Paths.get(arg.substring(1)))) {
                    line = line.trim();
                    if (!line.isEmpty()) addIfTest(classes, line);
                }
            } else if (arg.startsWith("dir:")) {
                Path root = Paths.get(arg.substring(4));
                try (Stream<Path> s = Files.walk(root)) {
                    List<String> names = new ArrayList<>();
                    s.filter(p -> p.toString().endsWith(".class"))
                     .map(p -> root.relativize(p).toString())
                     .filter(n -> !n.contains("$"))
                     .map(n -> n.substring(0, n.length() - 6).replace(File.separatorChar, '.'))
                     .sorted()
                     .forEach(names::add);
                    for (String n : names) addIfTest(classes, n);
                }
            } else {
                addIfTest(classes, arg);
            }
        }
        final PrintStream out = System.out;
        JUnitCore core = new JUnitCore();
        core.addListener(new RunListener() {
            @Override public void testFailure(Failure failure) {
                Description d = failure.getDescription();
                Throwable t = failure.getException();
                String msg = String.valueOf(t);
                int nl = msg.indexOf('\n');
                if (nl >= 0) msg = msg.substring(0, nl);
                msg = msg.replace('\t', ' ');
                String frame = "";
                if (t != null) {
                    for (StackTraceElement e : t.getStackTrace()) {
                        if (e.getClassName().startsWith("it.binario")) {
                            frame = e.getClassName() + "." + e.getMethodName() + "(" + e.getFileName() + ":" + e.getLineNumber() + ")";
                            break;
                        }
                    }
                }
                out.println("FAIL\t" + d.getClassName() + "\t" + d.getMethodName() + "\t" + msg + " @ " + frame);
                out.flush();
            }
        });
        Result r = core.run(classes.toArray(new Class<?>[0]));
        out.println("TOTAL\trun=" + r.getRunCount() + " fail=" + r.getFailureCount() + " ignored=" + r.getIgnoreCount()
                + " classes=" + classes.size() + " time_ms=" + r.getRunTime());
        out.flush();
        System.exit(r.wasSuccessful() ? 0 : 1);
    }

    private static void addIfTest(List<Class<?>> classes, String name) {
        try {
            Class<?> c = Class.forName(name, false, RunAll.class.getClassLoader());
            if (c.isInterface() || Modifier.isAbstract(c.getModifiers()) || c.isAnonymousClass()) return;
            if (c.isAnnotationPresent(org.junit.runner.RunWith.class)) { classes.add(c); return; }
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
                for (Method m : k.getDeclaredMethods()) {
                    if (m.isAnnotationPresent(org.junit.Test.class)) { classes.add(c); return; }
                }
            }
        } catch (Throwable t) {
            System.out.println("FAIL\t" + name + "\t<load>\t" + t + " @ ");
        }
    }
}
