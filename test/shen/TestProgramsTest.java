package shen;

import org.junit.Ignore;
import org.junit.Test;

import static shen.Shen.eval;
import static shen.Shen.install;

public class TestProgramsTest {
    @Test @Ignore
    public void test_programs() throws Throwable {
        install();
        eval("(cd \"shen/test\")");
        eval("(load \"README.shen\")");
        eval("(load \"tests.shen\")");
    }

    public static void main(String... args) throws Throwable {
        new TestProgramsTest().test_programs();
    }
}
