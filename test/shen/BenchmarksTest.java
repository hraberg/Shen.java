package shen;

import org.junit.Ignore;
import org.junit.Test;

import static shen.Shen.eval;
import static shen.Shen.install;

public class BenchmarksTest {
    @Test @Ignore
    public void benchmarks() throws Throwable {
        install();
        eval("(cd \"shen/benchmarks\")");
        eval("(load \"README.shen\")");
        eval("(load \"benchmarks.shen\")");
    }

    public static void main(String... args) throws Throwable {
        new BenchmarksTest().benchmarks();
    }
}
