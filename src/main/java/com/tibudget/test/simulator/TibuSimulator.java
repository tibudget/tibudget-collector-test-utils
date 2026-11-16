package com.tibudget.test.simulator;

import javafx.application.Application;

/**
 * Launch the generic desktop simulator with the test classpath.
 * Keep this launcher separate from Application so JavaFX can run on the classpath on Java 21.
 */
public final class TibuSimulator {
    private TibuSimulator() {
    }

    public static void main(String[] args) {
        if (args.length > 0 && args[0].equals("--headless")) {
            CollectorCli.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        Application.launch(SimulatorWindow.class, args);
    }
}
