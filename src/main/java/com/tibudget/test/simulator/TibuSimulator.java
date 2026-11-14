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
        Application.launch(SimulatorWindow.class, args);
    }
}
