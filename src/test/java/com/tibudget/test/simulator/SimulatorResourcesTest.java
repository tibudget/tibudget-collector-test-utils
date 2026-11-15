package com.tibudget.test.simulator;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class SimulatorResourcesTest {
    @Test
    void stylesheetIsAvailableInTheSimulatorPackage() {
        assertNotNull(SimulatorWindow.class.getResource("simulator.css"), "The simulator window and dialogs need the packaged stylesheet");
    }
}
