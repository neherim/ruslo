package io.github.neherim.ruslo.perf.runner;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class BenchmarkRunnerTest {
    @Test
    void excludesWarmupsAndRunsEveryMeasurement() {
        var calls = new ArrayList<Integer>();
        var measured = BenchmarkRunner.run(2, 3, index -> {
            calls.add(index);
            return index;
        });
        assertThat(calls).containsExactly(-1, -2, 0, 1, 2);
        assertThat(measured).containsExactly(0, 1, 2);
    }

    @Test
    void rejectsConfigurationsWithoutMeasurementsOrWithNegativeWarmup() {
        assertThatIllegalArgumentException().isThrownBy(() -> BenchmarkRunner.run(0, 0, index -> index));
        assertThatIllegalArgumentException().isThrownBy(() -> BenchmarkRunner.run(-1, 3, index -> index));
    }
}
