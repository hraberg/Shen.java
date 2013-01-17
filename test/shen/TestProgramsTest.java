package shen;

import org.junit.Ignore;
import org.junit.Test;

import static shen.Shen.eval;
import static shen.Shen.install;

public class TestProgramsTest {
    @Test @Ignore
    public void test_programs() throws Exception {
        install();
        eval("(cd \"shen/test-programs\")");
        eval("(load \"README.shen\")");
        eval("(load \"tests.shen\")");
    }
}
